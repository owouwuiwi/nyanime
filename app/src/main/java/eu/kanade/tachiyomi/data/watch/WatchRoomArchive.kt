package eu.kanade.tachiyomi.data.watch

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import eu.kanade.tachiyomi.data.reading.ReadingEdit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A room is resumable only until its invitation expires; it never enters Android or Nyanime backups. */
internal class WatchRoomArchive(context: Context) {
    @Serializable
    data class Saved(
        val invite: WatchInvite,
        val identity: String,
        val name: String,
        val edits: List<ReadingEdit> = emptyList(),
        val live: nyanime.tv.api.TvRoomSelection? = null,
        val reading: eu.kanade.tachiyomi.data.reading.ReadingSessionEnvelope? = null,
        val personalReading: Boolean = false,
        val sequenceEpoch: Long = 0,
    )

    private val file = AtomicFile(File(context.noBackupFilesDir, "watch-room.pending"))
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writes = Channel<Pair<Long, Saved>>(Channel.CONFLATED)
    private var generation = 0L
    private var saved: Saved? = null
    private val mutableSaveFailed = MutableStateFlow(false)
    val saveFailed = mutableSaveFailed.asStateFlow()
    private val alias = "nyanime.watch.room.v1"

    init {
        scope.launch {
            for ((revision, snapshot) in writes) {
                val failed = runCatching {
                    synchronized(lock) {
                        if (revision == generation) write(snapshot)
                    }
                }.isFailure
                mutableSaveFailed.value = failed
                if (failed) {
                    delay(5000)
                    writes.trySend(revision to snapshot)
                }
            }
        }
    }

    fun load(): Saved? = synchronized(lock) {
        saved?.let { cached ->
            if (runCatching { cached.invite.validate(System.currentTimeMillis()) }.isSuccess) {
                return@synchronized cached
            }
            clearLocked()
        }
        if (!file.baseFile.exists()) return@synchronized null
        val result = runCatching {
            val bytes = file.readFully()
            require(bytes.size in 29..8_000_000)
            val decoded = watchJson.decodeFromString<Saved>(open(bytes).decodeToString())
            decoded.invite.validate(System.currentTimeMillis())
            require(decoded.identity.matches(Regex("[0-9a-f]{64}")))
            require(decoded.name.length <= 32 && decoded.edits.size <= 8192)
            require(decoded.edits.all(ReadingEdit::valid))
            require(decoded.live == null || decoded.live.valid())
            require(decoded.reading == null || decoded.reading.valid())
            require(decoded.sequenceEpoch in 0..Int.MAX_VALUE.toLong())
            decoded
        }.getOrNull()
        if (result == null) clearLocked() else saved = result
        result
    }

    fun begin(invite: WatchInvite, identity: WatchIdentity, name: String) = synchronized(lock) {
        val secret = identity.sessionSecret()
        val previous = load()
        val edits = previous?.edits?.takeIf {
            previous.invite.secret == invite.secret && previous.identity == secret.watchHex()
        }.orEmpty()
        val live = previous?.live?.takeIf {
            previous.invite.secret == invite.secret &&
                previous.identity == secret.watchHex()
        }
        val reading = previous?.reading?.takeIf {
            previous.invite.secret == invite.secret && previous.identity == secret.watchHex()
        }
        val personal = previous?.personalReading == true &&
            previous.invite.secret == invite.secret &&
            previous.identity == secret.watchHex()
        val epoch = previous?.sequenceEpoch?.takeIf {
            previous.invite.secret == invite.secret && previous.identity == secret.watchHex()
        } ?: 0
        saved = Saved(invite, secret.watchHex(), name, edits, live, reading, personal, epoch)
        secret.fill(0)
        generation++
        write(saved!!)
        mutableSaveFailed.value = false
    }

    fun update(edits: List<ReadingEdit>) = synchronized(lock) {
        val current = saved ?: return@synchronized
        if (edits.size > 8192) return@synchronized
        saved = current.copy(edits = edits.toList())
        writes.trySend(generation to saved!!)
    }

    fun updateLive(value: nyanime.tv.api.TvRoomSelection?) = synchronized(lock) {
        val current = saved ?: return@synchronized
        if (value != null && !value.valid()) return@synchronized
        if (current.live == value) return@synchronized
        saved = current.copy(live = value)
        writes.trySend(generation to saved!!)
    }

    fun updateReading(value: eu.kanade.tachiyomi.data.reading.ReadingSessionEnvelope) = synchronized(lock) {
        val current = saved ?: return@synchronized
        if (!value.valid() || current.reading == value) return@synchronized
        saved = current.copy(reading = value)
        writes.trySend(generation to saved!!)
    }

    fun updatePersonalReading(value: Boolean) = synchronized(lock) {
        val current = saved ?: return@synchronized
        if (current.personalReading == value) return@synchronized
        saved = current.copy(personalReading = value)
        writes.trySend(generation to saved!!)
    }

    /** Reserve a fresh sequence range before reconnecting with the same encrypted identity. */
    fun reserveSequenceEpoch(): Long = synchronized(lock) {
        val current = saved ?: return@synchronized 0L
        require(current.sequenceEpoch < Int.MAX_VALUE)
        val next = current.copy(sequenceEpoch = current.sequenceEpoch + 1)
        generation++
        write(next)
        saved = next
        next.sequenceEpoch shl 32
    }

    fun clear() = synchronized(lock) { clearLocked() }

    internal fun exportMigration(): String? = load()?.let { watchJson.encodeToString(Saved.serializer(), it) }
    internal fun importMigration(json: String) = synchronized(lock) {
        val snapshot = watchJson.decodeFromString<Saved>(json)
        snapshot.invite.validate(System.currentTimeMillis())
        require(snapshot.identity.matches(Regex("[0-9a-f]{64}")) && snapshot.edits.size <= 8192)
        require(snapshot.name.length <= 32 && snapshot.edits.all(ReadingEdit::valid))
        require(snapshot.live == null || snapshot.live.valid())
        require(snapshot.reading == null || snapshot.reading.valid())
        require(snapshot.sequenceEpoch in 0..Int.MAX_VALUE.toLong())
        write(snapshot)
    }

    private fun clearLocked() {
        generation++
        saved = null
        mutableSaveFailed.value = false
        file.delete()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias) }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    private fun write(snapshot: Saved) {
        val bytes = watchJson.encodeToString(Saved.serializer(), snapshot).toByteArray()
        require(bytes.size <= 7_900_000)
        val sealed = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, key())
            iv + doFinal(bytes)
        }
        val output = file.startWrite()
        try {
            output.write(sealed)
            file.finishWrite(output)
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
    }

    private fun open(bytes: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOf(12)))
        doFinal(bytes.copyOfRange(12, bytes.size))
    }
}
