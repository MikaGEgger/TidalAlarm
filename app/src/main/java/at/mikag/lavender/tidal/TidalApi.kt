package at.mikag.lavender.tidal

import at.mikag.lavender.AppLog
import at.mikag.lavender.data.TidalItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale

/** The few TIDAL API (openapi.tidal.com/v2, JSON:API format) calls Lavender needs: your playlists and favorite tracks. */
class TidalApi(private val http: OkHttpClient, private val auth: TidalAuth) {
    private val json = Json { ignoreUnknownKeys = true }

    data class Page(val items: List<TidalItem>, val nextCursor: String?)

    class ApiException(val code: Int, message: String) : Exception(message)

    suspend fun playlists(cursor: String?): Page {
        val root = getWithFallback(
            "userCollectionPlaylists/me/relationships/items",
            listOf(listOf("items", "items.coverArt"), listOf("items")),
            cursor,
            mapOf("collectionView" to "FLAT"),
        )
        return parse(root) { resource, included ->
            val attributes = resource["attributes"]?.jsonObject ?: return@parse null
            val name = attributes.string("name") ?: return@parse null
            val count = attributes["numberOfItems"]?.jsonPrimitive?.intOrNull
            TidalItem(
                type = TidalItem.Type.PLAYLIST,
                id = resource.string("id") ?: return@parse null,
                title = name,
                subtitle = count?.toString(),
                imageUrl = artwork(resource, "coverArt", included),
            )
        }
    }

    suspend fun tracks(cursor: String?): Page {
        val root = getWithFallback(
            "userCollectionTracks/me/relationships/items",
            listOf(listOf("items", "items.artists", "items.albums.coverArt"), listOf("items", "items.artists"), listOf("items")),
            cursor,
            mapOf("locale" to Locale.getDefault().toLanguageTag()),
        )
        return parse(root) { resource, included ->
            val attributes = resource["attributes"]?.jsonObject ?: return@parse null
            val title = attributes.string("title") ?: return@parse null
            val version = attributes.string("version")?.takeIf { it.isNotBlank() }
            val artists = related(resource, "artists", included).mapNotNull { it["attributes"]?.jsonObject?.string("name") }
            val album = related(resource, "albums", included).firstOrNull()
            TidalItem(
                type = TidalItem.Type.TRACK,
                id = resource.string("id") ?: return@parse null,
                title = if (version != null) "$title ($version)" else title,
                subtitle = artists.joinToString(", ").ifBlank { null },
                imageUrl = album?.let { artwork(it, "coverArt", included) },
            )
        }
    }

    /** Looks up a pasted/shared link, so the alarm can show its name. */
    suspend fun lookup(type: TidalItem.Type, id: String): TidalItem? {
        val path = if (type == TidalItem.Type.PLAYLIST) "playlists/$id" else "tracks/$id"
        val include = if (type == TidalItem.Type.PLAYLIST) listOf("coverArt") else listOf("artists", "albums.coverArt")
        val root = try {
            getWithFallback(path, listOf(include, emptyList()), null, emptyMap())
        } catch (e: ApiException) {
            if (e.code == 404) return null else throw e
        }
        val resource = root["data"]?.jsonObject ?: return null
        val included = includedMap(root)
        val attributes = resource["attributes"]?.jsonObject ?: return null
        return if (type == TidalItem.Type.PLAYLIST) {
            TidalItem(type, id, attributes.string("name") ?: id, null, artwork(resource, "coverArt", included))
        } else {
            val artists = related(resource, "artists", included).mapNotNull { it["attributes"]?.jsonObject?.string("name") }
            val album = related(resource, "albums", included).firstOrNull()
            TidalItem(type, id, attributes.string("title") ?: id, artists.joinToString(", ").ifBlank { null }, album?.let { artwork(it, "coverArt", included) })
        }
    }

