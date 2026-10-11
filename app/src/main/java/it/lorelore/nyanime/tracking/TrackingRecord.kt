package it.lorelore.nyanime.tracking

import kotlinx.serialization.Serializable
import java.security.MessageDigest

@Serializable
enum class TrackingMedium { ANIME, MANGA }

@Serializable
data class TrackingSubject(val medium: TrackingMedium, val source: Long, val url: String) {
    val key: String get() = "${medium.name}:$source:${url.length}:$url".let { value ->
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun valid() = url.isNotBlank() && url.length <= 16_384
}

@Serializable
enum class PersonalTrackingStatus { CURRENT, REPEATING, COMPLETED, PAUSED, DROPPED, PLANNED }

/** Provider status/score values remain in the provider's original units. */
@Serializable
data class TrackingValues(
    val status: Long = PersonalTrackingStatus.PLANNED.ordinal.toLong(),
    val progress: Double = 0.0,
    val score: Double = 0.0,
    val startDate: Long = 0,
    val finishDate: Long = 0,
    val private: Boolean = false,
    val repeats: Int = 0,
    val volumes: Double = 0.0,
    val notes: String = "",
    val tags: List<String> = emptyList(),
) {
    fun valid() = progress.isFinite() &&
        progress in 0.0..1_000_000.0 &&
        score.isFinite() &&
        score in 0.0..1_000_000.0 &&
        startDate >= 0 &&
        finishDate >= 0 &&
        repeats in 0..100_000 &&
        volumes.isFinite() &&
        volumes in 0.0..1_000_000.0 &&
        notes.length <= 32_768 &&
        tags.size <= 32 &&
        tags.all { it.length in 1..80 }
}

@Serializable
enum class TrackingField { STATUS, PROGRESS, SCORE, START, FINISH, PRIVATE, REPEATS, VOLUMES, NOTES, TAGS }

@Serializable
data class TrackingPatch(val values: TrackingValues, val fields: Set<TrackingField>) {
    fun applyTo(current: TrackingValues): TrackingValues = current.copy(
        status = if (TrackingField.STATUS in fields) values.status else current.status,
        progress = if (TrackingField.PROGRESS in fields) values.progress else current.progress,
        score = if (TrackingField.SCORE in fields) values.score else current.score,
        startDate = if (TrackingField.START in fields) values.startDate else current.startDate,
        finishDate = if (TrackingField.FINISH in fields) values.finishDate else current.finishDate,
        private = if (TrackingField.PRIVATE in fields) values.private else current.private,
        repeats = if (TrackingField.REPEATS in fields) values.repeats else current.repeats,
        volumes = if (TrackingField.VOLUMES in fields) values.volumes else current.volumes,
        notes = if (TrackingField.NOTES in fields) values.notes else current.notes,
        tags = if (TrackingField.TAGS in fields) values.tags else current.tags,
    )

    fun then(next: TrackingPatch) = TrackingPatch(next.applyTo(values), fields + next.fields)

    /** Undo changes only fields which still have the value written by this edit. */
    fun inverse(before: TrackingValues, current: TrackingValues): TrackingPatch = TrackingPatch(
        before,
        fields.filterTo(mutableSetOf()) { field ->
            TrackingPatch(values, setOf(field)).applyTo(current) == current
        },
    )

    companion object {
        val REMOTE_FIELDS = setOf(
            TrackingField.STATUS,
            TrackingField.PROGRESS,
            TrackingField.SCORE,
            TrackingField.START,
            TrackingField.FINISH,
            TrackingField.PRIVATE,
        )
    }
}

@Serializable
data class PersonalTrackingRecord(
    val subject: TrackingSubject,
    val values: TrackingValues = TrackingValues(),
    val observedProgress: Double = 0.0,
    val changedAt: Long = 0,
) {
    fun valid() = subject.valid() &&
        values.valid() &&
        values.status in PersonalTrackingStatus.entries.indices.map(Int::toLong) &&
        values.score in 0.0..10.0 &&
        observedProgress.isFinite() &&
        observedProgress in 0.0..1_000_000.0 &&
        changedAt >= 0

    fun observe(progress: Double): PersonalTrackingRecord {
        if (!progress.isFinite() || progress <= observedProgress || progress > 1_000_000) return this
        return copy(
            observedProgress = progress,
            values = values.copy(
                progress = maxOf(values.progress, progress),
                status = if (values.status == PersonalTrackingStatus.PLANNED.ordinal.toLong()) {
                    PersonalTrackingStatus.CURRENT.ordinal.toLong()
                } else {
                    values.status
                },
            ),
        )
    }
}

@Serializable
data class PendingTrackingEdit(
    val subject: TrackingSubject,
    val entryId: Long,
    val trackId: Long,
    val trackerId: Long,
    val remoteId: Long,
    val account: String,
    val patch: TrackingPatch,
    val revision: Long,
    val failed: Boolean = false,
) {
    val key: String get() = "${subject.medium}:$trackId"
    fun valid() = subject.valid() &&
        entryId > 0 &&
        trackId > 0 &&
        trackerId > 0 &&
        account.length in 1..256 &&
        patch.values.valid() &&
        patch.fields.isNotEmpty() &&
        patch.fields.all { it in TrackingPatch.REMOTE_FIELDS } &&
        revision > 0

    fun acknowledge(confirmed: PendingTrackingEdit): PendingTrackingEdit? {
        if (key != confirmed.key || account != confirmed.account || remoteId != confirmed.remoteId) return this
        val remaining = patch.fields.filterTo(mutableSetOf()) { field ->
            field !in confirmed.patch.fields ||
                TrackingPatch(confirmed.patch.values, setOf(field)).applyTo(patch.values) != patch.values
        }
        return if (remaining.isEmpty()) null else copy(patch = patch.copy(fields = remaining))
    }
}
