package it.lorelore.nyanime.presentation.player.flow.data.model
data class PlayerChapter(val title: String, val startTimeSeconds: Int)
data class TimelineSegment(val category: String, val startTime: Float, val endTime: Float)
