package org.koitharu.kotatsu.scrobbling.common.domain.model

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

/**
 * Reading trackers this build supports — none.
 *
 * Shikimori, AniList, MyAnimeList and Kitsu were removed with the credentials
 * they needed: those were OAuth applications registered to upstream, and an
 * app under a different name has no business authenticating as them.
 *
 * The type is kept rather than deleted because the scrobbling tables, their
 * Room migrations and the backup format all still reference it. An empty enum
 * makes every list of trackers empty and every lookup fail to resolve, which
 * is what "no trackers" should mean, without rewriting the schema.
 */
enum class ScrobblerService(
	val id: Int,
	@StringRes val titleResId: Int,
	@DrawableRes val iconResId: Int,
)
