package nyanime.tv.api

/** Source-independent rules used by the host and by extension tests. */
object TvRules {
    fun valid(ref: TvChannelRef): Boolean = ref.sourceId != 0L &&
        text(ref.id, 1024) &&
        !ref.id.startsWith("http://", true) &&
        !ref.id.startsWith("https://", true)

    fun valid(channel: TvChannel): Boolean = valid(channel.ref) &&
        text(channel.name, 240) &&
        (channel.logoUrl == null || webUrl(channel.logoUrl)) &&
        channel.languages.size <= 32 &&
        channel.languages.all { text(it, 32) } &&
        channel.categories.size <= 32 &&
        channel.categories.all { text(it, 100) } &&
        (channel.country == null || text(channel.country, 128)) &&
        (channel.feedName == null || text(channel.feedName, 240))

    fun valid(capabilities: TvCapabilities): Boolean = labels(capabilities.homeTitle, 80) &&
        (capabilities.initialCountry == null || text(capabilities.initialCountry, 128)) &&
        (capabilities.attribution?.length ?: 0) <= 4096 &&
        listOf(capabilities.countries, capabilities.languages, capabilities.categories).all { facets ->
            facets.size <= 10_000 &&
                facets.map { it.id }.distinct().size == facets.size &&
                facets.all { text(it.id, 128) && labels(it.labels, 240) }
        }

    fun valid(section: TvHomeSection): Boolean = text(section.id, 128) &&
        labels(section.labels, 240) &&
        section.channels.size <= 500 &&
        section.channels.all(::valid) &&
        (section.category == null || text(section.category, 128))

    fun valid(programme: TvProgramme): Boolean = valid(programme.channel) &&
        text(programme.id, 1024) &&
        text(programme.title, 500) &&
        programme.startUtc > 0 &&
        programme.endUtc > programme.startUtc &&
        programme.endUtc - programme.startUtc <= 7 * DAY &&
        (programme.description?.length ?: 0) <= 32_000 &&
        (programme.imageUrl == null || webUrl(programme.imageUrl))

    fun valid(stream: TvStream): Boolean = text(stream.id, 1024) &&
        webUrl(stream.url) &&
        text(stream.label, 240) &&
        stream.headers.size <= 32 &&
        stream.headers.all { (key, value) -> text(key, 200) && text(value, 8192) } &&
        (stream.resolution == null || stream.resolution in 1..16_384) &&
        (stream.bitrate == null || stream.bitrate > 0) &&
        (stream.mimeType == null || text(stream.mimeType, 240)) &&
        (stream.clock == null || stream.clock.mediaPositionSeconds.isFinite() && stream.clock.utcMillis > 0) &&
        stream.audio.size <= 32 &&
        stream.subtitles.size <= 32 &&
        (stream.audio + stream.subtitles).all { webUrl(it.url) && text(it.label, 240) }

    fun nowNext(programmes: List<TvProgramme>, nowUtc: Long): Pair<TvProgramme?, TvProgramme?> {
        val ordered = programmes.filter(::valid).sortedBy { it.startUtc }
        val current = ordered.lastOrNull { it.startUtc <= nowUtc && it.endUtc > nowUtc }
        val next = ordered.firstOrNull { it.startUtc >= (current?.endUtc ?: nowUtc) }
        return current to next
    }

    fun progress(programme: TvProgramme, nowUtc: Long): Float =
        if (programme.endUtc <= programme.startUtc) {
            0f
        } else {
            ((nowUtc - programme.startUtc).toDouble() / (programme.endUtc - programme.startUtc)).toFloat().coerceIn(
                0f,
                1f,
            )
        }

    fun webUrl(value: String): Boolean = runCatching {
        val uri = java.net.URI(value)
        value.length <= 16_384 &&
            uri.scheme in setOf("http", "https") &&
            !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null &&
            value.none(Char::isISOControl)
    }.getOrDefault(false)

    private fun text(value: String, max: Int) =
        value.isNotBlank() && value.length <= max && value.none(Char::isISOControl)
    private fun labels(values: Map<String, String>, max: Int): Boolean = values.size in 1..50 &&
        values.all { (locale, value) -> text(locale, 35) && text(value, max) }
    const val DAY = 86_400_000L
}
