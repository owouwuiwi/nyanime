package nyanime.panels

import kotlin.math.max
import kotlin.math.min

/** Bounded connected-components extraction. No JNI references or native ownership involved. */
object PanelSegmentation {
    fun extract(probabilities: FloatArray, size: Int = PANEL_INPUT_SIZE): List<PanelRect> {
        require(size in 1..512 && probabilities.size == size * size * 3)
        val content = BooleanArray(size * size) { index ->
            val base = index * 3
            val score = probabilities[base + 2]
            score.isFinite() && score > probabilities[base] && score > probabilities[base + 1]
        }
        val queue = IntArray(content.size)
        val result = mutableListOf<PanelRect>()
        val minArea = max(12, (content.size * 0.003f).toInt())
        for (start in content.indices) {
            if (!content[start]) continue
            var head = 0
            var tail = 1
            queue[0] = start
            content[start] = false
            var left = size
            var top = size
            var right = 0
            var bottom = 0
            while (head < tail) {
                val index = queue[head++]
                val x = index % size
                val y = index / size
                left = min(left, x)
                top = min(top, y)
                right = max(right, x + 1)
                bottom = max(bottom, y + 1)
                fun visit(next: Int) {
                    if (content[next]) {
                        content[next] = false
                        queue[tail++] = next
                    }
                }
                if (x > 0) visit(index - 1)
                if (x + 1 < size) visit(index + 1)
                if (y > 0) visit(index - size)
                if (y + 1 < size) visit(index + size)
            }
            if (tail >= minArea) {
                result += PanelRect(
                    left.toFloat() / size,
                    top.toFloat() / size,
                    right.toFloat() / size,
                    bottom.toFloat() / size,
                )
            }
        }
        return PanelGeometry.deduplicate(result)
    }
}
