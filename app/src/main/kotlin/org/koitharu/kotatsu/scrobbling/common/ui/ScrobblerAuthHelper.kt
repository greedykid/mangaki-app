package org.koitharu.kotatsu.scrobbling.common.ui

import android.content.Context
import org.koitharu.kotatsu.scrobbling.common.domain.ScrobblerRepositoryMap
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerUser
import javax.inject.Inject

/**
 * Signs a reader in to a tracker, of which this build has none.
 *
 * Every method takes a [ScrobblerService], and no value of that type can be
 * constructed any more, so none of them can actually be called. The class stays
 * because [org.koitharu.kotatsu.core.exceptions.resolve.ExceptionResolver] holds
 * a `Provider` for it to answer a `ScrobblerAuthRequiredException` — an
 * exception nothing can raise now, but the wiring is cheaper to keep than to
 * unpick from the resolver.
 *
 * `startAuth` used to open the tracker's OAuth page in a browser. It no longer
 * does anything, and says so, rather than reporting a success it did not have.
 */
class ScrobblerAuthHelper @Inject constructor(
	private val repositoriesMap: ScrobblerRepositoryMap,
) {

	fun isAuthorized(scrobbler: ScrobblerService) = repositoriesMap[scrobbler].isAuthorized

	fun getCachedUser(scrobbler: ScrobblerService): ScrobblerUser? {
		return repositoriesMap[scrobbler].cachedUser
	}

	suspend fun getUser(scrobbler: ScrobblerService): ScrobblerUser {
		return repositoriesMap[scrobbler].loadUser()
	}

	fun startAuth(context: Context, scrobbler: ScrobblerService): Result<Unit> = runCatching {
		throw UnsupportedOperationException("No scrobbler services are available in this build")
	}
}
