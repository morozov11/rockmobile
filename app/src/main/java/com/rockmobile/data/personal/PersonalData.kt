package com.rockmobile.data.personal

import android.content.Context
import com.rockmobile.domain.model.Station
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

private const val MAX_FAVOURITES = 500
private const val MAX_HISTORY = 500
private const val HISTORY_WINDOW_MS = 300_000L
private const val HISTORY_RETENTION_MS = 90L * 24 * 60 * 60 * 1000
private const val PROFILE = "profile"
private const val BACKUP = "profile.pre-migration"
private const val JOURNAL = "profile.migration-journal"

data class Favourite(val recordId: String, val stationId: String, val addedAt: String, val updatedAt: String, val lastKnownName: String?, val catalogVersion: String? = null)
data class HistoryEntry(val recordId: String, val stationId: String, val startedAt: String, val lastPlayedAt: String, val endedAt: String?, val playDurationMs: Long?, val lastKnownName: String?, val catalogVersion: String? = null, val source: String?)
data class UnresolvedReference(val referenceId: String, val sourceKind: String, val originalStationId: String, val firstSeenAt: String, val reason: String, val candidates: List<String> = emptyList(), val lastKnownName: String? = null, val catalogVersion: String? = null)
data class PersonalData(
    val schemaVersion: Int = 1,
    val profileId: String = UUID.randomUUID().toString(),
    val createdAt: String = clock(),
    val updatedAt: String = createdAt,
    val favourites: List<Favourite> = emptyList(),
    val history: List<HistoryEntry> = emptyList(),
    val unresolved: List<UnresolvedReference> = emptyList(),
    val lastPlayedStationId: String? = null,
    val launchCount: Int = 0,
)

/** Complete, accepted local identity and lifecycle evidence used without networking. */
data class LocalCatalogIndex(
    val activeStationIds: Set<String>,
    val legacyIds: Map<String, String> = emptyMap(),
    val merged: Map<String, String> = emptyMap(),
    val splits: Map<String, List<String>> = emptyMap(),
    val removed: Set<String> = emptySet(),
    val catalogVersion: String? = null,
)

internal fun resolvePersonalData(data: PersonalData, index: LocalCatalogIndex, now: Instant): PersonalData {
    val unresolved = data.unresolved.toMutableList()
    fun resolve(id: String, kind: String, name: String?, seenAt: String): String? {
        if (!validId(id)) {
            unresolved += unresolved(kind, id, seenAt, "legacy-unmapped", emptyList(), name, index.catalogVersion)
            return null
        }
        if (id in index.activeStationIds) return id
        index.legacyIds[id]?.takeIf(index.activeStationIds::contains)?.let { return it }
        var current = id
        val seen = mutableSetOf<String>()
        while (current in index.merged && seen.add(current)) current = index.merged.getValue(current)
        if (current in index.activeStationIds) return current
        val candidates = index.splits[current]
        val reason = when {
            candidates != null -> "split"
            current in index.removed -> "removed"
            id.startsWith("legacy-") || id.startsWith("rockserver-") -> "legacy-unmapped"
            else -> "missing"
        }
        unresolved += unresolved(kind, id, seenAt, reason, candidates.orEmpty(), name, index.catalogVersion)
        return null
    }
    val favourites = data.favourites.mapNotNull { f -> resolve(f.stationId, "favourite", f.lastKnownName, f.addedAt)?.let { f.copy(stationId = it) } }
        .groupBy { it.stationId }.values.map(::mergeFavourites)
        .sortedWith(compareBy<Favourite> { instant(it.addedAt) }.thenBy { it.stationId }).take(MAX_FAVOURITES)
    val history = data.history.mapNotNull { h -> resolve(h.stationId, "history", h.lastKnownName, h.startedAt)?.let { h.copy(stationId = it) } }
        .filter { instant(it.lastPlayedAt).toEpochMilli() >= now.toEpochMilli() - HISTORY_RETENTION_MS }
        .sortedWith(compareByDescending<HistoryEntry> { instant(it.lastPlayedAt) }.thenByDescending { instant(it.startedAt) }.thenBy { it.recordId }).take(MAX_HISTORY)
    return data.copy(favourites = favourites, history = history, unresolved = unresolved.distinctBy { listOf(it.sourceKind, it.originalStationId, it.reason) }, lastPlayedStationId = data.lastPlayedStationId?.let { resolve(it, "history", null, data.updatedAt) }, updatedAt = now.toString())
}

/** Portable, offline profile store with fail-closed reads and rollback-safe legacy migration. */
class PersonalDataStore(context: Context) {
    private val prefs = context.getSharedPreferences("rockmobile_personal_data", Context.MODE_PRIVATE)
    private val loaded = read(prefs.getString(PROFILE, null))
    private var writable = loaded.error == null
    private val _state = MutableStateFlow(loaded.data)
    val state: StateFlow<PersonalData> = _state.asStateFlow()

    init {
        if (writable && loaded.legacyRaw != null) migrate(loaded.legacyRaw, loaded.data)
        else if (writable) update(loaded.data.copy(launchCount = loaded.data.launchCount + 1))
        if (writable && loaded.legacyRaw == null && prefs.contains(JOURNAL)) check(prefs.edit().remove(BACKUP).remove(JOURNAL).commit())
    }

