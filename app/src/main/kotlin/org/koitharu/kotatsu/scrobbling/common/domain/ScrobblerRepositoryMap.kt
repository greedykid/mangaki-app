package org.koitharu.kotatsu.scrobbling.common.domain

import org.koitharu.kotatsu.scrobbling.common.data.ScrobblerRepository
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import javax.inject.Inject

/** No trackers are built in, so nothing resolves. See [ScrobblerService]. */
class ScrobblerRepositoryMap @Inject constructor() {

	operator fun get(scrobblerService: ScrobblerService): ScrobblerRepository =
		throw UnsupportedOperationException("No scrobbler services are available in this build")
}
