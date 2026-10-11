package nyanime.secretgame

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64

internal object SandspielScene {
    const val MAX_BYTES = 500_000
    private val materials = setOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 11, 12, 13, 14, 15, 16, 17, 18, 19)

    fun validate(payload: String) {
        require(payload.length <= MAX_BYTES)
        val value = Json.parseToJsonElement(payload).jsonObject
        require(value.keys == setOf("v", "cells", "material", "brush"))
        require(value.getValue("v").jsonPrimitive.intOrNull == 1)
        require(value.getValue("material").jsonPrimitive.intOrNull in materials)
        require(value.getValue("brush").jsonPrimitive.intOrNull in 0..4)
        val encoded = value.getValue("cells").jsonPrimitive.content
        require(encoded.length == 480_000)
        val bytes = Base64.getDecoder().decode(encoded)
        require(bytes.size == 360_000)
        for (index in bytes.indices step 4) require((bytes[index].toInt() and 0xff) in materials)
    }
}

/** One atomic local creation. Invalid input never touches the last committed file. */
internal class SceneStore(private val file: File) {
    private val magic = "NYASAND1".toByteArray(Charsets.US_ASCII)

    fun read(): String? {
        if (!file.exists()) return null
        require(file.length() in 45..(SandspielScene.MAX_BYTES + 44L))
        val bytes = file.readBytes()
        require(bytes.copyOfRange(0, 8).contentEquals(magic))
        val length = ByteBuffer.wrap(bytes, 8, 4).int
        require(length == bytes.size - 44 && length in 1..SandspielScene.MAX_BYTES)
        val payload = bytes.copyOfRange(44, bytes.size)
        require(MessageDigest.isEqual(bytes.copyOfRange(12, 44), MessageDigest.getInstance("SHA-256").digest(payload)))
        return payload.toString(Charsets.UTF_8).also(SandspielScene::validate)
    }

    fun write(payload: String) {
        SandspielScene.validate(payload)
        val bytes = payload.toByteArray(Charsets.UTF_8)
        require(bytes.size <= SandspielScene.MAX_BYTES)
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        val temporary = File(file.parentFile, file.name + ".pending")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(magic)
                output.write(ByteBuffer.allocate(4).putInt(bytes.size).array())
                output.write(MessageDigest.getInstance("SHA-256").digest(bytes))
                output.write(bytes)
                output.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            temporary.delete()
        }
    }
}

internal class SnapshotAssembler {
    private var id = 0L
    private var expected = 0
    private var next = 0
    private var data = StringBuilder()

    fun begin(id: Long, parts: Int) {
        reset()
        require(id > 0 && parts in 1..24)
        this.id = id
        expected = parts
    }

    fun append(id: Long, index: Int, part: String) {
        require(this.id == id && expected > 0 && index == next && next < expected)
        require(part.length <= 24_000 && data.length + part.length <= SandspielScene.MAX_BYTES)
        data.append(part)
        next++
    }

    fun finish(id: Long): String {
        require(this.id == id && expected > 0 && next == expected)
        return data.toString().also { reset() }
    }

    fun reset() {
        id = 0
        expected = 0
        next = 0
        data = StringBuilder()
    }
}
