package it.lorelore.nyanime.studio

import java.net.InetAddress
import java.net.URI
import java.util.Locale

/** Rules come from the separately reviewed component. No provider endpoints are built into the host. */
class DocumentOriginPolicy(hosts: Set<String>) {
    val hosts: Set<String> = hosts.map { rule ->
        val normalized = rule.lowercase(Locale.ROOT)
        val domain = normalized.removePrefix("*.")
        require(domain.length in 3..253 && domain.split('.').size >= 2)
        require(
            domain.split('.').all { label ->
                label.length in 1..63 &&
                    label.matches(Regex("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?"))
            },
        )
        require(!domain.all { it.isDigit() || it == '.' })
        normalized
    }.toSet().also { require(it.size in 1..32) }

    fun requireUrl(raw: String): URI {
        require(raw.length in 1..8192)
        val uri = URI(raw)
        val host = requireNotNull(uri.host?.lowercase(Locale.ROOT)) { "Invalid document origin" }
        require(uri.scheme == "https" && uri.userInfo == null && uri.port in setOf(-1, 443) && uri.fragment == null)
        require(
            hosts.any { rule ->
                if (rule.startsWith("*.")) {
                    host.endsWith(rule.removePrefix("*")) && host != rule.removePrefix("*.")
                } else {
                    host == rule
                }
            },
        ) { "The document component did not declare this origin" }
        return uri
    }

    /** Used by the actual HTTP client's DNS callback, not only by a preflight lookup. */
    fun requirePublic(addresses: List<InetAddress>): List<InetAddress> {
        require(addresses.isNotEmpty() && addresses.all(::publicAddress)) {
            "Private network destinations are not authorized"
        }
        return addresses
    }

    private fun publicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) {
            return false
        }
        val bytes = address.address.map { it.toInt() and 255 }
        if (bytes.size == 4) {
            val a = bytes[0]
            val b = bytes[1]
            val c = bytes[2]
            return !(
                a == 0 ||
                    a >= 224 ||
                    a == 127 ||
                    a == 10 ||
                    a == 100 &&
                    b in 64..127 ||
                    a == 169 &&
                    b == 254 ||
                    a == 172 &&
                    b in 16..31 ||
                    a == 192 &&
                    (b == 168 || b == 0 && c in setOf(0, 2)) ||
                    a == 198 &&
                    (b in 18..19 || b == 51 && c == 100) ||
                    a == 203 &&
                    b == 0 &&
                    c == 113
                )
        }
        if (bytes.size == 16) {
            // Public IPv6 unicast only; reject unique-local, mapped/reserved and documentation ranges.
            return bytes[0] in 0x20..0x3f &&
                !(bytes[0] == 0x20 && bytes[1] == 1 && bytes[2] == 0x0d && bytes[3] == 0xb8)
        }
        return false
    }
}
