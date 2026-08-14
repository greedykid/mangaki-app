package org.koitharu.kotatsu.settings.sources.health

import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.core.view.MenuProvider
import androidx.fragment.app.viewModels
import androidx.preference.Preference
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.ui.BasePreferenceFragment
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.observe
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

		else -> false
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
						viewModel.check(result.source)
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