    fun reconcile(index: LocalCatalogIndex) = update(resolvePersonalData(_state.value, index, Instant.now()))
    fun toggleFavourite(station: Station): Boolean {
        requireWritable(); require(validId(station.id))
        val value = _state.value
        value.favourites.firstOrNull { it.stationId == station.id }?.let { update(value.copy(favourites = value.favourites - it)); return false }
        check(value.favourites.size < MAX_FAVOURITES)
        val now = clock(); update(value.copy(favourites = value.favourites + Favourite(UUID.randomUUID().toString(), station.id, now, now, station.name))); return true
    }
    fun recordPlay(station: Station, source: String) {
        requireWritable(); require(validId(station.id))
        val now = Instant.now(); val value = _state.value
        val latest = value.history.filter { it.stationId == station.id }.maxByOrNull { instant(it.lastPlayedAt) }
        val history = if (latest != null && now.toEpochMilli() - instant(latest.lastPlayedAt).toEpochMilli() <= HISTORY_WINDOW_MS) value.history.map {
            if (it == latest) it.copy(lastPlayedAt = now.toString(), endedAt = now.toString(), playDurationMs = (now.toEpochMilli() - instant(it.startedAt).toEpochMilli()).coerceAtLeast(0), lastKnownName = station.name) else it
        } else value.history + HistoryEntry(UUID.randomUUID().toString(), station.id, now.toString(), now.toString(), now.toString(), 0, station.name, source = source)
        update(value.copy(history = history, lastPlayedStationId = station.id))
    }
    fun rollbackMigration(): Boolean {
        requireWritable(); val raw = prefs.getString(BACKUP, null) ?: return false
        val restored = read(raw); check(restored.error == null)
        check(prefs.edit().putString(PROFILE, raw).remove(BACKUP).remove(JOURNAL).commit())
        _state.value = restored.data; return true
    }
    private fun update(value: PersonalData) {
        requireWritable(); val now = Instant.now()
        val retained = value.copy(updatedAt = now.toString(), favourites = value.favourites.sortedWith(compareBy<Favourite> { instant(it.addedAt) }.thenBy { it.stationId }).take(MAX_FAVOURITES), history = value.history.filter { instant(it.lastPlayedAt).toEpochMilli() >= now.toEpochMilli() - HISTORY_RETENTION_MS }.sortedWith(compareByDescending<HistoryEntry> { instant(it.lastPlayedAt) }.thenByDescending { instant(it.startedAt) }.thenBy { it.recordId }).take(MAX_HISTORY))
        check(prefs.edit().putString(PROFILE, encode(retained)).commit()) { "Personal profile write failed" }; _state.value = retained
    }
    private fun migrate(raw: String, value: PersonalData) {
        val journal = JSONObject().put("sourceSchemaVersion", 1).put("targetSchemaVersion", 1).put("timestamp", clock()).put("favourites", value.favourites.size).put("history", value.history.size).put("unresolved", value.unresolved.size).toString()
        check(prefs.edit().putString(BACKUP, raw).putString(PROFILE, encode(value)).putString(JOURNAL, journal).commit()) { "Personal profile migration failed" }; _state.value = value
    }
    private fun requireWritable() = check(writable) { "Personal profile is unreadable or has an unsupported schema" }
}

private data class Loaded(val data: PersonalData, val legacyRaw: String? = null, val error: Throwable? = null)
private fun read(raw: String?): Loaded = try {
    if (raw == null) Loaded(PersonalData()) else JSONObject(raw).let { root ->
        require(root.optInt("schemaVersion", -1) == 1)
        if (root.has("profileId")) Loaded(decode(root)) else Loaded(decodeLegacy(root), raw)
    }
} catch (error: Throwable) { Loaded(PersonalData(), error = error) }

private fun encode(data: PersonalData) = JSONObject().apply {
    put("schemaVersion", 1); put("profileId", data.profileId); put("createdAt", data.createdAt); put("updatedAt", data.updatedAt)
    put("favourites", JSONArray(data.favourites.map { JSONObject().put("recordId", it.recordId).put("stationId", it.stationId).put("addedAt", it.addedAt).put("updatedAt", it.updatedAt).put("metadata", metadata(it.lastKnownName, it.catalogVersion)) }))
    put("playbackHistory", JSONArray(data.history.map { JSONObject().put("recordId", it.recordId).put("stationId", it.stationId).put("startedAt", it.startedAt).put("lastPlayedAt", it.lastPlayedAt).put("endedAt", it.endedAt).put("playDurationMs", it.playDurationMs).put("metadata", metadata(it.lastKnownName, it.catalogVersion).put("source", it.source)) }))
    put("unresolvedReferences", JSONArray(data.unresolved.map { JSONObject().put("referenceId", it.referenceId).put("sourceKind", it.sourceKind).put("originalStationId", it.originalStationId).put("firstSeenAt", it.firstSeenAt).put("reason", it.reason).put("candidateStationIds", JSONArray(it.candidates)).put("lastKnownName", it.lastKnownName).put("catalogVersion", it.catalogVersion) }))
    put("metadata", JSONObject().put("lastPlayedStationId", data.lastPlayedStationId).put("launchCount", data.launchCount))
}.toString()

