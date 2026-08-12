package org.koitharu.kotatsu.scrobbling

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.ElementsIntoSet
import org.koitharu.kotatsu.scrobbling.common.domain.Scrobbler

/**
 * Reading trackers, of which this build has none.
 *
 * Shikimori, AniList, MyAnimeList and Kitsu were removed along with the
 * credentials they needed: those were OAuth applications registered to
 * upstream, and an app under a different name has no business authenticating
 * as them.
 *
 * The module stays so the empty set still has a binding — everything that
 * injects `Set<Scrobbler>` keeps working and simply finds nothing.
 */
@Module
@InstallIn(SingletonComponent::class)
object ScrobblingModule {

	@Provides
	@ElementsIntoSet
	fun provideScrobblers(): Set<Scrobbler> = emptySet()
}
