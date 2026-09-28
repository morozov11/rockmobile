package com.rockmobile.data.stations

import android.content.Context
import androidx.room.Database
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.rockmobile.domain.model.CatalogueSource
import com.rockmobile.domain.model.Station
import java.security.MessageDigest

private const val EXTENDED_ASSET = "rockmobile-extended-2026.08.2-mobile.1.sqlite"
private const val EXTENDED_VERSION = "2026.08.2-mobile.1"
private const val EXTENDED_SCHEMA = 1
private const val EXTENDED_COUNT = 16_825
private const val EXTENDED_SHA256 = "ad469d405f177d7e476cf9b3d9985497d0e2c6132ac0f3ce14485f4eab402073"

/** A Room-backed, prebuilt catalog. It never changes the asset or contacts a server. */
class ExtendedCatalogStationSource(private val context: Context) : LocalStationSource {
    override val catalogueSource = CatalogueSource.EXTENDED
    private val database by lazy {
        verifyAsset()
        Room.databaseBuilder(context.applicationContext, ExtendedCatalogDatabase::class.java, "rockmobile-$EXTENDED_VERSION.db")
            .createFromAsset(EXTENDED_ASSET)
            .build()
            .also(::verifyDatabase)
    }

    /**
     * Room-free point lookups by station id. Room's pre-packaged identity check rejects this
     * asset's schema, so catalog fallback and this lookup must not depend on the Room handle;
     * a read-only SQLite connection over the verified copy serves both names and streams.
     */
    suspend fun stationsById(ids: Collection<String>): Map<String, Station> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyMap()
        try {
            ensureDatabaseFile()
            val found = LinkedHashMap<String, Station>()
            android.database.sqlite.SQLiteDatabase.openDatabase(databaseFile().path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { db ->
                for (id in ids) {
                    db.rawQuery(
                        "SELECT name, tags_json, country_code, language, homepage_url, favicon_url, stream_url, codec, bitrate_kbps FROM stations WHERE station_id = ?",
                        arrayOf(id),
                    ).use { cursor ->
                        if (cursor.moveToFirst()) {
                            found[id] = Station(
                                id = id,
                                name = cursor.getString(0),
                                streamUrl = cursor.getString(6),
                                tags = org.json.JSONArray(cursor.getString(1)).let { array -> List(array.length()) { array.getString(it) } },
                                country = cursor.getString(2),
                                language = cursor.getString(3),
                                codec = cursor.getString(7),
                                bitrateKbps = cursor.getInt(8).takeIf { !cursor.isNull(8) },
                                homepageUrl = cursor.getString(4),
                                faviconUrl = cursor.getString(5),
                            )
                        }
                    }
                }
                val missing = ids.toSet() - found.keys
                val hashQueryNeeded = missing.filter { it.startsWith("legacy-") || it.startsWith("radio-browser-") }
                if (hashQueryNeeded.isNotEmpty()) {
                    val remaining = hashQueryNeeded.toMutableSet()
                    db.rawQuery(
                        "SELECT name, tags_json, country_code, language, homepage_url, favicon_url, stream_url, codec, bitrate_kbps FROM stations",
                        null,
                    ).use { cursor ->
                        val md = MessageDigest.getInstance("SHA-256")
                        while (cursor.moveToNext() && remaining.isNotEmpty()) {
                            val streamUrl = cursor.getString(6) ?: continue
                            val urlBytes = streamUrl.toByteArray(Charsets.UTF_8)
                            val shaHex = md.digest(urlBytes).take(8).joinToString("") { "%02x".format(it) }
                            val legacyId = "legacy-$shaHex"
                            if (legacyId in remaining) {
                                found[legacyId] = Station(
                                    id = legacyId,
                                    name = cursor.getString(0),
                                    streamUrl = streamUrl,
                                    tags = org.json.JSONArray(cursor.getString(1)).let { array -> List(array.length()) { array.getString(it) } },
                                    country = cursor.getString(2),
                                    language = cursor.getString(3),
                                    codec = cursor.getString(7),
                                    bitrateKbps = cursor.getInt(8).takeIf { !cursor.isNull(8) },
                                    homepageUrl = cursor.getString(4),
                                    faviconUrl = cursor.getString(5),
                                )
                                remaining.remove(legacyId)
                            }
                            var h = 0L
                            for (b in urlBytes) {
                                h = h * 109L + (b.toLong() and 0xFFL)
                            }
                            val rbId = "radio-browser-" + java.lang.Long.toUnsignedString(h)
                            if (rbId in remaining) {
                                found[rbId] = Station(
                                    id = rbId,
                                    name = cursor.getString(0),
                                    streamUrl = streamUrl,
                                    tags = org.json.JSONArray(cursor.getString(1)).let { array -> List(array.length()) { array.getString(it) } },
                                    country = cursor.getString(2),
                                    language = cursor.getString(3),
                                    codec = cursor.getString(7),
                                    bitrateKbps = cursor.getInt(8).takeIf { !cursor.isNull(8) },
                                    homepageUrl = cursor.getString(4),
                                    faviconUrl = cursor.getString(5),
                                )
                                remaining.remove(rbId)
                            }
                        }
                    }
                }
            }
            found
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun databaseFile() = context.getDatabasePath("rockmobile-$EXTENDED_VERSION.db")

    private fun ensureDatabaseFile() {
        val target = databaseFile()
        if (target.exists()) return
        target.parentFile?.mkdirs()
        context.assets.open(EXTENDED_ASSET).use { input -> target.outputStream().use { output -> input.copyTo(output) } }
    }

    override suspend fun load(): List<Station> = database.catalogue().initialStations(INITIAL_PAGE_SIZE).map(ExtendedStation::toStation)

    override suspend fun station(id: String): Station? = stationsById(listOf(id))[id] ?: database.catalogue().station(id)?.toStation()

    override suspend fun search(query: String, genre: String?, country: String?, language: String?): List<Station> {
        val terms = searchTerms(query, genre)
        val rows = if (terms.isEmpty() && country.isNullOrBlank() && language.isNullOrBlank()) database.catalogue().initialStations(INITIAL_PAGE_SIZE)
        else if (terms.isEmpty()) database.catalogue().filteredStations(country?.trim()?.uppercase(), language?.trim()?.lowercase(), INITIAL_PAGE_SIZE)
        else database.catalogue().search(searchQuery(terms, country, language))
        return rows.map(ExtendedStation::toStation)
    }

    private fun verifyAsset() {
        val digest = context.assets.open(EXTENDED_ASSET).use { stream ->
            val messageDigest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                messageDigest.update(buffer, 0, count)
            }
            messageDigest.digest().joinToString("") { "%02x".format(it) }
        }
        require(digest == EXTENDED_SHA256) { "Extended catalog checksum does not match pinned release" }
    }

    private fun verifyDatabase(database: ExtendedCatalogDatabase) {
        val sqlite = database.openHelper.readableDatabase
        val metadata = sqlite.query("SELECT catalog_version, schema_version, station_count FROM catalog_metadata LIMIT 1").use { cursor ->
            require(cursor.moveToFirst()) { "Extended catalog has no metadata" }
            CatalogMetadata(cursor.getString(0), cursor.getInt(1), cursor.getInt(2))
        }
        require(metadata.catalogVersion == EXTENDED_VERSION && metadata.schemaVersion == EXTENDED_SCHEMA && metadata.stationCount == EXTENDED_COUNT) {
            "Extended catalog metadata does not match pinned release"
        }
        val userVersion = sqlite.query("PRAGMA user_version").use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }
        val integrity = sqlite.query("PRAGMA integrity_check").use { cursor -> cursor.moveToFirst(); cursor.getString(0) }
        require(userVersion == EXTENDED_SCHEMA && integrity == "ok") {
            "Extended catalog database integrity check failed"
        }
        require(database.catalogue().stationCount() == EXTENDED_COUNT) { "Extended catalog station count is invalid" }
    }

