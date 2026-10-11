package it.lorelore.nyanime.backup.remote

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.RandomAccessFile

internal class DriveBackupStore(
    private val token: String,
    private val http: OkHttpClient = remoteHttpClient(),
    private val onUnauthorized: suspend () -> Unit = {},
) : RemoteFileStore {
    private val api = "https://www.googleapis.com/drive/v3/files".toHttpUrl()
    override suspend fun prepare() = Unit

    override suspend fun list(): List<RemoteObject> {
        val result = mutableListOf<RemoteObject>()
        val seen = mutableSetOf<String>()
        var page: String? = null
        do {
            val url = api.newBuilder().addQueryParameter("spaces", "appDataFolder")
                .addQueryParameter("q", "trashed = false and appProperties has { key='nyanimeBackup' and value='1' }")
                .addQueryParameter("fields", "nextPageToken,files(id,name,size)")
                .addQueryParameter("pageSize", "1000").apply {
                    page?.let { addQueryParameter("pageToken", it) }
                }.build()
            val json = execute(request(url).build()).use { it.json() }
            val files = json["files"] as? JsonArray ?: error("Invalid file list")
            files.forEach { value ->
                val item = value.jsonObject
                val name = item["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val id = item["id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val size = item["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return@forEach
                if (isRemoteBackupName(name) &&
                    validId(id) &&
                    size in 0..MAX_REMOTE_BYTES
                ) {
                    result += RemoteObject(id, name, size)
                }
            }
            require(result.size <= 2000)
            page = json["nextPageToken"]?.jsonPrimitive?.contentOrNull
            if (page != null) require(page!!.length <= 4096 && seen.add(page!!))
            require(seen.size <= 5)
        } while (page != null)
        return result
    }

    override suspend fun upload(name: String, file: File): RemoteObject {
        require(isRemoteBackupName(name) && file.length() in 1..MAX_REMOTE_BYTES)
        // Immutable names are tied to the persisted WorkManager job. A retried commit reuses its object.
        val existing = list().filter { it.name == name }
        require(existing.size <= 1)
        existing.singleOrNull()?.let { return it }
        val metadata = buildJsonObject {
            put("name", name)
            put("parents", JsonArray(listOf(JsonPrimitive("appDataFolder"))))
            put("appProperties", buildJsonObject { put("nyanimeBackup", "1") })
        }
        val session = execute(
            request("https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&fields=id".toHttpUrl())
                .header("X-Upload-Content-Type", "application/octet-stream")
                .header("X-Upload-Content-Length", file.length().toString())
                .post(metadata.toString().toRequestBody("application/json".toMediaType())).build(),
        ).use {
            it.requireSuccess()
            val location = requireNotNull(it.header("Location")).toHttpUrl()
            require(location.isHttps && location.host == api.host && location.port == 443)
            require(
                location.encodedPath == "/upload/drive/v3/files" &&
                    location.username.isEmpty() &&
                    location.password.isEmpty(),
            )
            location
        }
        var offset = 0L
        var recoveries = 0
        RandomAccessFile(file, "r").use { input ->
            while (offset < file.length()) {
                currentCoroutineContext().ensureActive()
                input.seek(offset)
                val bytes = ByteArray(minOf(1024 * 1024L, file.length() - offset).toInt())
                input.readFully(bytes)
                val request = request(
                    session,
                ).header("Content-Range", "bytes $offset-${offset + bytes.size - 1}/${file.length()}")
                    .put(bytes.toRequestBody("application/octet-stream".toMediaType())).build()
                try {
                    execute(request).use { response ->
                        if (response.code == 308) {
                            val acknowledged = acknowledged(response, file.length())
                            require(acknowledged > offset && acknowledged <= offset + bytes.size)
                            offset = acknowledged
                        } else {
                            val id = response.json()["id"]?.jsonPrimitive?.contentOrNull ?: error("Missing file ID")
                            require(validId(id))
                            return RemoteObject(id, name, file.length())
                        }
                    }
                } catch (failure: RemoteStorageException) {
                    if (failure.reason !in setOf("network", "server") || recoveries++ >= 2) throw failure
                    execute(
                        request(
                            session,
                        ).header("Content-Range", "bytes */${file.length()}").put(ByteArray(0).toRequestBody()).build(),
                    ).use { status ->
                        if (status.code == 308) {
                            offset = acknowledged(status, file.length())
                        } else {
                            val id = status.json()["id"]?.jsonPrimitive?.contentOrNull ?: error("Missing file ID")
                            require(validId(id))
                            return RemoteObject(id, name, file.length())
                        }
                    }
                }
            }
        }
        throw RemoteStorageException("incomplete")
    }

    override suspend fun download(id: String, file: File, limit: Long) {
        require(validId(id))
        execute(
            request(api.newBuilder().addPathSegment(id).addQueryParameter("alt", "media").build()).build(),
        )
            .use { it.saveRemote(file, limit) }
    }

    override suspend fun delete(id: String) {
        require(validId(id))
        execute(request(api.newBuilder().addPathSegment(id).build()).delete().build())
            .use { if (it.code != 404) it.requireSuccess() }
    }

    private suspend fun execute(request: Request): Response {
        val response = http.remoteRequest(request)
        if (response.code == 401) {
            response.close()
            try {
                onUnauthorized()
            } catch (
                cancel: kotlinx.coroutines.CancellationException,
            ) {
                throw cancel
            } catch (
                _: Exception,
            ) { /* Still surface the authorization failure if cache clearing fails. */ }
            throw RemoteStorageException("authorization", 401)
        }
        return response
    }
    private fun request(url: HttpUrl) = Request.Builder().url(url).header("Authorization", "Bearer $token")
    private suspend fun Response.json(): JsonObject {
        requireSuccess()
        val bytes = readRemoteBytes(512 * 1024)
        require(bytes.size <= 512 * 1024)
        return boundedRemoteJson(bytes.toString(Charsets.UTF_8)).jsonObject
    }
    private fun validId(id: String) = id.matches(Regex("[A-Za-z0-9_-]{1,200}"))
    private fun acknowledged(response: Response, size: Long): Long {
        val range = response.header("Range") ?: return 0
        require(range.matches(Regex("bytes=0-[0-9]+")))
        return (range.substringAfterLast('-').toLong() + 1).also { require(it in 0..size) }
    }
}