private fun decode(root: JSONObject): PersonalData {
    fun array(key: String) = root.optJSONArray(key) ?: JSONArray()
    val favourites = List(array("favourites").length()) { i -> array("favourites").getJSONObject(i).let { val m = it.optJSONObject("metadata") ?: JSONObject(); Favourite(uuid(it.getString("recordId")), canonical(it.getString("stationId")), timestamp(it.getString("addedAt")), timestamp(it.getString("updatedAt")), m.text("lastKnownName"), m.text("catalogVersion")) } }
    val history = List(array("playbackHistory").length()) { i -> array("playbackHistory").getJSONObject(i).let { val m = it.optJSONObject("metadata") ?: JSONObject(); HistoryEntry(uuid(it.getString("recordId")), canonical(it.getString("stationId")), timestamp(it.getString("startedAt")), timestamp(it.getString("lastPlayedAt")), it.text("endedAt")?.let(::timestamp), if (it.has("playDurationMs") && !it.isNull("playDurationMs")) it.getLong("playDurationMs") else null, m.text("lastKnownName"), m.text("catalogVersion"), m.text("source")) } }
    val unresolved = List(array("unresolvedReferences").length()) { i -> array("unresolvedReferences").getJSONObject(i).let { item -> UnresolvedReference(uuid(item.getString("referenceId")), item.getString("sourceKind"), item.getString("originalStationId"), timestamp(item.getString("firstSeenAt")), item.getString("reason"), item.optJSONArray("candidateStationIds")?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty(), item.text("lastKnownName"), item.text("catalogVersion")) } }
    val m = root.optJSONObject("metadata") ?: JSONObject()
    return PersonalData(1, uuid(root.getString("profileId")), timestamp(root.getString("createdAt")), timestamp(root.getString("updatedAt")), favourites, history, unresolved, m.text("lastPlayedStationId"), m.optInt("launchCount"))
}

private fun decodeLegacy(root: JSONObject): PersonalData {
    fun time(v: Long) = Instant.ofEpochMilli(v).toString()
    fun array(k: String) = root.optJSONArray(k) ?: JSONArray()
    val favourites = List(array("favourites").length()) { i -> array("favourites").getJSONObject(i).let { Favourite(it.getString("recordId"), canonical(it.getString("stationId")), time(it.getLong("addedAt")), time(it.getLong("updatedAt")), it.text("name")) } }
    val history = List(array("history").length()) { i -> array("history").getJSONObject(i).let { HistoryEntry(it.getString("recordId"), canonical(it.getString("stationId")), time(it.getLong("startedAt")), time(it.getLong("lastPlayedAt")), if (it.isNull("endedAt")) null else time(it.getLong("endedAt")), it.optLong("duration"), it.text("name"), source = it.text("source")) } }
    val unresolved = List(array("unresolved").length()) { i -> array("unresolved").getJSONObject(i).let { UnresolvedReference(it.getString("referenceId"), it.getString("sourceKind"), it.getString("originalStationId"), time(it.getLong("firstSeenAt")), it.getString("reason"), it.optJSONArray("candidates")?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty(), it.text("name")) } }
    return PersonalData(createdAt = (favourites.map { instant(it.addedAt) } + history.map { instant(it.startedAt) }).minOrNull()?.toString() ?: clock(), favourites = favourites, history = history, unresolved = unresolved, lastPlayedStationId = root.text("lastPlayedStationId"), launchCount = root.optInt("launchCount"))
}

private fun mergeFavourites(values: List<Favourite>): Favourite { val first = values.minWith(compareBy<Favourite> { instant(it.addedAt) }.thenBy { it.recordId }); val last = values.maxWith(compareBy<Favourite> { instant(it.updatedAt) }.thenBy { it.recordId }); return first.copy(updatedAt = last.updatedAt, lastKnownName = last.lastKnownName ?: first.lastKnownName, catalogVersion = last.catalogVersion ?: first.catalogVersion) }
private fun unresolved(kind: String, id: String, at: String, reason: String, candidates: List<String>, name: String?, version: String?) = UnresolvedReference(UUID.randomUUID().toString(), kind, id, at, reason, candidates, name, version)
private fun metadata(name: String?, version: String?) = JSONObject().put("lastKnownName", name).put("catalogVersion", version)
private fun JSONObject.text(key: String) = if (has(key) && !isNull(key)) optString(key).takeIf(String::isNotBlank) else null
private fun uuid(value: String) = value.also { UUID.fromString(it) }
private fun canonical(value: String) = value.also { require(validId(it)) }
private fun validId(value: String) = value.matches(Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")) || value.matches(Regex("^[a-z][a-z0-9-]{1,31}:[A-Za-z0-9][A-Za-z0-9._:-]{0,190}$"))
private fun timestamp(value: String) = value.also { instant(it) }
private fun instant(value: String) = Instant.parse(value)
private fun clock() = Instant.now().toString()