    private companion object { const val INITIAL_PAGE_SIZE = 200 }
}

data class CatalogMetadata(
    val catalogVersion: String,
    val schemaVersion: Int,
    val stationCount: Int,
)

@Entity(tableName = "stations")
data class ExtendedStation(
    @PrimaryKey @ColumnInfo(name = "station_id") val stationId: String,
    val source: String,
    @ColumnInfo(name = "source_station_id") val sourceStationId: String,
    val name: String,
    @ColumnInfo(name = "normalized_name") val normalizedName: String,
    @ColumnInfo(name = "tags_json") val tagsJson: String,
    @ColumnInfo(name = "normalized_tags") val normalizedTags: String,
    @ColumnInfo(name = "country_code") val countryCode: String?,
    val language: String?,
    @ColumnInfo(name = "homepage_url") val homepageUrl: String?,
    @ColumnInfo(name = "favicon_url") val faviconUrl: String?,
    @ColumnInfo(name = "stream_url") val streamUrl: String,
    val codec: String?,
    @ColumnInfo(name = "bitrate_kbps") val bitrateKbps: Int?,
) {
    fun toStation() = Station(
        id = stationId, name = name, streamUrl = streamUrl,
        tags = org.json.JSONArray(tagsJson).let { array -> List(array.length()) { array.getString(it) } },
        country = countryCode, language = language, codec = codec, bitrateKbps = bitrateKbps,
        homepageUrl = homepageUrl, faviconUrl = faviconUrl,
    )
}

