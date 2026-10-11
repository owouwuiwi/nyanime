package eu.kanade.tachiyomi.telegram

import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import eu.kanade.tachiyomi.data.telegram.TelegramEngineService
import org.json.JSONObject
import java.io.File
import java.io.ObjectInputStream
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Exercises real Binder/Handler recycling without reading an account or requesting any login code. */
class TelegramIpcProbe : Instrumentation() {
    private var prepareCloudSetup = false
    private var checkPhoneNumbers = false
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        prepareCloudSetup = arguments?.getString("prepare_cloud_setup") == "true"
        checkPhoneNumbers = arguments?.getString("phone_numbers") == "true"
        start()
    }

    override fun onStart() {
        val output = Bundle()
        if (checkPhoneNumbers) {
            // Harmless fixtures only: exercises packaged metadata after R8, without changing the account.
            try {
                // R8 staticizes the Kotlin object and removes INSTANCE. Invoke the installed entry point,
                // rather than retaining unoptimized production methods solely for this test helper.
                val owner = Class.forName("nyanime.telegram.TelegramPhoneNumbers", true, targetContext.classLoader)
                val method = owner.getDeclaredMethod("normalize", String::class.java, String::class.java).apply {
                    isAccessible = true
                }
                val receiver = if (Modifier.isStatic(method.modifiers)) null else owner.getField("INSTANCE").get(null)
                fun normalize(
                    number: String,
                    region: String,
                ): String = method.invoke(receiver, number, region) as String
                check(normalize("312 345 6789", "IT") == "+393123456789")
                check(normalize("02 1234 5678", "IT") == "+390212345678")
                check(normalize("07400 123456", "GB") == "+447400123456")
                check(normalize("+1 (202) 555-0123", "IT") == "+12025550123")
                check(normalize("0044 7400 123456", "IT") == "+447400123456")
                listOf("IT", "US", "GB", "CA").forEach { region ->
                    val path = "com/google/i18n/phonenumbers/data/PhoneNumberMetadataProto_$region"
                    ObjectInputStream(checkNotNull(targetContext.classLoader.getResourceAsStream(path))).use {
                        check(it.readInt() > 0)
                    }
                }
                output.putString("telegram_phone_numbers", "passed")
                output.putInt("phone_examples", 5)
                output.putInt("packaged_metadata", 4)
                finish(Activity.RESULT_OK, output)
            } catch (error: Exception) {
                output.putString("telegram_phone_numbers", "failed")
                output.putString("failure_type", error.javaClass.simpleName)
                finish(Activity.RESULT_CANCELED, output)
            }
            return
        }
        if (prepareCloudSetup) {
            // Re-enter first-device setup using the authorized account, without clearing
            // the session, archives, library, device identity, credentials or backup copies.
            try {
                val prefs = targetContext.getSharedPreferences(
                    targetContext.packageName + "_preferences",
                    Context.MODE_PRIVATE,
                )
                check(prefs.getBoolean("__APP_STATE_cloud_backup_enabled", false))
                val database = SQLiteDatabase.openDatabase(
                    File(targetContext.noBackupFilesDir, "cloud.sqlite").path,
                    null,
                    SQLiteDatabase.OPEN_READWRITE,
                )
                database.use { db ->
                    fun state(key: String): String? = db.query(
                        "state",
                        arrayOf("value"),
                        "key=?",
                        arrayOf(key),
                        null,
                        null,
                        null,
                    ).use { if (it.moveToFirst()) it.getString(0) else null }
                    val account = checkNotNull(state("account"))
                    check(state("setup:$account") == "complete")
                    check(state("archive:$account") != null && state("pending") == null)
                    check(
                        prefs.edit().putBoolean("__APP_STATE_cloud_backup_enabled", false)
                            .putBoolean("__APP_STATE_cloud_introduction_complete", false).commit(),
                    )
                    db.delete("state", "key=?", arrayOf("setup:$account"))
                }
                output.putString("cloud_onboarding", "prepared")
                finish(Activity.RESULT_OK, output)
            } catch (error: Exception) {
                output.putString("cloud_onboarding", "failed")
                output.putString("failure_type", error.javaClass.simpleName)
                finish(Activity.RESULT_CANCELED, output)
            }
            return
        }
        val count = 50
        val connected = CountDownLatch(1)
        val replies = CountDownLatch(count)
        val received = ConcurrentHashMap.newKeySet<String>()
        val failure = AtomicReference<String?>(null)
        val version = AtomicReference<String?>(null)
        val service = AtomicReference<Messenger?>(null)
        val incoming = Messenger(
            Handler(Looper.getMainLooper()) { message ->
                val raw = message.data.getString("json")
                if (raw != null) {
                    val event = JSONObject(raw)
                    val id = event.optString("@extra")
                    if (id.startsWith("ipc-probe-") && received.add(id)) {
                        if (event.optString("@type") == "optionValueString") {
                            version.set(event.optString("value"))
                        } else {
                            failure.set("Unexpected engine response")
                        }
                        replies.countDown()
                    }
                }
                true
            },
        )
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service.set(Messenger(binder))
                connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) {
                failure.set("Engine disconnected")
            }
        }
        var bound = false
        try {
            bound = targetContext.bindService(
                Intent(targetContext, TelegramEngineService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
            check(bound && connected.await(15, TimeUnit.SECONDS))
            val engine = checkNotNull(service.get())
            engine.send(Message.obtain(null, TelegramEngineService.ATTACH).apply { replyTo = incoming })
            repeat(count) { index ->
                val json = JSONObject().put("@type", "getOption").put("name", "version")
                    .put("@extra", "ipc-probe-$index").toString()
                val request = Message.obtain(null, TelegramEngineService.REQUEST).apply {
                    data = Bundle().apply { putString("json", json) }
                    replyTo = incoming
                }
                engine.send(request)
                request.recycle()
            }
            check(replies.await(15, TimeUnit.SECONDS)) { "Engine lost a request" }
            check(failure.get() == null && version.get() == "1.8.67")
            output.putString("telegram_ipc", "passed")
            output.putInt("confirmed_requests", received.size)
            output.putString("engine_version", version.get())
            finish(Activity.RESULT_OK, output)
        } catch (error: Exception) {
            output.putString("telegram_ipc", "failed")
            output.putInt("confirmed_requests", received.size)
            output.putString("failure_type", error.javaClass.simpleName)
            finish(Activity.RESULT_CANCELED, output)
        } finally {
            runCatching {
                service.get()?.send(Message.obtain(null, TelegramEngineService.DETACH).apply { replyTo = incoming })
            }
            if (bound) runCatching { targetContext.unbindService(connection) }
        }
    }
}
