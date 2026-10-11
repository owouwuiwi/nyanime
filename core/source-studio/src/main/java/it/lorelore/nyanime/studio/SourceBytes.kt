package it.lorelore.nyanime.studio

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Enforces source and cache budgets during reads, before an oversized allocation can occur. */
object SourceBytes {
    fun read(input: InputStream, maximum: Int): ByteArray {
        require(maximum >= 0)
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size().toLong() + count <= maximum) { "Source data exceeds its limit" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
