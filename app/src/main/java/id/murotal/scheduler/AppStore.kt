package id.murotal.scheduler

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class AppStore(context: Context) {
    private val prefs = context.getSharedPreferences("murotal_data", Context.MODE_PRIVATE)

    fun tracks(): MutableList<AudioTrack> = runCatching {
        val array = JSONArray(prefs.getString("tracks", "[]"))
        MutableList(array.length()) { i ->
            val item = array.getJSONObject(i)
            AudioTrack(item.getString("id"), item.getString("title"), item.getString("uri"))
        }
    }.getOrDefault(mutableListOf())

    fun saveTracks(items: List<AudioTrack>) {
        val array = JSONArray()
        items.forEach { array.put(JSONObject().put("id", it.id).put("title", it.title).put("uri", it.uri)) }
        prefs.edit().putString("tracks", array.toString()).apply()
    }

    fun playlists(): MutableList<Playlist> = runCatching {
        val array = JSONArray(prefs.getString("playlists", "[]"))
        MutableList(array.length()) { i ->
            val item = array.getJSONObject(i)
            val ids = item.getJSONArray("trackIds")
            Playlist(item.getString("id"), item.getString("name"), List(ids.length()) { ids.getString(it) })
        }
    }.getOrDefault(mutableListOf())

    fun savePlaylists(items: List<Playlist>) {
        val array = JSONArray()
        items.forEach { playlist ->
            array.put(JSONObject().put("id", playlist.id).put("name", playlist.name)
                .put("trackIds", JSONArray(playlist.trackIds)))
        }
        prefs.edit().putString("playlists", array.toString()).apply()
    }

    fun schedules(): MutableList<PlaybackSchedule> = runCatching {
        val array = JSONArray(prefs.getString("schedules", "[]"))
        MutableList(array.length()) { i ->
            val item = array.getJSONObject(i)
            PlaybackSchedule(
                item.getString("id"), item.getString("name"), item.getString("targetType"),
                item.getString("targetId"), item.getInt("startMinutes"), item.getInt("endMinutes"),
                item.getInt("volumePercent"), item.optString("playbackMode",
                    if (item.getString("targetType") == "track") "single" else "sequential"),
                item.optString("stopMode", "time"),
                item.optJSONArray("days")?.let { days -> List(days.length()) { days.getInt(it) } }
                    ?: listOf(1, 2, 3, 4, 5, 6, 7),
                item.optBoolean("fadeIn", false), item.optBoolean("equalizerEnabled", false),
                item.optString("equalizerPreset", "Normal"),
                item.optJSONArray("equalizerBands")?.let { bands ->
                    if (bands.length() == 5) List(5) { bands.getInt(it) } else listOf(0, 0, 0, 0, 0)
                } ?: listOf(0, 0, 0, 0, 0), item.optBoolean("enabled", true)
            )
        }
    }.getOrDefault(mutableListOf())

    fun saveSchedules(items: List<PlaybackSchedule>) {
        val array = JSONArray()
        items.forEach { schedule ->
            array.put(JSONObject().put("id", schedule.id).put("name", schedule.name)
                .put("targetType", schedule.targetType).put("targetId", schedule.targetId)
                .put("startMinutes", schedule.startMinutes).put("endMinutes", schedule.endMinutes)
                .put("volumePercent", schedule.volumePercent).put("playbackMode", schedule.playbackMode)
                .put("stopMode", schedule.stopMode).put("days", JSONArray(schedule.days))
                .put("fadeIn", schedule.fadeIn).put("equalizerEnabled", schedule.equalizerEnabled)
                .put("equalizerPreset", schedule.equalizerPreset)
                .put("equalizerBands", JSONArray(schedule.equalizerBands)).put("enabled", schedule.enabled))
        }
        prefs.edit().putString("schedules", array.toString()).apply()
    }

    fun resolveUris(type: String, id: String): List<String> {
        val tracks = tracks()
        return if (type == "track") {
            tracks.firstOrNull { it.id == id }?.let { listOf(it.uri) }.orEmpty()
        } else {
            val ids = playlists().firstOrNull { it.id == id }?.trackIds.orEmpty()
            ids.mapNotNull { trackId -> tracks.firstOrNull { it.id == trackId }?.uri }
        }
    }

    fun nextUriForSchedule(schedule: PlaybackSchedule): String? {
        if (schedule.targetType == "track") return resolveUris("track", schedule.targetId).firstOrNull()
        val playlist = playlists().firstOrNull { it.id == schedule.targetId } ?: return null
        val availableTracks = tracks().associateBy { it.id }
        val ids = playlist.trackIds.filter { availableTracks.containsKey(it) }
        if (ids.isEmpty()) return null
        val selectedId = if (schedule.playbackMode == "shuffle_cycle") {
            val key = "shuffle_${schedule.id}"
            val stored = runCatching {
                val array = JSONArray(prefs.getString(key, "[]"))
                MutableList(array.length()) { array.getString(it) }.filter { it in ids }.toMutableList()
            }.getOrDefault(mutableListOf())
            if (stored.isEmpty()) {
                stored.addAll(ids.shuffled())
                val previous = prefs.getString("${key}_last", null)
                if (stored.size > 1 && stored.first() == previous) {
                    stored.add(stored.removeAt(0))
                }
            }
            val chosen = stored.removeAt(0)
            prefs.edit().putString(key, JSONArray(stored).toString()).putString("${key}_last", chosen).apply()
            chosen
        } else {
            val key = "sequential_${schedule.id}"
            val index = prefs.getInt(key, 0).mod(ids.size)
            prefs.edit().putInt(key, (index + 1).mod(ids.size)).apply()
            ids[index]
        }
        return availableTracks[selectedId]?.uri
    }

    fun targetName(type: String, id: String): String = if (type == "track") {
        tracks().firstOrNull { it.id == id }?.title ?: "Track tidak ditemukan"
    } else {
        playlists().firstOrNull { it.id == id }?.name ?: "Playlist tidak ditemukan"
    }

}
