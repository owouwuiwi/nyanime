package nyanime.privacy.display

import android.view.View
import java.lang.reflect.Method

data class PrivacyDisplayDevice(val manufacturer: String, val model: String, val sdk: Int) {
    val samsungPrivacyHardware: Boolean
        get() = sdk >= 36 && manufacturer.equals("samsung", ignoreCase = true) && model.startsWith("SM-S948")
}

/** Only API names/signatures are external technical facts. All coordination is our own. */
class SamsungPrivacyDisplayBackend private constructor(
    override val capability: PrivacyDisplayCapability,
    private val methods: SamsungPrivacyMethods?,
) : PrivacyDisplayBackend<AndroidPrivacyDisplayTarget> {
    override fun apply(
        target: AndroidPrivacyDisplayTarget,
        region: PrivacyRegion,
        previous: PrivacyRegion?,
    ): Result<PrivacyRegion?> = samsungApiCall {
        val api = checkNotNull(methods)
        val placement = target.place(region, panelExpansion, EDGE_CLEARANCE_PX) ?: return@samsungApiCall null
        val view = checkNotNull(target.currentView)
        api.apply(
            view,
            placement.displayRegion.copy(bounds = placement.localBounds),
            activate = previous == null || previous.cornerRadiusPx != region.cornerRadiusPx,
        )
        view.invalidate()
        placement.displayRegion
    }

    override fun clear(target: AndroidPrivacyDisplayTarget): Result<Unit> = samsungApiCall {
        try {
            target.currentView?.let { checkNotNull(methods).clear(it) }
        } finally {
            target.detach()
        }
    }

    companion object {
        // Observed S26 firmware expands by (-1, -1, +2, +2), then rejects out-of-panel regions.
        // Keep compensation in this adapter, never in screen geometry or content policy.
        private val panelExpansion = PrivacyPanelExpansion(1, 1, 2, 2)

        // SM-S948B / S948BXXS4AZHL probes: 13px requested inset is rejected, 14px is accepted.
        // Keep 16px after native expansion to include the observed transition rounding.
        private const val EDGE_CLEARANCE_PX = 16

        fun create(device: PrivacyDisplayDevice): SamsungPrivacyDisplayBackend {
            if (!device.samsungPrivacyHardware) {
                return SamsungPrivacyDisplayBackend(
                    PrivacyDisplayCapability.Unavailable(PrivacyUnavailableReason.HARDWARE),
                    null,
                )
            }
            val resolved = samsungApiCall { SamsungPrivacyMethods.resolve(View::class.java) }
            return SamsungPrivacyDisplayBackend(
                if (resolved.isSuccess) {
                    PrivacyDisplayCapability.Available
                } else {
                    PrivacyDisplayCapability.Unavailable(PrivacyUnavailableReason.FIRMWARE)
                },
                resolved.getOrNull(),
            )
        }
    }
}

/** Resolved once by the backend. No target or accessibility override is retained. */
internal class SamsungPrivacyMethods private constructor(
    private val enable: Method,
    private val position: Method,
    private val disable: Method,
) {
    fun apply(target: Any, region: PrivacyRegion, activate: Boolean = true) {
        if (activate) enable.invoke(target, region.cornerRadiusPx)
        with(region.bounds) { position.invoke(target, left, top, right, bottom) }
    }

    fun clear(target: Any) {
        disable.invoke(target)
    }

    companion object {
        fun resolve(owner: Class<*>): SamsungPrivacyMethods = SamsungPrivacyMethods(
            owner.getMethod("semSetPrivacyDisplayView", Float::class.javaPrimitiveType),
            owner.getMethod(
                "semSetPrivacyDisplayViewPosition",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ),
            owner.getMethod("semDisablePrivacyDisplayView"),
        )
    }
}

internal inline fun <T> samsungApiCall(operation: () -> T): Result<T> = try {
    Result.success(operation())
} catch (error: Exception) {
    Result.failure(error)
} catch (error: LinkageError) {
    Result.failure(error)
}
