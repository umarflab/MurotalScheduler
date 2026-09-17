package id.murotal.scheduler

data class AudioTrack(val id: String, val title: String, val uri: String)

data class Playlist(val id: String, val name: String, val trackIds: List<String>)

data class PlaybackSchedule(
    val id: String,
    val name: String,
    val targetType: String,
    val targetId: String,
    val startMinutes: Int,
    val endMinutes: Int,
    val volumePercent: Int,
    val enabled: Boolean = true
)
