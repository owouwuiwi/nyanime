package it.lorelore.nyanime.tracking

object TrackingQueueReconciliation {
    fun desired(edit: PendingTrackingEdit, remote: TrackingValues): TrackingValues {
        if (!remote.valid()) throw TrackingQueueException(TrackingQueueProblem.REJECTED)
        for (field in edit.expectedFields) {
            val expected = edit.expectedRemote ?: throw TrackingQueueException(TrackingQueueProblem.CONFLICT)
            if (!TrackingQueuePolicy.sameField(remote, expected, field) &&
                edit.alternativeExpectedRemote?.let { TrackingQueuePolicy.sameField(remote, it, field) } != true &&
                !TrackingQueuePolicy.sameField(remote, edit.patch.values, field)
            ) {
                throw TrackingQueueException(TrackingQueueProblem.CONFLICT)
            }
        }
        val fields = if (edit.automaticProgress && edit.patch.values.progress <= remote.progress) {
            edit.patch.fields - TrackingField.PROGRESS
        } else {
            edit.patch.fields
        }
        val desired = edit.patch.copy(fields = fields).applyTo(remote)
        if (fields.any { it == TrackingField.START || it == TrackingField.FINISH } &&
            desired.startDate > 0 &&
            desired.finishDate > 0 &&
            desired.finishDate < desired.startDate
        ) {
            throw TrackingQueueException(TrackingQueueProblem.CONFLICT)
        }
        return desired
    }
}

object TrackingQueueFailures {
    fun classify(error: Throwable): Pair<TrackingQueueProblem, Long?> {
        val chain = generateSequence(error) { it.cause }.take(8).toList()
        chain.filterIsInstance<TrackingQueueException>().firstOrNull()?.let { return it.problem to null }
        val response = chain.filterIsInstance<TrackingQueueHttpFailure>().firstOrNull()
        val http = chain.filterIsInstance<eu.kanade.tachiyomi.network.HttpException>().firstOrNull()
        val code = response?.code ?: http?.code
        val problem = when (code) {
            401, 403 -> TrackingQueueProblem.AUTHENTICATION
            404, 410 -> TrackingQueueProblem.ASSOCIATION
            409, 412 -> TrackingQueueProblem.CONFLICT
            429 -> TrackingQueueProblem.RATE_LIMIT
            in 500..599 -> TrackingQueueProblem.SERVICE
            null -> if (chain.any {
                    it is java.io.IOException
                }
            ) {
                TrackingQueueProblem.NETWORK
            } else {
                TrackingQueueProblem.REJECTED
            }
            else -> TrackingQueueProblem.REJECTED
        }
        return problem to (response?.retryAfter ?: http?.retryAfterMillis)
    }
}
