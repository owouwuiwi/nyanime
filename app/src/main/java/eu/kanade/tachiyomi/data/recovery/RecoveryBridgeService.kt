package eu.kanade.tachiyomi.data.recovery

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder

/** Bound only during a user operation. All data authorization is enforced by the provider. */
class RecoveryBridgeService : Service() {
    private val binder = Binder()
    override fun onBind(intent: Intent): IBinder = binder
}
