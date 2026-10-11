package nyanime.telegram

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.drinkless.tdlib.JsonClient

/** Exercises the real JNI on Android without an API account, user database or launcher activity. */
class TelegramNativeProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var clientId = 0
        try {
            val silence = tdRequest("setLogStream", "log_stream" to tdRequest("logStreamEmpty"))
            JsonClient.execute(silence.toString())
            val request = tdRequest("getOption", "name" to JsonPrimitive("version"))
            val version = telegramJson.parseToJsonElement(JsonClient.execute(request.toString())).jsonObject
            check(version.type() == "optionValueString" && version.text("value").isNotBlank())
            clientId = JsonClient.createClientId()
            check(clientId > 0)
            JsonClient.send(clientId, tdRequest("getAuthorizationState").toString())
            awaitAuth(clientId, "authorizationStateWaitTdlibParameters")
            // No parameters are supplied: TDLib never opens a user database or authenticates.
            JsonClient.send(clientId, tdRequest("close").toString())
            awaitAuth(clientId, "authorizationStateClosed")
            clientId = 0
            result.putString("native_probe", "passed")
            result.putString("engine_version", version.text("value"))
            finish(Activity.RESULT_OK, result)
        } catch (_: Throwable) {
            // Never include raw native events or account data in test output.
            result.putString("native_probe", "failed")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            if (clientId > 0) runCatching { JsonClient.send(clientId, tdRequest("close").toString()) }
        }
    }

    private fun awaitAuth(client: Int, expected: String) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val raw = JsonClient.receive(0.5) ?: continue
            val event = telegramJson.parseToJsonElement(raw).jsonObject
            if (event.number("@client_id") != client.toLong()) continue
            val auth = event.objectOrNull("authorization_state")
                ?: event.takeIf { it.type().startsWith("authorizationState") }
            if (auth?.type() == expected) return
        }
        error("Native authorization state timed out")
    }
}
