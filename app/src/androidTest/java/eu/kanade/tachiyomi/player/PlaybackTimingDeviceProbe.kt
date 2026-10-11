package eu.kanade.tachiyomi.player

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.ui.player.PlayerActivity
import eu.kanade.tachiyomi.ui.player.PlayerSession
import eu.kanade.tachiyomi.ui.player.session.PlaybackSessions
import `is`.xyz.mpv.MPVLib
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.abs

/** Opt-in device probe. Local arguments select content; reports contain numeric timing only. */
class PlaybackTimingDeviceProbe : Instrumentation() {
    private var options = Bundle()

    override fun onCreate(arguments: Bundle?) {
        options = arguments ?: Bundle()
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val output = Bundle()
        try {
            if (options.getString("mode") == "inspect") {
                inspect()
            } else {
                runBlocking { exercise() }
            }
            output.putString("stream", "Playback probe completed\n")
            finish(Activity.RESULT_OK, output)
        } catch (error: Throwable) {
            // Exception messages from a source can contain authenticated URLs.
            val location = error.stackTrace.firstOrNull { it.className.contains("PlaybackTimingDeviceProbe") }
            output.putString(
                "stream",
                "Playback probe failed: ${error.javaClass.simpleName} at ${location?.lineNumber}\n",
            )
            finish(Activity.RESULT_CANCELED, output)
        }
    }

    private fun inspect() {
        val title = requireNotNull(options.getString("title"))
        SQLiteDatabase.openDatabase(
            targetContext.getDatabasePath("tachiyomi.animedb").path,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { database ->
            database.rawQuery(
                "SELECT e.anime_id,e._id,e.episode_number,e.last_second_seen,e.seen " +
                    "FROM episodes e JOIN animes a ON a._id=e.anime_id " +
                    "LEFT JOIN animehistory h ON h.episode_id=e._id " +
                    "WHERE a.title LIKE ? ORDER BY COALESCE(h.last_seen,0) DESC,e.source_order LIMIT 15",
                arrayOf("%$title%"),
            ).use { rows ->
                while (rows.moveToNext()) {
                    report(
                        JSONObject().put("animeId", rows.getLong(0)).put("episodeId", rows.getLong(1))
                            .put("episode", rows.getDouble(2)).put("resumeMs", rows.getLong(3))
                            .put("seen", rows.getInt(4)),
                    )
                }
            }
        }
    }

    private suspend fun exercise() {
        val animeId = requireNotNull(options.getString("animeId")).toLong()
        val episodeId = requireNotNull(options.getString("episodeId")).toLong()
        val base = Injekt.get<BasePreferences>().incognitoMode()
        val tracking = Injekt.get<TrackPreferences>().autoUpdateTrack()
        val originalIncognito = base.get()
        val originalTracking = tracking.get()
        var session: PlayerSession? = null
        base.set(true)
        tracking.set(false)
        try {
            targetContext.startActivity(
                PlayerActivity.newIntent(targetContext, animeId, episodeId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            session = withTimeout(90_000) {
                var ready: PlayerSession? = null
                while (ready == null) {
                    runOnMainSync {
                        PlaybackSessions.current.value?.takeIf { it.available }?.let {
                            session = it
                            require(!it.viewModel.watchTogether.active)
                            it.viewModel.remoteProgressOwned = true
                            if (!it.viewModel.isLoadingEpisode.value && !it.viewModel.isLoadingTracks.value) ready = it
                        }
                    }
                    delay(50)
                }
                ready
            }
            val active = requireNotNull(session)
            var initial = 0
            runOnMainSync {
                active.viewModel.remoteProgressOwned = true
                initial = active.viewModel.pos.value.toInt()
            }
            sample(active, "startup", 40)
            listOf(10, -10, 60, -45).forEachIndexed { index, offset ->
                runOnMainSync { active.viewModel.seekBy(offset) }
                sample(active, "seek-$index", 32)
            }
            runOnMainSync { active.viewModel.seekTo(initial) }
            sample(active, "return", 24)
        } finally {
            runOnMainSync { session?.takeIf { it.available }?.close() }
            delay(1_000)
            base.set(originalIncognito)
            tracking.set(originalTracking)
        }
    }

    private suspend fun sample(session: PlayerSession, phase: String, count: Int) {
        repeat(count) {
            var timing: JSONObject? = null
            runOnMainSync {
                check(session.available && !session.player.isExiting)
                session.viewModel.remoteProgressOwned = true
                val position = MPVLib.getPropertyDouble("time-pos")
                val audio = MPVLib.getPropertyDouble("audio-pts")
                val drift = MPVLib.getPropertyDouble("avsync")
                val nativeTracks = MPVLib.getPropertyString("track-list")?.let(::JSONArray)
                timing = JSONObject().put("phase", phase).put("position", position)
                    .put("audioPts", audio).put("avsync", drift)
                    .put("paused", MPVLib.getPropertyBoolean("pause"))
                    .put("buffering", MPVLib.getPropertyBoolean("paused-for-cache"))
                    .put("seeking", MPVLib.getPropertyBoolean("seeking"))
                    .put("cacheSeconds", MPVLib.getPropertyDouble("demuxer-cache-duration"))
                    .put("droppedFrames", MPVLib.getPropertyInt("frame-drop-count"))
                    .put("audioId", session.viewModel.selectedAudio.value)
                    .put("audioChoices", session.viewModel.audioTracks.value.size)
                    .put("subtitleChoices", session.viewModel.subtitleTracks.value.size)
                    .put("nativeTrackCount", nativeTracks?.length())
                    .put("within100ms", drift?.let { abs(it) <= 0.1 })
            }
            report(requireNotNull(timing))
            delay(250)
        }
    }

    private fun report(value: JSONObject) = sendStatus(
        0,
        Bundle().apply { putString("stream", "$value\n") },
    )
}
