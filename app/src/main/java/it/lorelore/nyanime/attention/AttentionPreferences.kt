package it.lorelore.nyanime.attention

import it.lorelore.nyanime.attention.core.AttentionMode
import it.lorelore.nyanime.attention.core.AttentionOptions
import it.lorelore.nyanime.attention.core.AttentionTraceChunk
import it.lorelore.nyanime.attention.core.AttentionTraceCodec
import it.lorelore.nyanime.attention.core.RecoveryMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tachiyomi.core.common.preference.PreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/** Portable keys deliberately contain no device/database row IDs or biometric calibration. */
class AttentionPreferences(
    private val store: PreferenceStore = Injekt.get(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    val enabled = store.getBoolean("attention_v1_enabled", false)
    val mode = store.getInt("attention_v1_mode", 0)
    val away = store.getBoolean("attention_v1_away", true)
    val closed = store.getBoolean("attention_v1_closed", true)
    val resumeAway = store.getBoolean("attention_v1_resume_away", true)
    val resumeClosed = store.getBoolean("attention_v1_resume_closed", false)
    val recovery = store.getInt("attention_v1_recovery", 1)
    val awaySeconds = store.getInt("attention_v1_away_seconds", 3)
    val closedSeconds = store.getInt("attention_v1_closed_seconds", 10)
    val returnMillis = store.getInt("attention_v1_return_ms", 1_500)
    val marginSeconds = store.getInt("attention_v1_margin_seconds", 3)
    val waitSeconds = store.getInt("attention_v1_wait_seconds", 60)
    val sensitivity = store.getFloat("attention_v1_sensitivity", 1f)

    // A single serialized preference participates in the existing full-backup flow.
    private val points = store.getString("attention_v1_recovery_points", "[]")
    private val json = Json { ignoreUnknownKeys = true }
    private val privatePoints = mutableMapOf<String, RecoveryPoint>()

    fun options() = AttentionOptions(
        enabled = enabled.get(),
        mode = AttentionMode.entries.getOrElse(mode.get()) {
            AttentionMode.Automatic
        },
        pauseAway = away.get(), pauseClosed = closed.get(),
        awayMs = awaySeconds.get() * 1_000L, closedMs = closedSeconds.get() * 1_000L,
        returnMs = returnMillis.get().toLong(), resumeAway = resumeAway.get(), resumeClosed = resumeClosed.get(),
        recovery = RecoveryMode.entries.getOrElse(recovery.get()) { RecoveryMode.Ask },
        marginMs = marginSeconds.get() * 1_000L, cameraWaitMs = waitSeconds.get() * 1_000L,
        sensitivity = sensitivity.get(),
    ).safe()

    fun changes() = merge(
        enabled.changes().map {
            Unit
        },
        mode.changes().map { Unit }, away.changes().map { Unit }, closed.changes().map { Unit },
        resumeAway.changes().map { Unit }, resumeClosed.changes().map { Unit }, recovery.changes().map { Unit },
        awaySeconds.changes().map { Unit }, closedSeconds.changes().map { Unit }, returnMillis.changes().map { Unit },
        marginSeconds.changes().map { Unit }, waitSeconds.changes().map { Unit }, sensitivity.changes().map { Unit },
    ).map { options() }

    private fun stored(): List<RecoveryPoint> = runCatching {
        val encoded = points.get()
        require(encoded.length <= 65_536)
        json.decodeFromString<List<RecoveryPoint>>(encoded)
    }.getOrDefault(emptyList()).filter {
        it.key.matches(Regex("[a-f0-9]{64}")) &&
            it.positionMs >= 0 &&
            nowMillis() - it.savedAt in 0..RETENTION
    }.distinctBy { it.key }.take(100)

    fun point(key: String, incognito: Boolean): RecoveryPoint? =
        if (incognito) privatePoints[key] else stored().firstOrNull { it.key == key }

    fun save(key: String, positionMs: Long, incognito: Boolean) {
        val point = RecoveryPoint(key, positionMs.coerceAtLeast(0), nowMillis())
        if (incognito) {
            privatePoints[key] = point
            if (privatePoints.size > 100) privatePoints.remove(privatePoints.keys.first())
        } else {
            points.set(json.encodeToString(listOf(point) + stored().filterNot { it.key == key }.take(99)))
        }
    }

    fun remove(key: String, incognito: Boolean) {
        if (incognito) {
            privatePoints.remove(key)
        } else {
            points.set(
                json.encodeToString(
                    stored().filterNot {
                        it.key == key
                    },
                ),
            )
        }
    }

    fun clear() {
        points.set("[]")
        privatePoints.clear()
    }

    internal fun traceGeneration(): Long = TRACE_GENERATION.get()

    /** Invoked on the writer dispatcher. Old queued writes cannot resurrect deleted history. */
    internal fun mergeTrace(chunk: AttentionTraceChunk, generation: Long): Boolean = synchronized(TRACE_LOCK) {
        if (generation != TRACE_GENERATION.get()) return@synchronized false
        if (chunk.incognito) return@synchronized true
        require(chunk.key.matches(Regex("[a-f0-9]{64}")))
        val preference = store.getString(TRACE_PREFIX + chunk.key, "")
        val previous = AttentionTraceCodec.decode(runCatching { preference.get() }.getOrDefault("")) ?: ByteArray(0)
        val merged = AttentionTraceCodec.merge(previous, chunk.masks)
        preference.set(AttentionTraceCodec.encode(merged))
        true
    }

    /** Future history UI can consume these sampled masks without accessing the camera. */
    fun trace(key: String, incognito: Boolean = false): ByteArray? {
        if (incognito || !key.matches(Regex("[a-f0-9]{64}"))) return null
        return synchronized(TRACE_LOCK) {
            AttentionTraceCodec.decode(runCatching { store.getString(TRACE_PREFIX + key, "").get() }.getOrDefault(""))
        }
    }

    /** Settings-only, off-thread summary. Never scanned during playback or when disabled. */
    fun traceCount(): Int = synchronized(TRACE_LOCK) {
        store.getAll().count { (key, value) ->
            key.startsWith(TRACE_PREFIX) &&
                value is String &&
                AttentionTraceCodec.decode(value)?.any { it.toInt() != 0 } == true
        }
    }

    /** Called off the UI thread by an explicit, confirmed deletion. */
    fun clearTraces() = synchronized(TRACE_LOCK) {
        TRACE_GENERATION.incrementAndGet()
        store.getAll().keys.filter { it.startsWith(TRACE_PREFIX) }.forEach { store.getString(it).delete() }
    }

    fun defaults() {
        listOf(enabled, away, closed, resumeAway, resumeClosed).forEach { it.delete() }
        listOf(recovery, awaySeconds, closedSeconds, returnMillis, marginSeconds, waitSeconds).forEach { it.delete() }
        sensitivity.delete()
        mode.delete()
    }

    companion object {
        private const val TRACE_PREFIX = "attention_v1_coverage_"
        private val TRACE_LOCK = Any()
        private val TRACE_GENERATION = AtomicLong()
        private const val RETENTION = 30L * 24 * 60 * 60 * 1_000
        fun key(vararg components: String): String = MessageDigest.getInstance("SHA-256")
            .digest(components.joinToString("") { "${it.length}:$it" }.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}

@Serializable
data class RecoveryPoint(val key: String, val positionMs: Long, val savedAt: Long)

/** UI requests only; no camera/model/runtime is created by this object. */
object AttentionUi {
    private val mutableSettings = MutableStateFlow(false)
    val settings = mutableSettings.asStateFlow()
    val suspended = MutableStateFlow(false)
    val configuring = MutableStateFlow(false)
    val verifying = MutableStateFlow(false)
    val calibrationRequests = MutableStateFlow(0L)
    val clearRequests = MutableStateFlow(0L)
    val preview = MutableStateFlow<androidx.camera.core.Preview.SurfaceProvider?>(null)
    val status = MutableStateFlow(AttentionStatus())
    val traceStorageError = MutableStateFlow(false)
    val traceChanges = MutableStateFlow(0L)
    var recover: () -> Unit = {}
    fun openSettings() {
        mutableSettings.value = true
    }
    fun closeSettings() {
        mutableSettings.value = false
    }
}

data class AttentionStatus(
    val label: Int = it.lorelore.nyanime.attention.core.R.string.attention_not_playing,
    val key: String? = null,
    val pointMs: Long? = null,
    val calibrating: Boolean = false,
    val calibrated: Boolean = false,
)
