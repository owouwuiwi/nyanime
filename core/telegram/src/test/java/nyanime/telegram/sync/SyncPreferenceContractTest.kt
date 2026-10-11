package nyanime.telegram.sync

import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SyncPreferenceContractTest {
    @Test fun declarationsCannotReachAnotherSourcesSettingsOrUseUnknownTypes() {
        val rule = SyncPreferenceRule(71, "quality", "int", minimum = 0.0, maximum = 3.0)
        SyncPreferenceContract(settings = listOf(rule)).validate(setOf(71))
        rule.validate(JsonPrimitive(2))
        assertThrows(IllegalArgumentException::class.java) { rule.validate(JsonPrimitive(4)) }
        assertThrows(IllegalArgumentException::class.java) { rule.validate(JsonPrimitive(true)) }
        assertThrows(IllegalArgumentException::class.java) {
            SyncPreferenceContract(settings = listOf(rule)).validate(setOf(72))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SyncPreferenceContract(settings = listOf(rule, rule)).validate(setOf(71))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SyncPreferenceContract(settings = listOf(rule.copy(type = "arbitrary-file"))).validate(setOf(71))
        }
    }
}
