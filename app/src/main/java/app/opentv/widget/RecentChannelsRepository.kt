package app.opentv.widget

import app.opentv.core.AppSettings
import app.opentv.data.db.ChannelDao
import app.opentv.data.parser.ChannelNameNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** No playback URL, portal command, source credentials or programme metadata crosses this boundary. */
data class RecentChannelCandidate(
    val id: Long,
    val displayName: String,
    val customName: String?,
    val groupKey: String,
    val qualityRank: Int,
    val categoryId: String?,
    val logoUrl: String?,
    val categoryName: String?,
) {
    val name: String get() = customName?.takeIf { it.isNotBlank() } ?: displayName
}

internal fun RecentChannelCandidate.isProtected(keys: Set<String>): Boolean {
    if (keys.isEmpty() || categoryId == null) return false
    // A dangling category cannot be proven safe while parental restrictions exist.
    val category = categoryName ?: return true
    val normalized = ChannelNameNormalizer.normalize(category)
    val legacy = normalized.groupKey.ifEmpty { categoryId }
    val key = normalized.region?.uppercase()?.let { "$it:$legacy" } ?: legacy
    return key in keys || legacy in keys
}

/** Match Live's grouping: first logical group retains its MRU position, best recent variant wins. */
internal fun selectRecentChannels(
    ids: List<Long>,
    candidates: List<RecentChannelCandidate>,
    protectedKeys: Set<String>,
    limit: Int,
): List<RecentChannelCandidate> {
    val byId = candidates.associateBy { it.id }
    val groups = LinkedHashMap<Any, RecentChannelCandidate>()
    for (id in ids.distinct()) {
        val candidate = byId[id] ?: continue
        if (candidate.isProtected(protectedKeys)) continue
        val key: Any = candidate.groupKey.ifEmpty { candidate.id }
        val previous = groups[key]
        if (previous == null || candidate.qualityRank > previous.qualityRank) groups[key] = candidate
    }
    return groups.values.take(limit.coerceIn(0, 6))
}

class RecentChannelsRepository(
    private val candidates: suspend (List<Long>) -> List<RecentChannelCandidate>,
) {
    constructor(dao: ChannelDao) : this(dao::widgetCandidates)

    suspend fun recent(ids: List<Long>, protectedKeys: Set<String>, liveEnabled: Boolean): List<RecentChannelCandidate> =
        withContext(Dispatchers.IO) {
            if (!liveEnabled || ids.isEmpty()) emptyList()
            else {
                // AppSettings retains twenty IDs; also bound callers to keep well below SQLite's
                // parameter limit if malformed/older preferences ever contain a longer history.
                val recent = ids.take(20)
                selectRecentChannels(recent, candidates(recent), protectedKeys, 6)
            }
        }

    /** Re-read at tap/confirmation time; session unlock never relaxes external-launch privacy. */
    suspend fun playable(id: Long, settings: AppSettings): RecentChannelCandidate? = withContext(Dispatchers.IO) {
        if (id <= 0 || !settings.liveEnabled.value) return@withContext null
        candidates(listOf(id)).singleOrNull()?.takeUnless { it.isProtected(settings.hiddenCategories.value) }
    }
}
