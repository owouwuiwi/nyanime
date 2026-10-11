package nyanime.privacy.display

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SamsungPrivacyMethodsTest {
    class Api {
        val calls = mutableListOf<String>()
        var reject = false
        fun semSetPrivacyDisplayView(radius: Float) {
            check(!reject)
            calls += "radius:$radius"
        }
        fun semSetPrivacyDisplayViewPosition(left: Int, top: Int, right: Int, bottom: Int) {
            calls += "$left,$top,$right,$bottom"
        }
        fun semDisablePrivacyDisplayView() {
            calls += "clear"
        }
    }
    class IncompleteApi {
        fun semSetPrivacyDisplayView(radius: Float) = radius
    }
    class PrivateApi {
        private fun semSetPrivacyDisplayView(radius: Float) = radius
        private fun semSetPrivacyDisplayViewPosition(left: Int, top: Int, right: Int, bottom: Int) =
            left + top + right + bottom
        private fun semDisablePrivacyDisplayView() = Unit
    }

    @Test fun allSignaturesAreRequiredAndPrivateMethodsAreNotBypassed() {
        assertTrue(samsungApiCall { SamsungPrivacyMethods.resolve(Api::class.java) }.isSuccess)
        assertTrue(samsungApiCall { SamsungPrivacyMethods.resolve(IncompleteApi::class.java) }.isFailure)
        assertTrue(samsungApiCall { SamsungPrivacyMethods.resolve(PrivateApi::class.java) }.isFailure)
    }

    @Test fun radiusAndCoordinatesKeepTheirOriginalMeaningAndOrder() {
        val api = Api()
        val methods = SamsungPrivacyMethods.resolve(Api::class.java)
        methods.apply(api, PrivacyRegion(PrivacyBounds(1, 2, 111, 222), 0, 12f))
        methods.clear(api)
        assertEquals(listOf("radius:12.0", "1,2,111,222", "clear"), api.calls)
    }

    @Test fun invocationErrorsAreReportedWithoutPositioningAfterFailure() {
        val api = Api().apply { reject = true }
        val methods = SamsungPrivacyMethods.resolve(Api::class.java)
        val result = samsungApiCall { methods.apply(api, PrivacyRegion(PrivacyBounds(1, 2, 111, 222), 0)) }
        assertFalse(result.isSuccess)
        assertTrue(api.calls.isEmpty())
        assertTrue(samsungApiCall { throw NoSuchMethodError() }.isFailure)
    }

    @Test fun changingPositionDoesNotReactivateThePrivacyView() {
        val api = Api()
        val methods = SamsungPrivacyMethods.resolve(Api::class.java)
        val region = PrivacyRegion(PrivacyBounds(1, 2, 111, 222), 0)
        methods.apply(api, region)
        methods.apply(api, region.copy(bounds = region.bounds.translate(0, 20)), activate = false)
        assertEquals(listOf("radius:0.0", "1,2,111,222", "1,22,111,242"), api.calls)
    }
}
