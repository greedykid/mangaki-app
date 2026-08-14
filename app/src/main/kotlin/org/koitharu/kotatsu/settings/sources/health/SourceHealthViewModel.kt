package org.koitharu.kotatsu.settings.sources.health

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import org.koitharu.kotatsu.core.model.unwrap
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import javax.inject.Inject

/**
 * Asks every enabled source for one page of manga and reports what came back.
 *
 * The point is where it runs. A source that answers nothing is usually not
 * broken in any way its author could fix — it is behind a bot wall that turns
 * away datacentres, or a domain that has moved, or a block sitting between one
 * particular reader and the site. Which of those it is depends entirely on
 * whose connection is asking, so the only test whose answer means anything is
 * one run from the reader's own phone. That is what this is.
 */
@HiltViewModel
class SourceHealthViewModel @Inject constructor(
	private val sourcesRepository: MangaSourcesRepository,
	private val repositoryFactory: MangaRepository.Factory,
) : BaseViewModel() {

	private val state = MutableStateFlow<List<SourceHealth>>(emptyList())
	val results: StateFlow<List<SourceHealth>> = state.asStateFlow()

	private var job: Job? = null

	init {
		checkAll()
	}

	fun checkAll() {
		val previous = job
		job = launchLoadingJob(Dispatchers.Default) {
			previous?.cancelAndJoin()
			val sources = sourcesRepository.getEnabledSources().filter { it.unwrap() is MangaParserSource }
			state.value = sources.map { SourceHealth(it, SourceHealth.Status.Checking) }

			// Four at a time. Every one of these is a real request to somebody
			// else's server, and firing forty at once is both slower in practice
			// and the kind of traffic that gets an IP blocked — which would be an
			// ironic way to fail a health check.
			val semaphore = Semaphore(MAX_PARALLELISM)
			sources.map { source ->
				launch {
					semaphore.withPermit {
						update(source, probe(source))
					}
				}
			}.joinAll()
		}
	}

	fun check(source: MangaSource) {
		(viewModelScope + Dispatchers.Default).launch {
			update(source, SourceHealth.Status.Checking)
			update(source, probe(source))
		}
	}

	private suspend fun probe(source: MangaSource): SourceHealth.Status = runCatchingCancellable {
		// A source that has not answered in half a minute is not going to, and a
		// reader watching a list of them should not wait on the slowest.
		withTimeout(TIMEOUT_MS) {
			repositoryFactory.create(source).getList(offset = 0, order = null, filter = null)
		}
	}.fold(
		onSuccess = { list -> SourceHealth.Status.Ok(list.size) },
		// The throwable is carried rather than a string: turning it into
		// something a reader can read needs resources, and the fragment has them.
		onFailure = { error -> SourceHealth.Status.Failed(error) },
	)

	private fun update(source: MangaSource, status: SourceHealth.Status) {
		state.value = state.value.map { if (it.source == source) it.copy(status = status) else it }
	}

	private companion object {
		private const val MAX_PARALLELISM = 4
		private const val TIMEOUT_MS = 30_000L
	}
}

data class SourceHealth(
	val source: MangaSource,
	val status: Status,
) {

	sealed interface Status {

		data object Checking : Status

		/**
		 * An answer arrived. [count] can still be zero — the request succeeded and
		 * the source had nothing to say, which is a different problem from a
		 * failure and is reported as one.
		 */
		data class Ok(val count: Int) : Status

		data class Failed(val error: Throwable) : Status
	}
}
