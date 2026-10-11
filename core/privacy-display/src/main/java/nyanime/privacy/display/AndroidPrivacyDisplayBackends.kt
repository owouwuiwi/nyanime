package nyanime.privacy.display

import android.os.Build

/** Adapter composition point. Adding a manufacturer does not change application screens or policy. */
object AndroidPrivacyDisplayBackends {
    fun create(
        device: PrivacyDisplayDevice = PrivacyDisplayDevice(Build.MANUFACTURER, Build.MODEL, Build.VERSION.SDK_INT),
    ): PrivacyDisplayBackend<AndroidPrivacyDisplayTarget> = SamsungPrivacyDisplayBackend.create(device)
}
