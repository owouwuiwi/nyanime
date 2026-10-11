package it.lorelore.nyanime.buildlogic

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationParameters
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Pinned MediaPipe 0.10.35 has no telemetry opt-out. Replace only its statistics factory,
 * before D8/R8, with a local no-op implementation of the unchanged upstream interface.
 * No graph, model, inference, native code or other Google's logging client is modified.
 */
abstract class LocalMediaPipeLogging : AsmClassVisitorFactory<InstrumentationParameters.None> {
    override fun isInstrumentable(classData: ClassData): Boolean =
        classData.className == "com.google.mediapipe.tasks.core.logging.TasksStatsLoggerFactory"

    override fun createClassVisitor(classContext: ClassContext, nextClassVisitor: ClassVisitor): ClassVisitor =
        object : ClassVisitor(Opcodes.ASM9, nextClassVisitor) {
            private var replaced = false
            override fun visitMethod(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?,
            ): MethodVisitor? {
                if (name != "create") return super.visitMethod(access, name, descriptor, signature, exceptions)
                val expected = "(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;)" +
                    "Lcom/google/mediapipe/tasks/core/logging/TasksStatsLogger;"
                check(descriptor == expected) {
                    "MediaPipe logging contract changed: review privacy before upgrading"
                }
                check(!replaced)
                replaced = true
                super.visitMethod(access, name, descriptor, signature, exceptions).apply {
                    visitCode()
                    visitTypeInsn(Opcodes.NEW, "it/lorelore/nyanime/attention/core/LocalAttentionStats")
                    visitInsn(Opcodes.DUP)
                    visitMethodInsn(
                        Opcodes.INVOKESPECIAL,
                        "it/lorelore/nyanime/attention/core/LocalAttentionStats",
                        "<init>",
                        "()V",
                        false,
                    )
                    visitInsn(Opcodes.ARETURN)
                    visitMaxs(2, 3)
                    visitEnd()
                }
                return null
            }

            override fun visitEnd() {
                check(replaced) { "MediaPipe statistics factory was not replaced" }
                super.visitEnd()
            }
        }
}
