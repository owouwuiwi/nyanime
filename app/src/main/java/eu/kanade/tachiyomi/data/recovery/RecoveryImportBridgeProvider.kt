package eu.kanade.tachiyomi.data.recovery

/** An independent graph can write after a reinstall without relaxing the export-only driver. */
class RecoveryImportBridgeProvider : RecoveryBridgeProvider() {
    override val imports = true
}

class RecoveryRestoreService : android.app.Service() {
    override fun onBind(intent: android.content.Intent): android.os.IBinder = android.os.Binder()
}
