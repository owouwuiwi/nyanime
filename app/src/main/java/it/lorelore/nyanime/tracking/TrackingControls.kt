package it.lorelore.nyanime.tracking

/** Manual tracking controls preserve the established service semantics, without touching local seen flags. */
object TrackingControls {
    fun status(before: TrackingValues, status: Long, completed: Boolean, total: Long): TrackingPatch {
        val fields = mutableSetOf(TrackingField.STATUS)
        var values = before.copy(status = status)
        if (completed && total > 0) {
            fields += TrackingField.PROGRESS
            values = values.copy(progress = total.toDouble())
        }
        return TrackingPatch(values, fields)
    }

    fun progress(
        before: TrackingValues,
        progress: Double,
        total: Long,
        current: Long?,
        repeating: Long?,
        completed: Long?,
        finishedAt: Long?,
    ): TrackingPatch {
        val fields = mutableSetOf(TrackingField.PROGRESS)
        var values = before.copy(progress = progress)
        if (before.progress == 0.0 && progress > 0 && before.status != repeating && current != null) {
            fields += TrackingField.STATUS
            values = values.copy(status = current)
        }
        if (total > 0 && progress == total.toDouble() && completed != null) {
            fields += TrackingField.STATUS
            values = values.copy(status = completed)
            if (finishedAt != null && before.finishDate == 0L) {
                fields += TrackingField.FINISH
                values = values.copy(finishDate = finishedAt)
            }
        }
        return TrackingPatch(values, fields)
    }
}
