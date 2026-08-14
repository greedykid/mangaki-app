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
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.koitharu.kotatsu.core.model.unwrap
import org.koitharu.kotatsu.core.network.MangaHttpClient
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.parser.ParserMangaRepository
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.await
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
	@MangaHttpClient private val okHttpClient: OkHttpClient,
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

	/**
	 * Describes what a source's own front page actually contains.
	 *
	 * A source that answers with no manga in it has usually been rebuilt, and
	 * fixing its parser means seeing the new markup — which is the one thing
	 * that cannot be done from a machine the site refuses. Every one of these
	 * sits behind a bot wall that turns away datacentres, while the phone asking
	 * here has already been let through and holds the cookie that proves it.
	 *
	 * Deliberately a summary and not the page. Raw HTML runs to hundreds of
	 * kilobytes, which Android's clipboard truncates unpredictably, and the
	 * useful part is small: what the site calls itself, how big the page is,
	 * whether a challenge came back instead, which CSS class names dominate —
	 * a Tailwind soup reads very differently from a Themesia theme — and one
	 * real link with the markup around it.
	 *
	 * Sent through the app's own client so it carries the same headers, cookies
	 * and proxy the parser would have used. Anything else would be describing a
	 * different request than the one that failed.
	 */
	suspend fun diagnose(source: MangaSource): String {
		val repository = repositoryFactory.create(source) as? ParserMangaRepository
			?: return "${source.name}: not a parser source"
		val url = "https://${repository.domain}/"
		val report = StringBuilder(source.name).append('\n').append(url).append('\n')

		runCatchingCancellable {
			withTimeout(TIMEOUT_MS) {
				val request = Request.Builder()
					.get()
					.url(url)
					.headers(repository.getRequestHeaders())
					.build()
				okHttpClient.newCall(request).await().use { response ->
					val body = response.body.string()
					report.append("HTTP ").append(response.code)
						.append(" · ").append(body.length / 1024).append(" KB")
						.append(" · ").append(response.request.url).append('\n')

					val document = Jsoup.parse(body, url)
					report.append("title: ").append(document.title()).append('\n')
					report.append("challenge: ")
						.append(document.getElementById("challenge-error-text") != null)
						.append('\n')

					val classes = HashMap<String, Int>()
					for (element in document.getAllElements()) {
						for (name in element.classNames()) {
							classes[name] = (classes[name] ?: 0) + 1
						}
					}
					report.append("\nclasses: ")
					classes.entries.sortedByDescending { it.value }.take(25)
						.joinTo(report, " ") { "${it.key}(${it.value})" }

					val link = document.select("a[href]").firstOrNull { a ->
						LINK_HINTS.any { it in a.attr("href") }
					}
					report.append("\n\nfirst entry link:\n")
						.append(link?.parent()?.outerHtml()?.take(1500) ?: "none found")
				}
			}
		}.onFailure { error ->
			report.append("failed: ").append(error.javaClass.simpleName)
				.append(": ").append(error.message)
		}
		return report.toString()
	}

	private fun update(source: MangaSource, status: SourceHealth.Status) {
		state.value = state.value.map { if (it.source == source) it.copy(status = status) else it }
	}

	private companion object {
		private const val MAX_PARALLELISM = 4
		private const val TIMEOUT_MS = 30_000L
		private val LINK_HINTS = arrayOf("/manga/", "/series/", "/komik/", "/manhwa/")
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
