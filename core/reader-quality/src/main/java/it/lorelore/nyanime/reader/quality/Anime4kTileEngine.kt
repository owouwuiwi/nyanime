package it.lorelore.nyanime.reader.quality

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.opengl.GLES30 as GL

/** One worker thread owns context, programs and every texture throughout their lifetime. */
internal class Anime4kTileEngine(private val assets: AssetManager) : QualityBackend {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var programs: List<Int> = emptyList()
    private var passes: List<Anime4kPass> = emptyList()
    private val owner = Thread.currentThread()

    private fun ready() {
        check(Thread.currentThread() === owner)
        if (programs.isNotEmpty()) return
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY)
            val versions = IntArray(2)
            check(EGL14.eglInitialize(display, versions, 0, versions, 1))
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            check(
                EGL14.eglChooseConfig(
                    display,
                    intArrayOf(
                        EGL14.EGL_RENDERABLE_TYPE, 0x40,
                        EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                        EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                        EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE,
                    ),
                    0,
                    configs,
                    0,
                    1,
                    count,
                    0,
                ) &&
                    count[0] > 0,
            )
            context = EGL14.eglCreateContext(
                display,
                configs[0],
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE),
                0,
            )
            check(context != EGL14.EGL_NO_CONTEXT)
            surface = EGL14.eglCreatePbufferSurface(
                display,
                configs[0],
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
                0,
            )
            check(surface != EGL14.EGL_NO_SURFACE)
            check(EGL14.eglMakeCurrent(display, surface, surface, context))
            passes = Anime4kCompiler.parse(
                listOf("Anime4K_Restore_CNN_S.glsl", "Anime4K_Upscale_CNN_x2_S.glsl").map {
                    assets.open("anime4k/$it").bufferedReader().use { reader -> reader.readText() }
                },
            )
            check(passes.isNotEmpty() && passes.size <= 32 && passes.all { it.hook == "MAIN" })
            val compiled = ArrayList<Int>()
            try {
                passes.forEachIndexed { index, pass ->
                    var fragment = Anime4kCompiler.compileToFragmentShader(pass)
                    if (index == passes.lastIndex) {
                        fragment = fragment.replace("outColor = hook();", "outColor = vec4(hook().rgb, 1.0);")
                    }
                    compiled += program(fragment)
                }
                programs = compiled
            } catch (error: Throwable) {
                compiled.forEach(GL::glDeleteProgram)
                throw error
            }
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    override fun process(input: Bitmap): Bitmap {
        check(input.colorSpace?.isSrgb != false) { "Original retained for wide-gamut input" }
        require(input.width in 1..256 && input.height in 1..256 && !input.isRecycled)
        val row = IntArray(input.width)
        for (y in 0 until input.height) {
            input.getPixels(row, 0, input.width, 0, y, input.width, 1)
            require(row.all { it ushr 24 == 255 }) { "Transparent image requires the original renderer" }
        }
        ready()
        val textures = ArrayList<Int>()
        val fbos = ArrayList<Int>()
        data class Texture(val id: Int, val width: Int, val height: Int)
        val names = mutableMapOf<String, Texture>()
        val quad = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f))
            position(0)
        }
        var output: Bitmap? = null
        try {
            val first = texture(input.width, input.height, false).also(textures::add)
            GLUtils.texImage2D(GL.GL_TEXTURE_2D, 0, input, 0)
            names["MAIN"] = Texture(first, input.width, input.height)
            var lastWidth = input.width
            var lastHeight = input.height
            passes.forEachIndexed { index, pass ->
                val sizes = names.mapValues { it.value.width to it.value.height } +
                    ("OUTPUT" to (input.width * 2 to input.height * 2))
                val width =
                    pass.widthExpression?.let { Anime4kSizeEvaluator.evaluate(it, sizes) }
                        ?: names.getValue(pass.hook).width
                val height =
                    pass.heightExpression?.let { Anime4kSizeEvaluator.evaluate(it, sizes) }
                        ?: names.getValue(pass.hook).height
                check(width in 1..512 && height in 1..512)
                val last = index == passes.lastIndex
                val target = texture(width, height, !last).also(textures::add)
                val fbo = IntArray(1).also { GL.glGenFramebuffers(1, it, 0) }[0].also(fbos::add)
                GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, fbo)
                GL.glFramebufferTexture2D(GL.GL_FRAMEBUFFER, GL.GL_COLOR_ATTACHMENT0, GL.GL_TEXTURE_2D, target, 0)
                check(GL.glCheckFramebufferStatus(GL.GL_FRAMEBUFFER) == GL.GL_FRAMEBUFFER_COMPLETE)
                GL.glViewport(0, 0, width, height)
                val program = programs[index]
                GL.glUseProgram(program)
                (
                    listOf("MAIN") +
                        pass.binds.filterNot {
                            it == "HOOKED"
                        } +
                        pass.hook
                    ).distinct().forEachIndexed { unit, name ->
                    val location = GL.glGetUniformLocation(program, name)
                    if (location >= 0) {
                        val source = names[name] ?: error("Missing shader input")
                        GL.glActiveTexture(GL.GL_TEXTURE0 + unit)
                        GL.glBindTexture(GL.GL_TEXTURE_2D, source.id)
                        GL.glUniform1i(location, unit)
                        GL.glUniform2f(
                            GL.glGetUniformLocation(program, "${name}_size"),
                            source.width.toFloat(),
                            source.height.toFloat(),
                        )
                        GL.glUniform2f(
                            GL.glGetUniformLocation(program, "${name}_pt"),
                            1f / source.width,
                            1f / source.height,
                        )
                    }
                }
                GL.glEnableVertexAttribArray(0)
                GL.glEnableVertexAttribArray(1)
                quad.position(0)
                GL.glVertexAttribPointer(0, 2, GL.GL_FLOAT, false, 16, quad)
                quad.position(2)
                GL.glVertexAttribPointer(1, 2, GL.GL_FLOAT, false, 16, quad)
                GL.glDrawArrays(GL.GL_TRIANGLE_STRIP, 0, 4)
                check(GL.glGetError() == GL.GL_NO_ERROR)
                names[pass.save ?: "MAIN"] = Texture(target, width, height)
                lastWidth = width
                lastHeight = height
            }
            check(lastWidth == input.width * 2 && lastHeight == input.height * 2)
            val pixels = ByteBuffer.allocateDirect(lastWidth * lastHeight * 4).order(ByteOrder.nativeOrder())
            GL.glReadPixels(0, 0, lastWidth, lastHeight, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, pixels)
            check(GL.glGetError() == GL.GL_NO_ERROR)
            pixels.rewind()
            output = Bitmap.createBitmap(lastWidth, lastHeight, Bitmap.Config.ARGB_8888)
            output.copyPixelsFromBuffer(pixels)
            return output
        } catch (error: Throwable) {
            output?.recycle()
            throw error
        } finally {
            GL.glDeleteFramebuffers(fbos.size, fbos.toIntArray(), 0)
            GL.glDeleteTextures(textures.size, textures.toIntArray(), 0)
            GL.glDisableVertexAttribArray(0)
            GL.glDisableVertexAttribArray(1)
            GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, 0)
        }
    }

    private fun texture(width: Int, height: Int, floating: Boolean): Int {
        val id = IntArray(1).also { GL.glGenTextures(1, it, 0) }[0]
        GL.glBindTexture(GL.GL_TEXTURE_2D, id)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE)
        GL.glTexImage2D(
            GL.GL_TEXTURE_2D, 0, if (floating) GL.GL_RGBA16F else GL.GL_RGBA8,
            width, height, 0, GL.GL_RGBA, if (floating) GL.GL_HALF_FLOAT else GL.GL_UNSIGNED_BYTE, null,
        )
        return id
    }

    private fun program(fragment: String): Int {
        fun shader(type: Int, source: String): Int {
            val id = GL.glCreateShader(type)
            GL.glShaderSource(id, source)
            GL.glCompileShader(id)
            val status = IntArray(1)
            GL.glGetShaderiv(id, GL.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                android.util.Log.w("ReaderQuality", GL.glGetShaderInfoLog(id).take(2048))
                GL.glDeleteShader(id)
                error("Shader unavailable")
            }
            return id
        }
        val vertex =
            shader(
                GL.GL_VERTEX_SHADER,
                """
            #version 300 es
            layout(location = 0) in vec2 aPosition;
            layout(location = 1) in vec2 aTexCoord;
            out vec2 vTexCoord;
            void main() { gl_Position = vec4(aPosition, 0.0, 1.0); vTexCoord = aTexCoord; }
                """.trimIndent(),
            )
        var fragmentId = 0
        var id = 0
        try {
            fragmentId = shader(GL.GL_FRAGMENT_SHADER, fragment)
            id = GL.glCreateProgram()
            GL.glAttachShader(id, vertex)
            GL.glAttachShader(id, fragmentId)
            GL.glLinkProgram(id)
            val status = IntArray(1)
            GL.glGetProgramiv(id, GL.GL_LINK_STATUS, status, 0)
            check(status[0] != 0) { "Shader link unavailable" }
            return id
        } catch (error: Throwable) {
            GL.glDeleteProgram(id)
            throw error
        } finally {
            GL.glDeleteShader(vertex)
            GL.glDeleteShader(fragmentId)
        }
    }

    override fun close() {
        check(Thread.currentThread() === owner)
        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglMakeCurrent(display, surface, surface, context)
        programs.forEach(GL::glDeleteProgram)
        programs = emptyList()
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
            EGL14.eglReleaseThread()
        }
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        surface = EGL14.EGL_NO_SURFACE
    }
}
