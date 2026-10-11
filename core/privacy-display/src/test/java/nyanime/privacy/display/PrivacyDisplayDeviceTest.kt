package nyanime.privacy.display

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PrivacyDisplayDeviceTest {
    @Test fun supportedHardwareDoesNotDependOnARegionalModelOrFirmwareFingerprint() {
        listOf("SM-S948B", "SM-S948U", "SM-S948N").forEach { model ->
            assertTrue(PrivacyDisplayDevice("Samsung", model, 36).samsungPrivacyHardware)
            assertTrue(PrivacyDisplayDevice("samsung", model, 37).samsungPrivacyHardware)
        }
    }

    @Test fun apiPresenceAloneCannotEnableUnrelatedHardware() {
        assertFalse(PrivacyDisplayDevice("samsung", "SM-F741B", 36).samsungPrivacyHardware)
        assertFalse(PrivacyDisplayDevice("samsung", "SM-S938B", 36).samsungPrivacyHardware)
        assertFalse(PrivacyDisplayDevice("other", "SM-S948B", 36).samsungPrivacyHardware)
        assertFalse(PrivacyDisplayDevice("samsung", "SM-S948B", 35).samsungPrivacyHardware)
    }
}