    /** Tries the richest "include" first; if TIDAL rejects it (400), retries with less. */
    private suspend fun getWithFallback(
        path: String,
        includes: List<List<String>>,
        cursor: String?,
        params: Map<String, String>,
    ): JsonObject {
        var last: ApiException? = null
        for (include in includes) {
            try {
                return get(path, include, cursor, params)
            } catch (e: ApiException) {
                if (e.code != 400) throw e
                last = e
            }
        }
        throw last ?: ApiException(400, "Bad request")
    }

    private suspend fun get(path: String, include: List<String>, cursor: String?, params: Map<String, String>): JsonObject =
        call("GET", path, include, cursor, params, body = null)

    private suspend fun call(
        method: String,
        path: String,
        include: List<String> = emptyList(),
        cursor: String? = null,
        params: Map<String, String> = emptyMap(),
        body: String? = null,
    ): JsonObject {
        val url: HttpUrl = "$BASE/$path".toHttpUrl().newBuilder().apply {
            include.forEach { addQueryParameter("include", it) }
            params.forEach { (k, v) -> addQueryParameter(k, v) }
            cursor?.let { addQueryParameter("page[cursor]", it) }
        }.build()
        return withContext(Dispatchers.IO) {
            var token = auth.accessToken() ?: throw ApiException(401, "Not logged in")
            repeat(2) { attempt ->
                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "application/vnd.api+json")
                    .method(method, body?.toRequestBody(JSON_API))
                    .build()
                http.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (response.code == 401 && attempt == 0) {
                        token = auth.accessToken(forceRefresh = true) ?: throw ApiException(401, "Not logged in")
                        return@use
                    }
                    if (!response.isSuccessful) {
                        AppLog.w("Tidal", "API $method $path -> ${response.code}: ${text.take(300)}")
                        throw ApiException(response.code, "TIDAL: ${response.code} ${text.take(300)}")
                    }
                    // Changes often answer 204 without a body.
                    return@withContext if (text.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(text).jsonObject
                }
            }
            throw ApiException(401, "Not logged in")
        }
    }

    // ---------- Wake-up playlists: reading track lists and writing Lavender's own playlist ----------

    /** One entry of a track list. [itemId] identifies the entry inside a playlist (needed to remove it). */
    data class ItemRef(val type: String, val id: String, val itemId: String?)

    /** Track ids of a playlist, in playlist order (videos skipped). */
    suspend fun playlistTrackIds(playlistId: String, max: Int = MAX_SOURCE_TRACKS): List<String> =
        collect("playlists/$playlistId/relationships/items", max).filter { it.type == "tracks" }.map { it.id }

    /** The user's liked tracks ("My Collection → Tracks"), newest first like in the TIDAL app. */
    suspend fun likedTrackIds(max: Int = MAX_SOURCE_TRACKS): List<String> =
        collect("userCollectionTracks/me/relationships/items", max).filter { it.type == "tracks" }.map { it.id }

    suspend fun playlistItems(playlistId: String): List<ItemRef> = collect("playlists/$playlistId/relationships/items", 500)

    private suspend fun collect(path: String, max: Int): List<ItemRef> {
        val result = mutableListOf<ItemRef>()
        var cursor: String? = null
        do {
            val root = call("GET", path, cursor = cursor)
            (root["data"] as? JsonArray).orEmpty().forEach { element ->
                val obj = element.jsonObject
                val id = obj.string("id") ?: return@forEach
                val type = obj.string("type") ?: return@forEach
                result += ItemRef(type, id, obj["meta"]?.jsonObject?.string("itemId"))
            }
            cursor = root["links"]?.jsonObject?.get("meta")?.jsonObject?.string("nextCursor")?.takeIf { it.isNotBlank() }
        } while (cursor != null && result.size < max)
        return result.take(max)
    }

    /** Creates an unlisted playlist in the user's account and returns its id. */
    suspend fun createPlaylist(name: String, description: String): String {
        val body = buildJsonObject {
            putJsonObject("data") {
                put("type", "playlists")
                putJsonObject("attributes") {
                    put("name", name.take(250))
                    put("description", description.take(500))
                    put("accessType", "UNLISTED")
                }
            }
        }
        val root = call("POST", "playlists", body = body.toString())
        return root["data"]?.jsonObject?.string("id") ?: throw ApiException(0, "TIDAL didn't return the new playlist's id")
    }

    suspend fun renamePlaylist(playlistId: String, name: String) {
        val body = buildJsonObject {
            putJsonObject("data") {
                put("id", playlistId)
                put("type", "playlists")
                putJsonObject("attributes") { put("name", name.take(250)) }
            }
        }
        call("PATCH", "playlists/$playlistId", body = body.toString())
    }

    suspend fun removeItems(playlistId: String, items: List<ItemRef>) {
        items.filter { it.itemId != null }.chunked(50).forEach { chunk ->
            val body = buildJsonObject {
                putJsonArray("data") {
                    chunk.forEach { item ->
                        addJsonObject {
                            put("id", item.id)
                            put("type", item.type)
                            putJsonObject("meta") { put("itemId", item.itemId) }
                        }
                    }
                }
            }
            call("DELETE", "playlists/$playlistId/relationships/items", body = body.toString())
        }
    }

    suspend fun addTracks(playlistId: String, trackIds: List<String>) {
        trackIds.chunked(50).forEach { chunk ->
            val body = buildJsonObject {
                putJsonArray("data") {
                    chunk.forEach { id ->
                        addJsonObject {
                            put("id", id)
                            put("type", "tracks")
                        }
                    }
                }
            }
            call("POST", "playlists/$playlistId/relationships/items", body = body.toString())
        }
    }

    suspend fun deletePlaylist(playlistId: String) {
        call("DELETE", "playlists/$playlistId")
    }

    private fun parse(root: JsonObject, map: (JsonObject, Map<String, JsonObject>) -> TidalItem?): Page {
        val included = includedMap(root)
        val items = (root["data"] as? JsonArray).orEmpty().mapNotNull { identifier ->
            val obj = identifier.jsonObject
            val key = "${obj.string("type")}:${obj.string("id")}"
            included[key]?.let { map(it, included) }
        }
        val next = root["links"]?.jsonObject?.get("meta")?.jsonObject?.string("nextCursor")
        return Page(items, next?.takeIf { it.isNotBlank() })
    }

    private fun includedMap(root: JsonObject): Map<String, JsonObject> =
        (root["included"] as? JsonArray).orEmpty().associate { element ->
            val obj = element.jsonObject
            "${obj.string("type")}:${obj.string("id")}" to obj
        }

    private fun related(resource: JsonObject, relationship: String, included: Map<String, JsonObject>): List<JsonObject> {
        val data = resource["relationships"]?.jsonObject?.get(relationship)?.jsonObject?.get("data") as? JsonArray
        return data.orEmpty().mapNotNull { element ->
            val obj = element.jsonObject
            included["${obj.string("type")}:${obj.string("id")}"]
        }
    }

    /** Picks an artwork file of at least ~240 px, else the largest. */
    private fun artwork(resource: JsonObject, relationship: String, included: Map<String, JsonObject>): String? {
        val art = related(resource, relationship, included).firstOrNull() ?: return null
        val files = art["attributes"]?.jsonObject?.get("files")?.jsonArray ?: return null
        val sized = files.mapNotNull { file ->
            val obj = file.jsonObject
            val href = obj.string("href") ?: return@mapNotNull null
            val width = obj["meta"]?.jsonObject?.get("width")?.jsonPrimitive?.intOrNull ?: 0
            href to width
        }
        return (sized.filter { it.second >= 240 }.minByOrNull { it.second } ?: sized.maxByOrNull { it.second })?.first
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

    private companion object {
        const val BASE = "https://openapi.tidal.com/v2"
        val JSON_API = "application/vnd.api+json".toMediaType()

        /** Enough to shuffle a big library; more would make preparing slow. */
        const val MAX_SOURCE_TRACKS = 2000
    }
}
