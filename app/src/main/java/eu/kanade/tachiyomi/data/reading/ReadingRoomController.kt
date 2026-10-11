package eu.kanade.tachiyomi.data.reading

import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.watch.RoomText
import eu.kanade.tachiyomi.data.watch.WatchRoomState
import eu.kanade.tachiyomi.data.watch.watchHex
import eu.kanade.tachiyomi.data.watch.watchRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A reading channel in the existing room, with no player reference and no second transport. */
class ReadingRoomController(
    private val scope: CoroutineScope,
    private val text: RoomText,
    private val now: () -> Long,
    private val onDocumentChanged: (List<ReadingEdit>) -> Unit = {},
    private val send: (ReadingEnvelope, String) -> Unit,
) {
    private val mutableState = MutableStateFlow(ReadingRoomState())
    val state = mutableState.asStateFlow()
    private var ticker: Job? = null
    private var local = ReadingPeer(text(R.string.room_reader))
    private var lastRoster = 0L
    private var operation = 0L
    private var revision = 0L
    private var presenceRevision = 0L
    private var lastRosterRevision = -1L
    private var evictedRevision = -1L
    private val presenceRevisions = mutableMapOf<String, Long>()
    private val rates = mutableMapOf<String, ArrayDeque<Long>>()
    private val accepted = mutableMapOf<String, Long>()
    private val acceptedErrors = mutableMapOf<String, String>()
    private val peers = linkedMapOf<String, Pair<ReadingPeer, Long>>()
    private var allowed = emptySet<String>()
    private var lastPresence = -5000L
    private var lastSend = -5000L
    private var lastBoardSend = -5000L
    private var rosterDirty = false
    private var sentPeer: ReadingPeer? = null
    private val pageReferences = linkedMapOf<String, ReadingPosition>()
    private var crdt = ReadingCrdt()
    private var crdtMode = false
    private val outgoing = ArrayDeque<Pair<ReadingEnvelope, String>>()
    private val syncOffsets = mutableMapOf<String, Int>()
    private var lastCrdtSync = -10_000L
    private var lastCrdtSend = -10_000L
    private var syncCursor = 0

    fun roomChanged(room: WatchRoomState) {
        val previous = state.value
        if (!room.active) {
            ticker?.cancel()
            ticker = null
            peers.clear()
            accepted.clear()
            acceptedErrors.clear()
            pageReferences.clear()
            crdt = ReadingCrdt()
            crdtMode = false
            outgoing.clear()
            syncOffsets.clear()
            syncCursor = 0
            operation = 0
            revision = 0
            presenceRevision = 0
            lastRosterRevision = -1
            evictedRevision = -1
            presenceRevisions.clear()
            rates.clear()
            sentPeer = null
            mutableState.value = ReadingRoomState()
            return
        }
        if (previous.active && previous.localId != room.localMemberId) {
            roomChanged(WatchRoomState())
        }
        allowed = room.members.map { it.id }.toSet() + room.localMemberId
        val wasCrdtMode = crdtMode
        crdtMode = room.readingVersion >= 2
        if (room.host) crdt.hostId = room.localMemberId
        local = local.copy(name = room.members.firstOrNull { it.id == room.localMemberId }?.name ?: local.name)
        mutableState.value = state.value.copy(
            active = true,
            host = room.host,
            localId = room.localMemberId,
            invite = room.invite,
            relayCount = room.relayCount,
            supported = room.host || room.readingSupported,
            crdtEnabled = crdtMode,
        )
        if (!wasCrdtMode && crdtMode) {
            crdt.all().map { it.page }.distinctBy { it.pageKey }.forEach(::updateCrdtBoard)
        }
        if (ticker == null) {
            lastRoster = now()
            lastPresence = -5000
            lastSend = -5000
            lastBoardSend = -5000
            rosterDirty = true
            ticker = scope.launch {
                while (state.value.active) {
                    tick()
                    delay(250)
                }
            }
        }
    }

    fun position(position: ReadingPosition?, reading: Boolean) {
        if (position != null && !position.valid()) return
        local = local.copy(position = position, reading = reading)
        if (crdtMode && position != null) updateCrdtBoard(position)
    }

    fun suspendReading() {
        local = local.copy(reading = false)
    }

    fun clearNotice() {
        mutableState.value = state.value.copy(notice = "")
    }

    fun draw(page: ReadingPosition, stroke: ReadingStroke): Boolean = if (crdtMode) {
        applyLocal(
            ReadingEdit(stroke.id, stroke.author, page, crdt.nextClock(), ReadingEditKind.Stroke, stroke = stroke),
        )
    } else {
        enqueue(ReadingEnvelope(kind = ReadingKind.Ink, page = page, stroke = stroke, operation = operation + 1))
    }

    fun addNote(page: ReadingPosition, x: Int, y: Int, text: String): Boolean {
        if (!crdtMode) return false
        val id = watchRandom(16).watchHex()
        val author = state.value.localId
        return applyLocal(
            ReadingEdit(
                id,
                author,
                page,
                crdt.nextClock(),
                ReadingEditKind.Note,
                note = ReadingNote(id, author, x, y, text.trim()),
            ),
        )
    }

    fun editNote(page: ReadingPosition, target: String, text: String): Boolean {
        if (!crdtMode || crdt.creator(page, target)?.author != state.value.localId) return false
        return applyLocal(
            ReadingEdit(
                watchRandom(16).watchHex(),
                state.value.localId,
                page,
                crdt.nextClock(),
                ReadingEditKind.NoteText,
                target = target,
                text = text.trim(),
            ),
        )
    }

    fun setVisible(page: ReadingPosition, target: String, visible: Boolean): Boolean {
        if (!crdtMode) return false
        val creator = crdt.creator(page, target) ?: return false
        if (creator.author != state.value.localId && !state.value.host) return false
        return applyLocal(
            ReadingEdit(
                watchRandom(16).watchHex(),
                state.value.localId,
                page,
                crdt.nextClock(),
                ReadingEditKind.Visibility,
                target = target,
                visible = visible,
            ),
        )
    }

    fun undo(page: ReadingPosition) {
        if (crdtMode) {
            crdt.strokes(page).lastOrNull { it.author == state.value.localId }?.let {
                setVisible(page, it.id, false)
            }
            return
        }
        val last = state.value.strokes(page).lastOrNull { it.author == state.value.localId } ?: return
        enqueue(ReadingEnvelope(kind = ReadingKind.Ink, page = page, erase = last.id, operation = operation + 1))
    }

    fun clear(page: ReadingPosition) {
        if (crdtMode) {
            crdt.strokes(page).filter { state.value.host || it.author == state.value.localId }
                .forEach { setVisible(page, it.id, false) }
            crdt.notes(page).filter { state.value.host || it.author == state.value.localId }
                .forEach { setVisible(page, it.id, false) }
            return
        }
        enqueue(ReadingEnvelope(kind = ReadingKind.Ink, page = page, clear = true, operation = operation + 1))
    }

    fun history(): List<ReadingEdit> = if (crdtMode) crdt.history() else emptyList()

    fun matchingPage(chapterKey: String, fingerprint: String): ReadingPosition? =
        pageReferences.values.filter {
            it.chapterKey == chapterKey && ReadingPageFingerprint.matches(fingerprint, it.fingerprint)
        }.distinctBy { it.pageKey }.singleOrNull()

    fun restore(edits: List<ReadingEdit>) {
        if (!state.value.active) return
        val pages = edits.take(8192).filter(crdt::apply).map { it.page }.distinctBy { it.pageKey }
        if (crdtMode) pages.forEach(::updateCrdtBoard)
        lastCrdtSync = -10_000L
    }

    private fun applyLocal(edit: ReadingEdit): Boolean {
        if (!state.value.active || !state.value.supported || edit.author != state.value.localId) {
            return false
        }
        if (!crdt.apply(edit)) {
            mutableState.value =
                state.value.copy(notice = text(R.string.room_annotation_limit))
            return false
        }
        updateCrdtBoard(edit.page)
        onDocumentChanged(crdt.all())
        if (outgoing.size < 256) {
            outgoing.addLast(
                ReadingEnvelope(version = 2, kind = ReadingKind.Crdt, page = edit.page, edits = listOf(edit)) to "",
            )
        }
        return true
    }

    private fun updateCrdtBoard(page: ReadingPosition) {
        if (!crdtMode) return
        cache(page, ReadingBoard(++revision, crdt.strokes(page), crdt.notes(page)))
    }

    private fun enqueue(value: ReadingEnvelope): Boolean {
        if (!state.value.active || !state.value.supported || !value.valid()) return false
        if (state.value.pending.size >= 16) {
            mutableState.value =
                state.value.copy(
                    notice = text(R.string.room_pending_sketches_hint),
                )
            return false
        }
        operation++
        if (state.value.host) {
            ink(state.value.localId, value)
        } else {
            mutableState.value = state.value.copy(pending = state.value.pending + value)
            if (state.value.pending.size == 1) send(value, "")
        }
        return true
    }

    fun receive(sender: String, envelope: ReadingEnvelope, owner: Boolean) {
        if (!state.value.active || !envelope.valid()) return
        if (crdtMode && owner && crdt.hostId != sender) {
            crdt.hostId = sender
            pageReferences.values.toList().forEach(::updateCrdtBoard)
        }
        if (crdtMode && envelope.version == 2) {
            receiveCrdt(sender, envelope, owner)
            return
        }
        if (crdtMode && envelope.kind in setOf(ReadingKind.Ink, ReadingKind.Board)) return
        if (state.value.host) {
            if (sender !in allowed) return
            val rate = rates.getOrPut(sender) { ArrayDeque() }
            while (rate.isNotEmpty() && now() - rate.first() >= 1000) rate.removeFirst()
            if (rate.size >= 32) return
            rate.addLast(now())
            when (envelope.kind) {
                ReadingKind.Presence -> {
                    if (envelope.revision <= (presenceRevisions[sender] ?: -1)) return
                    presenceRevisions[sender] = envelope.revision
                    val previous = peers[sender]?.first
                    peers[sender] = envelope.peer!! to now()
                    mutableState.value = state.value.copy(members = state.value.members + (sender to envelope.peer))
                    rosterDirty = previous != envelope.peer || rosterDirty
                    if (previous?.position?.pageKey != envelope.peer.position?.pageKey) {
                        envelope.peer.position?.let { broadcastBoard(it, sender) }
                    }
                }
                ReadingKind.Ink -> ink(sender, envelope)
                else -> Unit
            }
        } else if (owner) {
            when (envelope.kind) {
                ReadingKind.Roster -> {
                    if (envelope.revision <= lastRosterRevision) return
                    lastRosterRevision = envelope.revision
                    lastRoster = now()
                    mutableState.value =
                        state.value.copy(
                            members = envelope.members,
                            connected = state.value.localId in envelope.members,
                        )
                }
                ReadingKind.Board -> {
                    val page = envelope.page!!
                    val old = state.value.boards[page.pageKey]
                    if (old == null && envelope.revision <= evictedRevision) return
                    if (old == null || envelope.revision >= old.revision) {
                        cache(page, ReadingBoard(envelope.revision, envelope.strokes))
                    }
                    val pending = state.value.pending
                    val head = pending.firstOrNull()
                    if (head != null &&
                        head.page?.pageKey == page.pageKey &&
                        envelope.acknowledgement >= head.operation
                    ) {
                        mutableState.value = state.value.copy(
                            pending = pending.drop(1),
                            notice = if (envelope.errorCode == "page_sketch_limit") {
                                text(R.string.room_page_sketch_limit)
                            } else {
                                envelope.error
                            },
                        )
                        state.value.pending.firstOrNull()?.let { send(it, "") }
                    }
                }
                else -> Unit
            }
        }
    }

    private fun receiveCrdt(sender: String, envelope: ReadingEnvelope, owner: Boolean) {
        if (state.value.host && sender !in allowed) return
        if (!state.value.host && !owner) return
        val page = envelope.page ?: return
        if (owner) crdt.hostId = sender
        when (envelope.kind) {
            ReadingKind.Crdt -> {
                val acceptedEdits = envelope.edits.filter { edit ->
                    !state.value.host || edit.author == sender
                }.filter(crdt::apply)
                if (acceptedEdits.isEmpty()) return
                updateCrdtBoard(page)
                onDocumentChanged(crdt.all())
                if (state.value.host && outgoing.size < 256) {
                    acceptedEdits.chunked(4).forEach { edits ->
                        outgoing.addLast(
                            ReadingEnvelope(version = 2, kind = ReadingKind.Crdt, page = page, edits = edits) to "",
                        )
                    }
                }
            }
            ReadingKind.Sync -> if (envelope.digest != crdt.digest(page)) {
                if (crdt.page(page).isEmpty()) {
                    if (outgoing.size < 256) {
                        outgoing.addLast(
                            ReadingEnvelope(
                                version = 2,
                                kind = ReadingKind.Sync,
                                page = page,
                                digest = crdt.digest(page),
                            ) to sender,
                        )
                    }
                } else {
                    queueFullSync(page, sender)
                }
            } else {
                syncOffsets.remove("$sender:${page.pageKey}")
            }
            else -> Unit
        }
    }

    private fun queueFullSync(page: ReadingPosition, target: String) {
        if (outgoing.size >= 224) return
        val key = "$target:${page.pageKey}"
        val all = crdt.page(page).sortedBy { it.id }
        val offset = (syncOffsets[key] ?: 0).coerceAtMost(all.size)
        val batch = all.drop(offset).take(128)
        syncOffsets[key] = if (offset + batch.size >= all.size) 0 else offset + batch.size
        batch.chunked(4).forEach { edits ->
            outgoing.addLast(
                ReadingEnvelope(version = 2, kind = ReadingKind.Crdt, page = page, edits = edits) to target,
            )
        }
    }

    private fun tick() {
        val time = now()
        if (crdtMode && state.value.relayCount > 0) {
            if (time - lastCrdtSync >= 5000) {
                val pages = (
                    listOfNotNull(local.position) +
                        state.value.members.values.mapNotNull { it.position } +
                        crdt.all().map { it.page }
                    ).distinctBy { it.pageKey }
                if (pages.isNotEmpty()) {
                    repeat(minOf(4, pages.size)) { offset ->
                        val page = pages[(syncCursor + offset) % pages.size]
                        outgoing.addLast(
                            ReadingEnvelope(
                                version = 2,
                                kind = ReadingKind.Sync,
                                page = page,
                                digest = crdt.digest(page),
                            ) to "",
                        )
                    }
                    syncCursor = (syncCursor + 4) % pages.size
                }
                lastCrdtSync = time
            }
            if (time - lastCrdtSend >= 250 && outgoing.isNotEmpty()) {
                val (envelope, target) = outgoing.removeFirst()
                send(envelope, target)
                lastCrdtSend = time
            }
        }
        if (state.value.host) {
            peers.entries.removeAll { (id, value) -> id !in allowed || time - value.second > 15000 }
            if (time - lastSend >= 2000 || ((sentPeer != local || rosterDirty) && time - lastSend >= 350)) {
                broadcastRoster()
                lastSend = time
                sentPeer = local
                rosterDirty = false
            }
            if (time - lastBoardSend >= 2000) {
                (peers.values.mapNotNull { it.first.position } + listOfNotNull(local.position))
                    .distinctBy {
                        it.pageKey
                    }.filter { it.pageKey in state.value.boards }.forEach { broadcastBoard(it) }
                lastBoardSend = time
            }
            mutableState.value = state.value.copy(connected = state.value.relayCount > 0)
        } else {
            if (time - lastPresence >= 3000 || (sentPeer != local && time - lastPresence >= 350)) {
                send(ReadingEnvelope(kind = ReadingKind.Presence, peer = local, revision = ++presenceRevision), "")
                sentPeer = local
                lastPresence = time
            }
            if (time - lastSend >= 1500) {
                state.value.pending.firstOrNull()?.let { send(it, "") }
                lastSend = time
            }
            if (time - lastRoster > 12000) mutableState.value = state.value.copy(connected = false)
        }
    }

    private fun broadcastRoster() {
        val members = linkedMapOf(state.value.localId to local)
        peers.filterKeys { it in allowed }.forEach { (id, peer) -> members[id] = peer.first }
        mutableState.value = state.value.copy(members = members)
        send(ReadingEnvelope(kind = ReadingKind.Roster, members = members, revision = ++revision), "")
    }

    private fun ink(sender: String, value: ReadingEnvelope) {
        val page = value.page!!
        val last = accepted[sender] ?: 0
        if (value.operation <= last) {
            broadcastBoard(
                page,
                sender,
                last,
                acceptedErrors[sender].orEmpty().takeIf {
                    last == value.operation
                }.orEmpty(),
            )
            return
        }
        // A single outstanding operation per member avoids silently dropping holes after packet loss.
        if (value.operation != last + 1) return
        val strokes = state.value.boards[page.pageKey]?.strokes.orEmpty().toMutableList()
        var error = ""
        when {
            value.stroke != null -> {
                if (value.stroke.author != sender) return
                if (strokes.size >= 12) {
                    error = text(R.string.room_page_sketch_limit)
                } else if (strokes.none { it.id == value.stroke.id }) {
                    strokes.add(value.stroke)
                }
            }
            value.erase.isNotEmpty() -> strokes.removeAll {
                it.id == value.erase &&
                    (it.author == sender || sender == state.value.localId)
            }
            value.clear -> strokes.removeAll { it.author == sender || sender == state.value.localId }
        }
        accepted[sender] = value.operation
        acceptedErrors[sender] = error
        revision++
        cache(page, ReadingBoard(revision, strokes))
        if (sender == state.value.localId) mutableState.value = state.value.copy(notice = error)
        broadcastBoard(page)
        broadcastBoard(page, sender, value.operation, error)
    }

    private fun cache(page: ReadingPosition, board: ReadingBoard) {
        pageReferences.remove(page.pageKey)
        pageReferences[page.pageKey] = page
        val boards = state.value.boards.toMutableMap()
        boards[page.pageKey] = board
        while (pageReferences.size > (if (crdtMode) 64 else 32)) {
            val key = pageReferences.keys.first()
            pageReferences.remove(key)
            if (!state.value.host) evictedRevision = maxOf(evictedRevision, boards[key]?.revision ?: 0)
            boards.remove(key)
        }
        mutableState.value = state.value.copy(boards = boards)
    }

    private fun broadcastBoard(page: ReadingPosition, target: String = "", ack: Long = 0, error: String = "") {
        val board = state.value.boards[page.pageKey] ?: ReadingBoard(revision)
        send(
            ReadingEnvelope(
                kind = ReadingKind.Board,
                page = page,
                revision = ++revision,
                strokes = board.strokes,
                acknowledgement = ack,
                error = error,
                errorCode = if (error.isNotBlank()) "page_sketch_limit" else "",
            ),
            target,
        )
    }
}
