package id.murotal.scheduler

data class AudioTrack(val id: String, val title: String, val uri: String)

data class Playlist(val id: String, val name: String, val trackIds: List<String>)

data class EqualizerSettings(
    val enabled: Boolean = false,
    val preset: String = "Normal",
    val bands: List<Int> = listOf(0, 0, 0, 0, 0)
)

data class PlaybackSchedule(
    val id: String,
    val name: String,
    val targetType: String,
    val targetId: String,
    val startMinutes: Int,
    val endMinutes: Int,
    val volumePercent: Int,
    val playbackMode: String = "single",
    val stopMode: String = "time",
    val days: List<Int> = listOf(1, 2, 3, 4, 5, 6, 7),
    val fadeIn: Boolean = false,
    val enabled: Boolean = true
)
