package it.lorelore.nyanime.update

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ReleaseGraphTest {
    private val repository = "example/application"
    private val packageName = "it.example.application"
    private fun node(number: Int, complete: Boolean = false, abi: String = "arm64-v8a") = ReleaseGraph.Package(
        number.toString(16).repeat(64),
        1000,
        "1.0.0.$number",
        number.toLong(),
        abi,
        if (complete) {
            ReleaseGraph.assetUrl(
                repository,
                "1.0.0.$number",
                ReleaseGraph.apkName("1.0.0.$number", abi),
            )
        } else {
            null
        },
    )
    private fun edge(from: ReleaseGraph.Package, to: ReleaseGraph.Package, size: Long = 100) = ReleaseGraph.Patch(
        from.sha256,
        to.sha256,
        (from.versionCode + to.versionCode + 1).toString(16).padStart(64, '0'),
        size,
        ReleaseGraph.assetUrl(repository, to.versionName, ReleaseGraph.patchName(from.sha256, to.versionName, to.abi)),
    )
    private fun graph(nodes: List<ReleaseGraph.Package>, edges: List<ReleaseGraph.Patch>) = ReleaseGraph(
        2,
        packageName,
        nodes.last().versionName,
        nodes.last().versionCode,
        listOf(nodes.last().sha256),
        nodes,
        edges,
    )
    private fun ReleaseGraph.valid() = validate(repository, packageName, versionName)

    @Test
    fun `skipped versions use cheapest exact hash route and direct patch when cheaper`() {
        val a = node(1, true)
        val b = node(2)
        val c = node(3)
        val graph = graph(listOf(a, b, c), listOf(edge(a, b), edge(b, c), edge(a, c, 250))).valid()
        assertEquals(200, graph.route(listOf(a.abi), setOf(a.sha256))!!.downloadBytes)
        val direct = graph.copy(patches = graph.patches.dropLast(1) + edge(a, c, 150)).valid()
        assertEquals(listOf(edge(a, c, 150)), direct.route(listOf(a.abi), setOf(a.sha256))!!.steps)
    }

    @Test
    fun `cold install or unknown installed bytes download a retained complete checkpoint`() {
        val a = node(1, true)
        val b = node(2)
        val graph = graph(listOf(a, b), listOf(edge(a, b))).valid()
        assertEquals(1100, graph.route(listOf(a.abi), setOf("f".repeat(64)))!!.downloadBytes)
        assertEquals(100, graph.route(listOf(a.abi), setOf(a.sha256))!!.downloadBytes)
        assertNull(graph.route(listOf("x86"), emptySet()))
    }

    @Test
    fun `failed patch chooses an actual complete fallback and never invents target APK`() {
        val a = node(1, true)
        val b = node(2, true)
        val c = node(3)
        val graph = graph(listOf(a, b, c), listOf(edge(a, c), edge(b, c))).valid()
        val route = graph.route(listOf(a.abi), setOf(a.sha256), setOf(edge(a, c).sha256))!!
        assertEquals(b, route.base)
        assertEquals(1100, route.downloadBytes)
        assertNull(graph.route(listOf(a.abi), setOf(a.sha256), graph.patches.map { it.sha256 }.toSet()))
    }

    @Test
    fun `unreachable cycles cross ABI and nonmonotonic versions are rejected`() {
        val a = node(1, true)
        val b = node(2)
        val good = graph(listOf(a, b), listOf(edge(a, b)))
        for (bad in listOf(
            good.copy(patches = emptyList()),
            good.copy(patches = good.patches + edge(b, a)),
            good.copy(packages = listOf(a, b.copy(abi = "x86"))),
            good.copy(packages = listOf(a, b.copy(versionCode = 1))),
            good.copy(packages = listOf(a, b.copy(sha256 = "Z".repeat(64)))),
            good.copy(targets = listOf(a.sha256)),
            good.copy(packages = good.packages + a),
        )) {
            assertThrows(Exception::class.java) { bad.valid() }
        }
    }

    @Test
    fun `only exact immutable same repository URLs are allowed`() {
        val a = node(1, true)
        val b = node(2)
        val good = graph(listOf(a, b), listOf(edge(a, b)))
        for (url in listOf(
            a.url!!.replace("https:", "http:"),
            a.url + "?token=x",
            a.url + "#x",
            a.url.replace(repository, "other/application"),
            a.url.replace("github.com", "github.com.evil.test"),
            a.url.replace("v1.0.0.1", "latest"),
            a.url.replace("github.com", "user@github.com"),
        )) {
            assertThrows(Exception::class.java) { good.copy(packages = listOf(a.copy(url = url), b)).valid() }
        }
        val url = ReleaseGraph.assetUrl(repository, b.versionName, ReleaseGraph.FILE_NAME)
        assertEquals(b.versionName, ReleaseGraph.descriptorVersion(url, repository))
        assertNull(ReleaseGraph.descriptorVersion(url + "?download=1", repository))
    }

    @Test
    fun `JSON rejects unknown schema identity excessive depth and extra fields`() {
        val a = node(1, true)
        val good = graph(listOf(a), emptyList())
        val text = Json.encodeToString(good)
        assertEquals(good, ReleaseGraph.parse(text, repository, packageName, a.versionName))
        for (bad in listOf(
            text.replace("\"schema\":2", "\"schema\":3"),
            text.dropLast(1) + ",\"extra\":true}",
            "[".repeat(100),
            " ".repeat(ReleaseGraph.MAX_BYTES + 1),
        )) {
            assertThrows(Exception::class.java) { ReleaseGraph.parse(bad, repository, packageName, a.versionName) }
        }
        assertThrows(Exception::class.java) { ReleaseGraph.parse(text, repository, "wrong.package", a.versionName) }
    }

    @Test
    fun `routes longer than bound are not advertised`() {
        val nodes = (1..10).map { node(it, it == 1) }
        val graph = graph(nodes, nodes.zipWithNext(::edge))
        assertNull(graph.route(listOf(nodes.first().abi), setOf(nodes.first().sha256)))
        assertThrows(Exception::class.java) { graph.valid() }
        val resumed = graph.copy(
            packages = nodes.map {
                if (it == nodes[1]) {
                    it.copy(
                        url =
                        ReleaseGraph.assetUrl(repository, it.versionName, ReleaseGraph.apkName(it.versionName, it.abi)),
                    )
                } else {
                    it
                }
            },
        )
        assertEquals(8, resumed.valid().route(listOf(nodes.first().abi), emptySet())!!.steps.size)
    }
}
