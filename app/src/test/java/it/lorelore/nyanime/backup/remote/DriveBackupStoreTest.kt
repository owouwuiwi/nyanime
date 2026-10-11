package it.lorelore.nyanime.backup.remote

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.UUID

class DriveBackupStoreTest {
    @TempDir lateinit var directory: File

    @Test fun `Drive uses appDataFolder and rejects an off-origin upload session`() = runBlocking<Unit> {
        val requests = mutableListOf<okhttp3.Request>()
        val http = remoteHttpClient().newBuilder().addInterceptor { chain ->
            val request = chain.request().also { requests += it }
            val body = if (request.method == "GET") "{\"files\":[]}" else ""
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Location", "https://other.example/upload")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val file = File(directory, "example").apply { writeText("Example") }
        val store = DriveBackupStore("test-token", http)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                store.upload("nyanime-${UUID.randomUUID()}.nyabk.enc", file)
            }
        }
        assertEquals(2, requests.size)
        assertTrue(requests.all { it.url.host == "www.googleapis.com" })
        assertEquals("appDataFolder", requests.first().url.queryParameter("spaces"))
    }

    @Test fun `resumable upload continues from the acknowledged range`() = runBlocking<Unit> {
        val ranges = mutableListOf<String>()
        var uploads = 0
        val http = remoteHttpClient().newBuilder().addInterceptor { chain ->
            val request = chain.request()
            val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("OK")
            when (request.method) {
                "GET" -> response.code(200).body("{\"files\":[]}".toResponseBody())
                "POST" -> response.code(
                    200,
                ).header("Location", "https://www.googleapis.com/upload/drive/v3/files?upload_id=test")
                    .body("".toResponseBody())
                else -> {
                    ranges += requireNotNull(request.header("Content-Range"))
                    if (uploads++ == 0) {
                        response.code(308).header("Range", "bytes=0-1048575").body("".toResponseBody())
                    } else {
                        response.code(200).body("{\"id\":\"test-file-id\"}".toResponseBody())
                    }
                }
            }.build()
        }.build()
        val file = File(directory, "example").apply { writeBytes(ByteArray(1250000)) }
        val result = DriveBackupStore("test-token", http).upload("nyanime-${UUID.randomUUID()}.nyabk.enc", file)
        assertEquals("test-file-id", result.id)
        assertEquals(listOf("bytes 0-1048575/1250000", "bytes 1048576-1249999/1250000"), ranges)
    }

    @Test fun `Drive authorization error never becomes an empty successful list`() = runBlocking<Unit> {
        val http = remoteHttpClient().newBuilder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(401).message("Denied").body("{}".toResponseBody()).build()
        }.build()
        val failure =
            assertThrows(RemoteStorageException::class.java) {
                runBlocking { DriveBackupStore("test-token", http).list() }
            }
        assertEquals("authorization", failure.reason)
    }
}
