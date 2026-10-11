package it.lorelore.nyanime.backup.remote

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class WebDavBackupStoreTest {
    @Test fun `UTF16 XML is decoded before applying the DTD policy`() {
        val root = "https://storage.example/nyanime-backups-v1/".toHttpUrl()
        val name = "nyanime-${UUID.randomUUID()}.json"
        val xml = "<?xml version='1.0' encoding='UTF-16'?><d:multistatus xmlns:d='DAV:'>" +
            "<d:response><d:href>$root$name</d:href><d:propstat><d:prop>" +
            "<d:getcontentlength>42</d:getcontentlength></d:prop></d:propstat></d:response></d:multistatus>"
        assertEquals(name, parseWebDavObjects(decodeWebDavXml(xml.toByteArray(Charsets.UTF_16)), root).single().name)
    }

    @Test fun `UTF16 cannot conceal an entity declaration from the parser policy`() {
        val xml = "<!DOCTYPE data [<!ENTITY a 'example'>]><d:multistatus xmlns:d='DAV:'>&a;</d:multistatus>"
        assertThrows(IllegalArgumentException::class.java) {
            parseWebDavObjects(
                decodeWebDavXml(xml.toByteArray(Charsets.UTF_16)),
                "https://storage.example/".toHttpUrl(),
            )
        }
    }

    @Test fun `DAV parser ignores foreign paths and reports only owned objects`() {
        val root = "https://storage.example/folder/nyanime-backups-v1/".toHttpUrl()
        val name = "nyanime-${UUID.randomUUID()}.json"
        val paths = listOf(
            root.toString() + name,
            "https://other.example/" + name,
            "/other/" + name,
            root.toString() + "../" + name,
            root.toString() + "%2e%2e/" + name,
            root.toString() + "private.txt",
        )
        val xml = "<d:multistatus xmlns:d=\"DAV:\">" +
            paths.joinToString("") {
                "<d:response><d:href>$it</d:href><d:propstat><d:prop>" +
                    "<d:getcontentlength>42</d:getcontentlength></d:prop></d:propstat></d:response>"
            } +
            "</d:multistatus>"
        assertEquals(listOf(RemoteObject(name, name, 42)), parseWebDavObjects(xml, root))
    }

    @Test fun `external XML entities cannot be evaluated`() {
        val xml = "<!DOCTYPE data [<!ENTITY x SYSTEM 'file:///private'>]>" +
            "<d:multistatus xmlns:d='DAV:'>&x;</d:multistatus>"
        assertThrows(Exception::class.java) { parseWebDavObjects(xml, "https://storage.example/".toHttpUrl()) }
    }

    @Test fun `redirect never forwards credentials to another server`() = runBlocking<Unit> {
        MockWebServer().use { first ->
            MockWebServer().use { second ->
                first.start()
                second.start()
                first.enqueue(MockResponse.Builder().code(302).addHeader("Location", second.url("/")).build())
                val store =
                    WebDavBackupStore(
                        first.url("/folder/").toString(),
                        "test-user",
                        "test-secret",
                        allowHttpForTests = true,
                    )
                assertThrows(RemoteStorageException::class.java) { runBlocking { store.prepare() } }
                assertEquals(1, first.requestCount)
                assertEquals(0, second.requestCount)
                assertEquals("MKCOL", first.takeRequest().method)
            }
        }
    }

    @Test fun `HTTPS is required and credentials cannot be hidden inside an endpoint`() {
        assertThrows(IllegalArgumentException::class.java) { WebDavBackupStore("http://storage.example/", "", "") }
        assertThrows(IllegalArgumentException::class.java) {
            WebDavBackupStore("https://name:secret@storage.example/", "", "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            WebDavBackupStore("https://storage.example/?token=secret", "", "")
        }
    }

    @Test fun `short XML response is read without waiting for the maximum size`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(207).body("<d:multistatus xmlns:d='DAV:'/>").build())
            val store = WebDavBackupStore(server.url("/folder/").toString(), "", "", allowHttpForTests = true)
            assertTrue(store.list().isEmpty())
            assertEquals("PROPFIND", server.takeRequest().method)
        }
    }
}
