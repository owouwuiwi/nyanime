package nyanime.panels

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PanelGeometryTest {
    @Test fun clippedPanelSpanningTwoTilesBecomesOne() {
        val first = PanelTile(0, 0, 100, 200) to listOf(PanelRect(.1f, .5f, .9f, 1f))
        val second = PanelTile(0, 160, 100, 200) to listOf(PanelRect(.1f, 0f, .9f, .6f))
        val result = PanelGeometry.stitch(listOf(first, second), 100, 360)
        assertEquals(1, result.size)
        assertEquals(100f / 360, result.single().top, .001f)
        assertEquals(280f / 360, result.single().bottom, .001f)
    }

    @Test fun correctionNeverInvertsOrEscapesPage() {
        val tiny = PanelRect(.998f, .998f, 1f, 1f)
        assertTrue(PanelGeometry.resize(tiny, -1f, -1f, 0).valid())
        assertTrue(PanelGeometry.resize(PanelRect(.1f, .1f, .8f, .8f), 1f, 1f, 0).valid())
        assertTrue(PanelGeometry.resize(PanelRect(.1f, .1f, .8f, .8f), -1f, -1f, 2).valid())
    }

    @Test fun guttersKeepTallPanelTogether() {
        val tall = PanelRect(.05f, .05f, .4f, .95f)
        val top = PanelRect(.5f, .05f, .95f, .45f)
        val bottom = PanelRect(.5f, .55f, .95f, .95f)
        assertEquals(listOf(tall, top, bottom), PanelGeometry.order(listOf(bottom, tall, top), PanelDirection.LTR))
        assertEquals(listOf(top, bottom, tall), PanelGeometry.order(listOf(bottom, tall, top), PanelDirection.RTL))
    }

    @Test fun horizontalRowsPrecedeDirectionOfColumns() {
        val a = PanelRect(.05f, .05f, .45f, .45f)
        val b = PanelRect(.55f, .05f, .95f, .45f)
        val c = PanelRect(.05f, .55f, .45f, .95f)
        val d = PanelRect(.55f, .55f, .95f, .95f)
        assertEquals(listOf(b, a, d, c), PanelGeometry.order(listOf(d, a, c, b), PanelDirection.RTL))
        assertEquals(listOf(a, b, c, d), PanelGeometry.order(listOf(d, a, c, b), PanelDirection.LTR))
    }

    @Test fun longStripTilesCoverEveryPixelAndOverlap() {
        val tiles = PanelGeometry.tiles(800, 10_000)
        assertEquals(0, tiles.first().y)
        assertEquals(10_000, tiles.last().y + tiles.last().height)
        tiles.zipWithNext().forEach { (first, next) -> assertTrue(next.y < first.y + first.height) }
        val mapped = tiles.last().map(PanelRect.PAGE, 800, 10_000)
        assertEquals(1f, mapped.bottom)
        assertTrue(mapped.top > 0f)
        assertEquals(1, PanelGeometry.tiles(1200, 1600).size)
    }

    @Test fun duplicatesDoNotCreateExtraPanels() {
        val rect = PanelRect(.2f, .1f, .8f, .5f)
        assertEquals(listOf(rect), PanelGeometry.deduplicate(listOf(rect, rect.copy(top = .101f))))
    }

    @Test fun outputComponentsIgnoreNoiseAndInvalidScores() {
        val size = 32
        val values = FloatArray(size * size * 3) { if (it % 3 == 0) 1f else 0f }
        fun region(x0: Int, y0: Int, x1: Int, y1: Int) {
            for (y in y0 until y1) {
                for (x in x0 until x1) {
                    values[(y * size + x) * 3] = 0f
                    values[(y * size + x) * 3 + 2] = 1f
                }
            }
        }
        region(2, 2, 14, 14)
        region(18, 18, 30, 30)
        region(0, 30, 1, 31)
        assertEquals(2, PanelSegmentation.extract(values, size).size)
        values.fill(Float.NaN)
        assertTrue(PanelSegmentation.extract(values, size).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { PanelSegmentation.extract(FloatArray(1)) }
    }

    @Test fun portableCorrectionsAndZoomSurviveSerialization() {
        val record = GuidedPageRecord(
            PanelPageRef(73, "/title/7", "/chapter/3", 4),
            "a".repeat(64),
            corrections = listOf(PanelRect(.1f, .2f, .8f, .9f)),
            location = GuidedLocation(0, .5f, .6f, 2.2f, false),
            changedAt = 1234,
        )
        assertTrue(record.valid())
        assertEquals(record, Json.decodeFromString<GuidedPageRecord>(Json.encodeToString(record)))
        assertEquals(
            record,
            ProtoBuf.decodeFromByteArray(
                GuidedPageRecord.serializer(),
                ProtoBuf.encodeToByteArray(GuidedPageRecord.serializer(), record),
            ),
        )
        assertFalse(record.copy(location = GuidedLocation(0, Float.NaN)).valid())
        assertFalse(record.copy(corrections = emptyList()).valid())
        assertFalse(record.copy(imageHash = "invalid").valid())
        assertEquals(0x40L, GUIDED_READING_FLAG)
        assertEquals(0L, GUIDED_READING_FLAG and 0x3fL)
    }
}
