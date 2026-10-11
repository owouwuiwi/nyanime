package eu.kanade.domain.extension

import java.net.URI

sealed interface ExtensionCatalogueLink {
    data class Index(val url: String, val isDrive: Boolean = false) : ExtensionCatalogueLink

    data class DriveFolder(val id: String, val resourceKey: String?) : ExtensionCatalogueLink {
        val listingUrl: String
            get() = "https://drive.google.com/embeddedfolderview?id=$id${resourceKeyParameter(resourceKey)}"
    }

    companion object {
        private const val DRIVE_TOKEN = "[A-Za-z0-9_-]{1,128}"
        private val folderPath = Regex("/drive/(?:u/[0-9]+/)?folders/($DRIVE_TOKEN)/?")
        private val filePath = Regex("/file/(?:u/[0-9]+/)?d/($DRIVE_TOKEN)(?:/(?:view|edit|preview))?/?")
        private val driveToken = Regex(DRIVE_TOKEN)

        fun parse(value: String): ExtensionCatalogueLink? {
            val url = httpsUri(value) ?: return null
            if (url.isDriveHost()) return parseDrive(url)
            return if (url.rawQuery == null && url.path.endsWith(".json")) Index(value) else null
        }

        fun isLegacyRepositoryUrl(value: String): Boolean = httpsUri(value)?.let {
            !it.isDriveHost() && it.rawQuery == null
        } == true

        private fun URI.isDriveHost(): Boolean = host.equals("drive.google.com", ignoreCase = true) ||
            host.equals("drive.usercontent.google.com", ignoreCase = true)

        private fun httpsUri(value: String): URI? {
            if (value.length !in 1..2048) return null
            return runCatching { URI(value) }.getOrNull()?.takeIf {
                it.scheme == "https" && !it.host.isNullOrBlank() && it.rawUserInfo == null && it.rawFragment == null
            }
        }

        private fun parseDrive(url: URI): ExtensionCatalogueLink? {
            if (url.port != -1) return null
            val parameters = driveParameters(url.rawQuery) ?: return null
            val resourceKey = parameters["resourcekey"]
            if (url.host.equals("drive.google.com", ignoreCase = true)) {
                val folder = folderPath.matchEntire(url.rawPath)?.groupValues?.get(1)
                val file = filePath.matchEntire(url.rawPath)?.groupValues?.get(1)
                if (folder != null || file != null) {
                    if (parameters.keys.any { it !in setOf("usp", "resourcekey") }) return null
                    if (folder != null) return DriveFolder(folder, resourceKey)
                    return driveIndex(file, resourceKey)
                }
                if (url.rawPath != "/uc") return null
            } else if (url.rawPath != "/download") {
                return null
            }

            if (parameters.keys.any { it !in setOf("id", "export", "filename", "resourcekey", "confirm", "usp") }) {
                return null
            }
            if (parameters["export"]?.let { it != "download" } == true ||
                parameters["filename"]?.let { it != "index.json" } == true ||
                parameters["confirm"]?.let { it != "t" } == true
            ) return null
            return driveIndex(parameters["id"], resourceKey)
        }

        private fun driveParameters(rawQuery: String?): Map<String, String>? {
            if (rawQuery == null) return emptyMap()
            val parameters = mutableMapOf<String, String>()
            for (parameter in rawQuery.split('&')) {
                val name = parameter.substringBefore('=')
                val value = parameter.substringAfter('=', "")
                val isIndexFilename = name == "filename" && value == "index.json"
                if (!isIndexFilename && !value.matches(driveToken)) return null
                if (parameters.put(name, value) != null) return null
            }
            return parameters
        }

        private fun driveIndex(id: String?, resourceKey: String?): Index? {
            if (id == null || !id.matches(driveToken)) return null
            return Index(
                "https://drive.google.com/uc?export=download&id=$id${resourceKeyParameter(resourceKey)}&filename=index.json",
                isDrive = true,
            )
        }

        private fun resourceKeyParameter(key: String?): String = key?.let { "&resourcekey=$it" }.orEmpty()
    }
}
