package org.koitharu.kotatsu.settings.sources.health

import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.core.view.MenuProvider
import androidx.fragment.app.viewModels
import androidx.preference.Preference
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.BasePreferenceFragment
import org.koitharu.kotatsu.core.ui.dialog.buildAlertDialog
import org.koitharu.kotatsu.core.util.ext.copyToClipboard
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.viewLifecycleScope
import org.koitharu.kotatsu.parsers.model.MangaSource

/**
 * One row per enabled source, saying whether it answered.
 *
 * Built as preferences rather than as a list screen of its own: this is a
 * settings-shaped thing, the rows are title-and-summary and nothing more, and
 * everything it needs — the layout, the scrolling, the insets — already exists
 * here. Tapping a row asks that one source again, which is what a reader does
 * after changing DNS or solving a challenge.
 */
@AndroidEntryPoint
class SourceHealthFragment : BasePreferenceFragment(R.string.source_health), MenuProvider {

	private val viewModel by viewModels<SourceHealthViewModel>()
	private val preferences = HashMap<MangaSource, Preference>()

	override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
		preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		activity?.addMenuProvider(this, viewLifecycleOwner)
		viewModel.results.observe(viewLifecycleOwner, ::bind)
	}

	override fun onDestroyView() {
		preferences.clear()
		super.onDestroyView()
	}

	override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
		inflater.inflate(R.menu.opt_source_health, menu)
	}

	override fun onMenuItemSelected(menuItem: MenuItem): Boolean = when (menuItem.itemId) {
		R.id.action_update -> {
			viewModel.checkAll()
			true
		}

		R.id.action_copy -> {
			copyResults()
			true
		}

		else -> false
	}

	/**
	 * The same rows as plain text, one per line.
	 *
	 * Reporting what a source did otherwise means a screenshot, and a screenshot
	 * of this screen truncates exactly the part that matters: two sources here
	 * are called "ManhwaL…" and nothing distinguishes them. Text does not
	 * truncate, and it can be pasted into an issue.
	 */
	private fun copyResults() {
		val context = context ?: return
		val results = viewModel.results.value
		if (results.isEmpty()) {
			return
		}
		val width = results.maxOf { it.source.getTitle(context).length }
		val text = results.joinToString("\n") { result ->
			val name = result.source.getTitle(context).padEnd(width)
			val outcome = when (val status = result.status) {
				is SourceHealth.Status.Checking -> getString(R.string.loading_)
				is SourceHealth.Status.Ok -> if (status.count > 0) {
					getString(R.string.source_health_ok, status.count)
				} else {
					getString(R.string.source_health_empty)
				}

				is SourceHealth.Status.Failed -> status.error.getDisplayMessage(context.resources)
			}
			"$name  $outcome"
		}
		context.copyToClipboard(getString(R.string.source_health), text)
		view?.let { Snackbar.make(it, R.string.source_health_copied, Snackbar.LENGTH_SHORT).show() }
	}

	/**
	 * A row that answered is only worth re-asking. A row that failed is worth
	 * more than that: the error is often longer than a summary line, and the
	 * usual cause — the site having moved — has a fix the reader can apply
	 * themselves, in this source's own settings, without waiting for anyone to
	 * ship a new build. Four Indonesian sources changed domain in a month, so
	 * that is not a rare road.
	 */
	private fun onRowClicked(source: MangaSource) {
		val status = viewModel.results.value.firstOrNull { it.source == source }?.status
		val error = (status as? SourceHealth.Status.Failed)?.error
		if (error == null) {
			viewModel.check(source)
			return
		}
		val context = context ?: return
		buildAlertDialog(context) {
			setTitle(source.getTitle(context))
			setMessage(error.getDisplayMessage(context.resources))
			setPositiveButton(R.string.source_health_recheck) { _, _ ->
				viewModel.check(source)
			}
			setNeutralButton(R.string.settings) { _, _ ->
				router.openSourceSettings(source)
			}
			// Not a cancel button. Dismissing is what tapping outside is for, and
			// this is the one action that lets somebody who cannot reach the site
			// fix its parser: it describes what the source actually served, from
			// a connection the source will talk to.
			setNegativeButton(R.string.source_health_diagnose) { _, _ ->
				copyDiagnostics(source)
			}
		}.show()
	}

	private fun copyDiagnostics(source: MangaSource) {
		val context = context ?: return
		viewLifecycleScope.launch {
			val report = withContext(Dispatchers.Default) { viewModel.diagnose(source) }
			context.copyToClipboard(getString(R.string.source_health_diagnose), report)
			view?.let { Snackbar.make(it, R.string.source_health_copied, Snackbar.LENGTH_SHORT).show() }
		}
	}

	private fun bind(results: List<SourceHealth>) {
		val screen = preferenceScreen ?: return
		val context = screen.context

		// Rebuilt only when the set of sources changes, not on every status
		// update: replacing the rows mid-check would make the list jump under a
		// reader who is watching it fill in.
		if (preferences.keys != results.mapTo(HashSet()) { it.source }) {
			screen.removeAll()
			preferences.clear()
			for (result in results) {
				val preference = Preference(context).apply {
					isPersistent = false
					isSingleLineTitle = false
					title = result.source.getTitle(context)
					setOnPreferenceClickListener {
						onRowClicked(result.source)
						true
					}
				}
				screen.addPreference(preference)
				preferences[result.source] = preference
			}
		}

		for (result in results) {
			preferences[result.source]?.summary = when (val status = result.status) {
				is SourceHealth.Status.Checking -> getString(R.string.loading_)

				is SourceHealth.Status.Ok -> if (status.count > 0) {
					getString(R.string.source_health_ok, status.count)
				} else {
					// Answered, with nothing in it. Worth its own wording: a source
					// that returns an empty page is usually one whose parser no
					// longer matches the site, which is a different thing to chase
					// than a connection that never arrived.
					getString(R.string.source_health_empty)
				}

				is SourceHealth.Status.Failed -> status.error.getDisplayMessage(resources)
			}
		}
	}
}
