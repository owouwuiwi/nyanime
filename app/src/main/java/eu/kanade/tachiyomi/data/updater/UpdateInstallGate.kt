package eu.kanade.tachiyomi.data.updater

/** Screen-owned intent: never persisted into a new visit or resurrected after process death. */
internal data class UpdateInstallGate(
    val stage: Stage = Stage.IDLE,
    private val expectedUpdateId: String? = null,
    private val leftForAndroid: Boolean = false,
) {
    enum class Stage { IDLE, REQUESTED, PERMISSION, INSTALLER }
    enum class Action { PERMISSION, INSTALLER }

    fun request(updateId: String? = null) =
        if (stage == Stage.IDLE) copy(stage = Stage.REQUESTED, expectedUpdateId = updateId) else this

    /** Claim before launching Android, so repeated state emissions/rotation cannot launch twice. */
    fun claim(
        ready: Boolean,
        resumed: Boolean,
        allowed: Boolean,
        updateId: String? = null,
    ): Pair<UpdateInstallGate, Action?> {
        if (!ready || !resumed || stage != Stage.REQUESTED) return this to null
        if (expectedUpdateId != null && expectedUpdateId != updateId) return UpdateInstallGate() to null
        return if (allowed) {
            copy(stage = Stage.INSTALLER, leftForAndroid = false) to Action.INSTALLER
        } else {
            copy(stage = Stage.PERMISSION, leftForAndroid = false) to Action.PERMISSION
        }
    }

    fun permissionReturned(granted: Boolean) =
        if (stage == Stage.PERMISSION) {
            if (granted) copy(stage = Stage.REQUESTED) else UpdateInstallGate()
        } else {
            this
        }

    fun lifecycle(resumed: Boolean): UpdateInstallGate = when {
        stage != Stage.INSTALLER -> this
        !resumed -> copy(leftForAndroid = true)
        leftForAndroid -> UpdateInstallGate()
        else -> this
    }
}