@Dao
interface ExtendedCatalogDao {
    @Query("SELECT COUNT(*) FROM stations") fun stationCount(): Int
    @Query("SELECT * FROM stations ORDER BY normalized_name, station_id LIMIT :limit") fun initialStations(limit: Int): List<ExtendedStation>
    @Query("SELECT * FROM stations WHERE (:country IS NULL OR country_code = :country) AND (:language IS NULL OR language = :language) ORDER BY normalized_name, station_id LIMIT :limit")
    fun filteredStations(country: String?, language: String?, limit: Int): List<ExtendedStation>
    @Query("SELECT * FROM stations WHERE station_id = :stationId") fun station(stationId: String): ExtendedStation?
    @RawQuery(observedEntities = [ExtendedStation::class])
    fun search(query: SupportSQLiteQuery): List<ExtendedStation>
}

@Database(entities = [ExtendedStation::class], version = EXTENDED_SCHEMA, exportSchema = false)
abstract class ExtendedCatalogDatabase : RoomDatabase() { abstract fun catalogue(): ExtendedCatalogDao }

private fun searchTerms(query: String, genre: String?): List<String> =
    listOf(query, genre.orEmpty()).flatMap { value ->
        value.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    }
private fun searchQuery(terms: List<String>, country: String?, language: String?): SupportSQLiteQuery {
    // Quoted terms and a suffix wildcard keep UI text data-only while using the prebuilt FTS5 index.
    val fts = terms.joinToString(" AND ") { "\"${it.replace("\"", "\"\"")}\"*" }.ifBlank { "*" }
    val arguments = mutableListOf<Any>(fts)
    val filters = buildString {
        if (!country.isNullOrBlank()) { append(" AND s.country_code = ?"); arguments += country.trim().uppercase() }
        if (!language.isNullOrBlank()) { append(" AND s.language = ?"); arguments += language.trim().lowercase() }
    }
    return SimpleSQLiteQuery("SELECT s.* FROM station_search JOIN stations s USING (station_id) WHERE station_search MATCH ?$filters ORDER BY bm25(station_search), s.station_id LIMIT 200", arguments.toTypedArray())
}
