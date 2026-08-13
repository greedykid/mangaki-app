package org.koitharu.kotatsu.explore.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.core.LocalizedAppContext
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.dao.MangaSourcesDao
import org.koitharu.kotatsu.core.db.entity.MangaSourceEntity
import org.koitharu.kotatsu.core.model.MangaSourceInfo
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.model.isNsfw
import org.koitharu.kotatsu.core.parser.external.ExternalMangaSource
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.core.ui.util.ReversibleHandle
import org.koitharu.kotatsu.core.util.ext.flattenLatest
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.network.CloudFlareHelper
import org.koitharu.kotatsu.parsers.util.mapNotNullToSet
import org.koitharu.kotatsu.parsers.util.mapToSet
import java.util.Collections
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MangaSourcesRepository @Inject constructor(
	@LocalizedAppContext private val context: Context,
	private val db: MangaDatabase,
	private val settings: AppSettings,
) {

	private val isNewSourcesAssimilated = AtomicBoolean(false)
	private val dao: MangaSourcesDao
		get() = db.getSourcesDao()

	/**
	 * Every source this build offers, narrowed to the locales in [ENABLED_LOCALES].
	 *
	 * The upstream library ships around 1250 parsers for every language it
	 * covers. Mangaki is an Indonesian reader, so the other ~1130 are noise:
	 * they bloat the source catalogue, the search-everywhere screens and the
	 * settings list with entries nobody here will ever switch on.
	 *
	 * Filtered in one place on purpose. Every source list in the app funnels
	 * through this property, so nothing downstream needs to know about it —
	 * and widening the selection later is one line rather than an audit.
	 *
	 * Note the parsers themselves are a Maven dependency, not source in this
	 * repo, so they cannot be deleted; excluding them here is what "only
	 * Indonesian sources" means in practice.
	 */
	val allMangaSources: Set<MangaParserSource> = Collections.unmodifiableSet(
		EnumSet.noneOf<MangaParserSource>(MangaParserSource::class.java).also {
			MangaParserSource.entries.filterTo(it) { source ->
				!source.isBroken &&
					(source.locale in ENABLED_LOCALES || source.name in EXTRA_SOURCES) &&
					source.name !in DEAD_SOURCES &&
					source.name !in JS_GATED_SOURCES
			}
		}
	)

	suspend fun getEnabledSources(): List<MangaSource> {
		assimilateNewSources()
		val order = settings.sourcesSortOrder
		return dao.findAll(!settings.isAllSourcesEnabled, order).toSources(settings.isNsfwContentDisabled, order)
			.let { enabled ->
				val external = getExternalSources()
				val list = ArrayList<MangaSourceInfo>(enabled.size + external.size)
				external.mapTo(list) { MangaSourceInfo(it, isEnabled = true, isPinned = true) }
				list.addAll(enabled)
				list
			}
	}

	suspend fun getPinnedSources(): Set<MangaSource> {
		assimilateNewSources()
		val skipNsfw = settings.isNsfwContentDisabled
		return dao.findAllPinned().mapNotNullToSet {
			it.source.toMangaSourceOrNull()?.takeUnless { x -> skipNsfw && x.isNsfw() }
		}
	}

	suspend fun getTopSources(limit: Int): List<MangaSource> {
		assimilateNewSources()
		return dao.findLastUsed(limit).toSources(settings.isNsfwContentDisabled, null)
	}

	suspend fun getDisabledSources(): Set<MangaSource> {
		assimilateNewSources()
		if (settings.isAllSourcesEnabled) {
			return emptySet()
		}
		val result = EnumSet.copyOf(allMangaSources)
		val enabled = dao.findAllEnabledNames()
		for (name in enabled) {
			val source = name.toMangaSourceOrNull() ?: continue
			result.remove(source)
		}
		return result
	}

	suspend fun queryParserSources(
		isDisabledOnly: Boolean,
		isNewOnly: Boolean,
		excludeBroken: Boolean,
		types: Set<ContentType>,
		query: String?,
		locale: String?,
		sortOrder: SourcesSortOrder?,
	): List<MangaParserSource> {
		assimilateNewSources()
		val entities = dao.findAll().toMutableList()
		if (isDisabledOnly && !settings.isAllSourcesEnabled) {
			entities.removeAll { it.isEnabled }
		}
		if (isNewOnly) {
			entities.retainAll { it.addedIn == BuildConfig.VERSION_CODE }
		}
		val sources = entities.toSources(
			skipNsfwSources = settings.isNsfwContentDisabled,
			sortOrder = sortOrder,
		).run {
			mapNotNullTo(ArrayList(size)) { it.mangaSource as? MangaParserSource }
		}
		if (locale != null) {
			sources.retainAll { it.locale == locale }
		}
		if (excludeBroken) {
			sources.removeAll { it.isBroken }
		}
		if (types.isNotEmpty()) {
			sources.retainAll { it.contentType in types }
		}
		if (!query.isNullOrEmpty()) {
			sources.retainAll {
				it.getTitle(context).contains(query, ignoreCase = true) || it.name.contains(query, ignoreCase = true)
			}
		}
		return sources
	}

	fun observeIsEnabled(source: MangaSource): Flow<Boolean> {
		return dao.observeIsEnabled(source.name).onStart { assimilateNewSources() }
	}

	fun observeEnabledSourcesCount(): Flow<Int> {
		return combine(
			observeIsNsfwDisabled(),
			observeAllEnabled().flatMapLatest { isAllSourcesEnabled ->
				dao.observeAll(!isAllSourcesEnabled, SourcesSortOrder.MANUAL)
			},
		) { skipNsfw, sources ->
			sources.count {
				it.source.toMangaSourceOrNull()?.let { s -> !skipNsfw || !s.isNsfw() } == true
			}
		}.distinctUntilChanged().onStart { assimilateNewSources() }
	}

	fun observeAvailableSourcesCount(): Flow<Int> {
		return combine(
			observeIsNsfwDisabled(),
			observeAllEnabled().flatMapLatest { isAllSourcesEnabled ->
				dao.observeAll(!isAllSourcesEnabled, SourcesSortOrder.MANUAL)
			},
		) { skipNsfw, enabledSources ->
			val enabled = enabledSources.mapToSet { it.source }
			allMangaSources.count { x ->
				x.name !in enabled && (!skipNsfw || !x.isNsfw())
			}
		}.distinctUntilChanged().onStart { assimilateNewSources() }
	}

	fun observeEnabledSources(): Flow<List<MangaSourceInfo>> = combine(
		observeIsNsfwDisabled(),
		observeAllEnabled(),
		observeSortOrder(),
	) { skipNsfw, allEnabled, order ->
		dao.observeAll(!allEnabled, order).map {
			it.toSources(skipNsfw, order)
		}
	}.flattenLatest()
		.onStart { assimilateNewSources() }
		.combine(observeExternalSources()) { enabled, external ->
			val list = ArrayList<MangaSourceInfo>(enabled.size + external.size)
			external.mapTo(list) { MangaSourceInfo(it, isEnabled = true, isPinned = true) }
			list.addAll(enabled)
			list
		}

	fun observeAll(): Flow<List<Pair<MangaSource, Boolean>>> = dao.observeAll().map { entities ->
		val result = ArrayList<Pair<MangaSource, Boolean>>(entities.size)
		for (entity in entities) {
			val source = entity.source.toMangaSourceOrNull() ?: continue
			if (source in allMangaSources) {
				result.add(source to entity.isEnabled)
			}
		}
		result
	}.onStart { assimilateNewSources() }

	suspend fun setSourcesEnabled(sources: Collection<MangaSource>, isEnabled: Boolean): ReversibleHandle {
		setSourcesEnabledImpl(sources, isEnabled)
		return ReversibleHandle {
			setSourcesEnabledImpl(sources, !isEnabled)
		}
	}

	suspend fun setSourcesEnabledExclusive(sources: Set<MangaSource>) {
		db.withTransaction {
			assimilateNewSources()
			for (s in allMangaSources) {
				dao.setEnabled(s.name, s in sources)
			}
		}
	}

	suspend fun disableAllSources() {
		db.withTransaction {
			assimilateNewSources()
			dao.disableAllSources()
		}
	}

	suspend fun setPositions(sources: List<MangaSource>) {
		db.withTransaction {
			for ((index, item) in sources.withIndex()) {
				dao.setSortKey(item.name, index)
			}
		}
	}

	fun observeHasNewSources(): Flow<Boolean> = observeIsNsfwDisabled().map { skipNsfw ->
		val sources = dao.findAllFromVersion(BuildConfig.VERSION_CODE).toSources(skipNsfw, null)
		sources.isNotEmpty() && sources.size != allMangaSources.size
	}.onStart { assimilateNewSources() }

	fun observeHasNewSourcesForBadge(): Flow<Boolean> = combine(
		settings.observeAsFlow(AppSettings.KEY_SOURCES_VERSION) { sourcesVersion },
		observeIsNsfwDisabled(),
	) { version, skipNsfw ->
		if (version < BuildConfig.VERSION_CODE) {
			val sources = dao.findAllFromVersion(version).toSources(skipNsfw, null)
			sources.isNotEmpty()
		} else {
			false
		}
	}.onStart { assimilateNewSources() }

	fun clearNewSourcesBadge() {
		settings.sourcesVersion = BuildConfig.VERSION_CODE
	}

	private suspend fun assimilateNewSources(): Boolean {
		if (isNewSourcesAssimilated.getAndSet(true)) {
			return false
		}
		val new = getNewSources()
		if (new.isEmpty()) {
			return false
		}
		var maxSortKey = dao.getMaxSortKey()
		val isAllEnabled = settings.isAllSourcesEnabled
		val entities = new.map { x ->
			MangaSourceEntity(
				source = x.name,
				isEnabled = isAllEnabled,
				sortKey = ++maxSortKey,
				addedIn = BuildConfig.VERSION_CODE,
				lastUsedAt = 0,
				isPinned = false,
				cfState = CloudFlareHelper.PROTECTION_NOT_DETECTED,
			)
		}
		dao.insertIfAbsent(entities)
		return true
	}

	suspend fun isSetupRequired(): Boolean {
		return settings.sourcesVersion == 0 && dao.findAllEnabledNames().isEmpty()
	}

	suspend fun setIsPinned(sources: Collection<MangaSource>, isPinned: Boolean): ReversibleHandle {
		setSourcesPinnedImpl(sources, isPinned)
		return ReversibleHandle {
			setSourcesEnabledImpl(sources, !isPinned)
		}
	}

	suspend fun trackUsage(source: MangaSource) {
		if (!settings.isIncognitoModeEnabled(source.isNsfw())) {
			dao.setLastUsed(source.name, System.currentTimeMillis())
		}
	}

	private suspend fun setSourcesEnabledImpl(sources: Collection<MangaSource>, isEnabled: Boolean) {
		if (sources.size == 1) { // fast path
			dao.setEnabled(sources.first().name, isEnabled)
			return
		}
		db.withTransaction {
			for (source in sources) {
				dao.setEnabled(source.name, isEnabled)
			}
		}
	}

	private suspend fun getNewSources(): MutableSet<out MangaSource> {
		val entities = dao.findAll()
		val result = EnumSet.copyOf(allMangaSources)
		for (e in entities) {
			result.remove(e.source.toMangaSourceOrNull() ?: continue)
		}
		return result
	}

	private suspend fun setSourcesPinnedImpl(sources: Collection<MangaSource>, isPinned: Boolean) {
		if (sources.size == 1) { // fast path
			dao.setPinned(sources.first().name, isPinned)
			return
		}
		db.withTransaction {
			for (source in sources) {
				dao.setPinned(source.name, isPinned)
			}
		}
	}

	private fun observeExternalSources(): Flow<List<ExternalMangaSource>> {
		return callbackFlow {
			val receiver = object : BroadcastReceiver() {
				override fun onReceive(context: Context?, intent: Intent?) {
					trySendBlocking(intent)
				}
			}
			ContextCompat.registerReceiver(
				context,
				receiver,
				IntentFilter().apply {
					addAction(Intent.ACTION_PACKAGE_ADDED)
					addAction(Intent.ACTION_PACKAGE_VERIFIED)
					addAction(Intent.ACTION_PACKAGE_REPLACED)
					addAction(Intent.ACTION_PACKAGE_REMOVED)
					addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
					addDataScheme("package")
				},
				ContextCompat.RECEIVER_EXPORTED,
			)
			awaitClose { context.unregisterReceiver(receiver) }
		}.onStart {
			emit(null)
		}.map {
			getExternalSources()
		}.distinctUntilChanged()
			.conflate()
	}

	fun getExternalSources(): List<ExternalMangaSource> = context.packageManager.queryIntentContentProviders(
		Intent("app.kotatsu.parser.PROVIDE_MANGA"), 0,
	).map { resolveInfo ->
		ExternalMangaSource(
			packageName = resolveInfo.providerInfo.packageName,
			authority = resolveInfo.providerInfo.authority,
		)
	}

	private fun List<MangaSourceEntity>.toSources(
		skipNsfwSources: Boolean,
		sortOrder: SourcesSortOrder?,
	): MutableList<MangaSourceInfo> {
		val isAllEnabled = settings.isAllSourcesEnabled
		val result = ArrayList<MangaSourceInfo>(size)
		for (entity in this) {
			val source = entity.source.toMangaSourceOrNull() ?: continue
			if (skipNsfwSources && source.isNsfw()) {
				continue
			}
			if (source in allMangaSources) {
				result.add(
					MangaSourceInfo(
						mangaSource = source,
						isEnabled = entity.isEnabled || isAllEnabled,
						isPinned = entity.isPinned,
					),
				)
			}
		}
		if (sortOrder == SourcesSortOrder.ALPHABETIC) {
			result.sortWith(compareBy<MangaSourceInfo> { !it.isPinned }.thenBy { it.getTitle(context) })
		}
		return result
	}

	private fun observeIsNsfwDisabled() = settings.observeAsFlow(AppSettings.KEY_DISABLE_NSFW) {
		isNsfwContentDisabled
	}

	private fun observeSortOrder() = settings.observeAsFlow(AppSettings.KEY_SOURCES_ORDER) {
		sourcesSortOrder
	}

	private fun observeAllEnabled() = settings.observeAsFlow(AppSettings.KEY_SOURCES_ENABLED_ALL) {
		isAllSourcesEnabled
	}

	private fun String.toMangaSourceOrNull(): MangaParserSource? = MangaParserSource.entries.find { it.name == this }

	companion object {

		/**
		 * Locales whose sources this build offers, as the parser library tags
		 * them — the third argument of its `@MangaSourceParser` annotation.
		 *
		 * Add a locale here to widen the catalogue; `null` would let through
		 * the sources that declare no language at all, which are mostly
		 * aggregators and adult sites rather than anything Indonesian.
		 *
		 * It must never select an empty set: several callers pass the result
		 * to `EnumSet.copyOf`, which cannot infer an element type from an
		 * empty collection and throws.
		 */
		private val ENABLED_LOCALES = setOf("id")

		/**
		 * Indonesian sources that are no longer the site the parser was written
		 * for, and cannot be fixed by fixing the parser.
		 *
		 * The parsers ship in the Maven dependency rather than here, and upstream
		 * has not marked any of these `@Broken`, so without this they appear in
		 * the catalogue and fail on every request.
		 *
		 * Deliberately narrow. A source answering 403, 429, 5xx or a timeout does
		 * **not** belong here — that is usually Cloudflare turning away a
		 * data-centre IP, and the same site is fine from a phone. Neither does a
		 * source whose site merely changed layout: that is a parser fix, made in
		 * the fork, not a source to take away from readers.
		 *
		 * Matched by name so a parser leaving the library upstream is a no-op
		 * here rather than a compile error.
		 */
		private val DEAD_SOURCES = setOf(
			// Domain no longer resolves at all — NXDOMAIN from both Cloudflare's
			// and Google's public resolvers, checked 2026-08-12.
			"BIRDTOON",      // birdtoon.shop
			"ICHIROMANGA",   // ichiromanga.my.id
			"KOMIKMAMA",     // komikmama.lat
			"MASTERKOMIK",   // tenshi01.id
			"MONZEEKOMIK",   // monzee01.my.id
			"NEUMANGA",      // neumanga.xyz
			"NIMEMOB",       // www.nimemob.my.id
			"NOROMAX",       // noromax01.my.id

			// Domain expired and was re-registered by somebody else. These resolve
			// and answer, which is exactly why they have to be named here: left in,
			// the app would send readers who tap a manga source to a stranger's
			// site. mangadop.net is the urgent one — it now serves a gambling
			// promotion. Checked 2026-08-13.
			"MANGADOP",      // mangadop.net — casino promo, Turkish
			"NGOMIK",        // ngomik.mom — unrelated Turkish site

			// Still standing, but no longer a place to read manga. Checked
			// 2026-08-14 by reading what each one actually serves now.
			"KATAKOMIK",     // katakomik.my.id — a blog reviewing comics, nothing to read
			"MANGAKITA",     // mangakita.id — now "Animeplus", an anime download site
			"SEKAIKOMIK",    // sekaikomik.mom — parking page, "This domain is for sale"
			"SIRENKOMIK",    // sirenkomik.xyz — "404 (002) pixie proxy", nothing behind it
		)

		/**
		 * Sources that now answer with a JavaScript wall instead of their content.
		 *
		 * These sites are alive and their parsers are fine. What comes back to a
		 * plain HTTP client is a stub — `<title>Loading...</title>`,
		 * `Redirecting...`, `Checking your browser...` — carrying a script that
		 * computes a token or a fingerprint and only then navigates to the real
		 * page. Parsers read HTML with Jsoup and never execute any of it, so the
		 * list is always empty.
		 *
		 * This is not the data-centre-IP problem: a phone's HTTP client executes
		 * no JavaScript either, so these fail there in exactly the same way. Every
		 * one below was seen serving its wall directly, not inferred from a
		 * pattern; the redirects were followed by hand and either bounced or
		 * landed on a parked search page.
		 *
		 * `evaluateJs` cannot rescue them: it evaluates a snippet and returns a
		 * string, and there is no primitive that loads a URL in a WebView and
		 * hands back the settled DOM. Making them work would mean re-implementing
		 * each site's bot-detection script, per site, and re-doing it whenever
		 * they change it.
		 *
		 * Kept separate from [DEAD_SOURCES] because the reason is different and
		 * reversible — if a site drops its wall, deleting a line here is the whole
		 * fix.
		 */
		private val JS_GATED_SOURCES = setOf(
			// Fingerprint script, then a redirect carrying tr_uuid and a computed
			// fp value. Following it without that value gets a 302 to nowhere.
			"COMICASO",        // comicaso.xyz
			"DOUJINDESURIP",   // doujindesu.asia
			"FUTARI",          // futari.info
			"MANHWALAND_INK",  // manhwaland.asia
			"MANHWALIST",      // manhwalist.xyz
			"TUKANGKOMIK",     // tukangkomik.co

			// "Loading..." plus a redirect signed with a JWT. komiksin.id was
			// followed all the way through and lands on a parked search page.
			"KOMIKGO",         // komikgo.xyz
			"KOMIKINDO",       // komiksin.id
			"LUMOSKOMIK",      // lumos01.com

			// "Redirecting..." / "Checking your browser..." behind obfuscated JS.
			"KOMIKDEWASA",     // komikremaja.icu
			"MANGAKYO",        // mangakyo.vip
			"MANHWADESU",      // manhwadesu.asia
			"MANHWAINDO",      // manhwaindo.one
			"POJOKMANGA",      // pojokmanga.info
		)

		/**
		 * Sources kept despite not being tagged Indonesian.
		 *
		 * [ENABLED_LOCALES] selects parsers by the locale in their
		 * `@MangaSourceParser` annotation, and a site that serves many languages
		 * is tagged with none of them — so the filter that keeps the catalogue
		 * Indonesian was also throwing away the largest Indonesian catalogues
		 * there are.
		 *
		 * MangaDex carries 6838 titles with Indonesian translations, more than
		 * the small scanlation sites put together, over a documented API with no
		 * bot wall in front of it — while a third of the sites in this list
		 * answer nothing but a Cloudflare challenge. Its parser exposes a locale
		 * filter, so a reader who wants only Indonesian can have that.
		 *
		 * Deliberately a short list. This is the exception to "Indonesian sources
		 * only", not the end of it, and a source belongs here only if its
		 * Indonesian catalogue is worth the English that comes with it.
		 */
		private val EXTRA_SOURCES = setOf(
			"MANGADEX",
		)
	}
}
