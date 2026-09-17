package id.murotal.scheduler

import android.Manifest
import android.app.AlarmManager
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import java.util.Locale
import java.util.UUID

class MainActivity : AppCompatActivity() {
    private lateinit var store: AppStore
    private lateinit var content: LinearLayout
    private lateinit var nowPlaying: TextView
    private var selectedTab = 0

    private val audioPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        val tracks = store.tracks()
        uris.forEach { uri ->
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            if (tracks.none { it.uri == uri.toString() }) {
                tracks.add(AudioTrack(UUID.randomUUID().toString(), displayName(uri), uri.toString()))
            }
        }
        store.saveTracks(tracks)
        showLibrary()
        toast("${uris.size} audio ditambahkan")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = AppStore(this)
        content = findViewById(R.id.content)
        nowPlaying = findViewById(R.id.nowPlaying)
        requestNotificationPermission()

        findViewById<Button>(R.id.stopButton).setOnClickListener {
            startService(Intent(this, PlaybackService::class.java).setAction(PlaybackService.ACTION_STOP))
            nowPlaying.text = "Tidak ada audio diputar"
        }

        findViewById<TabLayout>(R.id.tabs).apply {
            addTab(newTab().setText("Pustaka"))
            addTab(newTab().setText("Playlist"))
            addTab(newTab().setText("Jadwal"))
            addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    selectedTab = tab.position
                    renderTab()
                }
                override fun onTabUnselected(tab: TabLayout.Tab) = Unit
                override fun onTabReselected(tab: TabLayout.Tab) = renderTab()
            })
        }
        showLibrary()
        AlarmScheduler.scheduleAll(this)
    }

    private fun renderTab() = when (selectedTab) {
        0 -> showLibrary()
        1 -> showPlaylists()
        else -> showSchedules()
    }

    private fun showLibrary() {
        content.removeAllViews()
        content.addView(primaryButton("Tambah audio") { audioPicker.launch(arrayOf("audio/*")) })
        val tracks = store.tracks()
        if (tracks.isEmpty()) content.addView(emptyText("Belum ada audio. Pilih Tambah audio untuk mengimpor murotal dari penyimpanan telepon."))
        tracks.forEach { track ->
            content.addView(card(track.title, "Track tunggal", "Putar", {
                play(listOf(track.uri), track.title)
            }, "Hapus", {
                confirmDelete("Hapus track '${track.title}'?") {
                    val updated = store.tracks().filterNot { it.id == track.id }
                    store.saveTracks(updated)
                    store.savePlaylists(store.playlists().map { it.copy(trackIds = it.trackIds.filterNot { id -> id == track.id }) })
                    showLibrary()
                }
            }))
        }
    }

    private fun showPlaylists() {
        content.removeAllViews()
        content.addView(primaryButton("Buat playlist") { openPlaylistDialog() })
        val playlists = store.playlists()
        val trackMap = store.tracks().associateBy { it.id }
        if (playlists.isEmpty()) content.addView(emptyText("Belum ada playlist. Playlist menggabungkan beberapa track yang dapat diputar berurutan."))
        playlists.forEach { playlist ->
            val count = playlist.trackIds.count { trackMap.containsKey(it) }
            content.addView(card(playlist.name, "$count track", "Putar", {
                val uris = store.resolveUris("playlist", playlist.id)
                if (uris.isEmpty()) toast("Playlist tidak memiliki track yang tersedia") else play(uris, playlist.name)
            }, "Hapus", {
                confirmDelete("Hapus playlist '${playlist.name}'?") {
                    store.savePlaylists(store.playlists().filterNot { it.id == playlist.id })
                    showPlaylists()
                }
            }))
        }
    }

    private fun showSchedules() {
        content.removeAllViews()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) {
            content.addView(card("Alarm presisi belum diizinkan", "Jadwal tetap dibuat, tetapi dapat terlambat oleh sistem.", "Buka pengaturan", {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            }, null, null))
        }
        content.addView(primaryButton("Tambah jadwal") { openScheduleDialog() })
        val schedules = store.schedules()
        if (schedules.isEmpty()) content.addView(emptyText("Belum ada jadwal. Setiap jadwal memiliki sumber audio, waktu mulai, waktu berhenti, dan volume sendiri."))
        schedules.forEach { schedule ->
            val status = if (schedule.enabled) "Aktif" else "Nonaktif"
            val modeName = when (schedule.playbackMode) {
                "shuffle_cycle" -> "Shuffle Cycle"
                "sequential" -> "Sequential"
                else -> "Single"
            }
            val stopName = if (schedule.stopMode == "time") "${time(schedule.startMinutes)}–${time(schedule.endMinutes)}" else "${time(schedule.startMinutes)}–selesai"
            val dayName = daySummary(schedule.days)
            val fade = if (schedule.fadeIn) " • Fade-in" else ""
            val equalizerName = if (schedule.equalizerEnabled) " • EQ ${schedule.equalizerPreset}" else ""
            val detail = "$dayName • $stopName\n$modeName • Volume ${schedule.volumePercent}%$fade$equalizerName • $status\n${store.targetName(schedule.targetType, schedule.targetId)}"
            content.addView(card(schedule.name, detail, "Edit", {
                openScheduleDialog(schedule)
            }, if (schedule.enabled) "Nonaktifkan" else "Aktifkan", {
                val changed = schedule.copy(enabled = !schedule.enabled)
                store.saveSchedules(store.schedules().map { if (it.id == schedule.id) changed else it })
                if (changed.enabled) AlarmScheduler.schedule(this, changed) else AlarmScheduler.cancel(this, changed.id)
                showSchedules()
            }, "Hapus") {
                confirmDelete("Hapus jadwal '${schedule.name}'?") {
                    AlarmScheduler.cancel(this, schedule.id)
                    store.saveSchedules(store.schedules().filterNot { it.id == schedule.id })
                    showSchedules()
                }
            })
        }
    }

    private fun openPlaylistDialog() {
        val tracks = store.tracks()
        if (tracks.isEmpty()) {
            toast("Tambahkan audio ke Pustaka terlebih dahulu")
            return
        }
        val form = verticalContainer()
        val name = EditText(this).apply { hint = "Nama playlist" }
        form.addView(name)
        form.addView(label("Pilih track"))
        val checks = tracks.map { track ->
            CheckBox(this).apply { text = track.title; isChecked = true; form.addView(this) }
        }
        MaterialAlertDialogBuilder(this).setTitle("Playlist baru").setView(form)
            .setNegativeButton("Batal", null).setPositiveButton("Simpan") { _, _ ->
                val chosen = tracks.filterIndexed { index, _ -> checks[index].isChecked }.map { it.id }
                if (name.text.isBlank() || chosen.isEmpty()) {
                    toast("Nama dan minimal satu track wajib diisi")
                } else {
                    val items = store.playlists()
                    items.add(Playlist(UUID.randomUUID().toString(), name.text.toString().trim(), chosen))
                    store.savePlaylists(items)
                    showPlaylists()
                }
            }.show()
    }

    private fun openScheduleDialog(existing: PlaybackSchedule? = null) {
        val tracks = store.tracks()
        val playlists = store.playlists().filter { it.trackIds.isNotEmpty() }
        if (tracks.isEmpty()) {
            toast("Tambahkan audio ke Pustaka terlebih dahulu")
            return
        }
        val form = verticalContainer()
        val name = EditText(this).apply {
            hint = "Nama jadwal, misalnya Murotal pagi"
            setText(existing?.name.orEmpty())
        }
        val typeSpinner = Spinner(this)
        val typeLabels = if (playlists.isEmpty()) listOf("Track tunggal") else listOf("Track tunggal", "Playlist")
        typeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, typeLabels)
        val targetSpinner = Spinner(this)
        val modeSpinner = Spinner(this)
        fun refreshTargets() {
            val isTrack = typeSpinner.selectedItemPosition == 0
            val labels = if (isTrack) tracks.map { it.title } else playlists.map { it.name }
            targetSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
            val modes = if (isTrack) listOf("Single") else listOf("Sequential", "Shuffle Cycle")
            modeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, modes)
        }
        typeSpinner.onItemSelectedListener = SimpleItemSelectedListener { refreshTargets() }
        val startButton = Button(this)
        val endButton = Button(this)
        val stopSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Berhenti pada jam tertentu", "Berhenti setelah audio selesai"))
            onItemSelectedListener = SimpleItemSelectedListener {
                endButton.visibility = if (selectedItemPosition == 0) View.VISIBLE else View.GONE
            }
        }
        var startMinutes = existing?.startMinutes ?: 300
        var endMinutes = existing?.endMinutes ?: 360
        startButton.text = "Mulai: ${time(startMinutes)}"
        endButton.text = "Berhenti: ${time(endMinutes)}"
        startButton.setOnClickListener { chooseTime(startMinutes) { startMinutes = it; startButton.text = "Mulai: ${time(it)}" } }
        endButton.setOnClickListener { chooseTime(endMinutes) { endMinutes = it; endButton.text = "Berhenti: ${time(it)}" } }
        val volumeLabel = label("Volume: 60%")
        val volume = SeekBar(this).apply {
            max = 100
            progress = existing?.volumePercent ?: 60
            setOnSeekBarChangeListener(SimpleSeekListener { volumeLabel.text = "Volume: $it%" })
        }
        volumeLabel.text = "Volume: ${volume.progress}%"
        val daysTitle = label("Hari pemutaran")
        val dayRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val dayValues = listOf(2, 3, 4, 5, 6, 7, 1)
        val dayLabels = listOf("Sen", "Sel", "Rab", "Kam", "Jum", "Sab", "Min")
        val dayChecks = dayValues.mapIndexed { index, _ ->
            CheckBox(this).apply {
                text = dayLabels[index]
                isChecked = existing?.days?.contains(dayValues[index]) ?: true
                buttonTintList = null
                dayRow.addView(this, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
        val fadeCheck = CheckBox(this).apply {
            text = "Fade-in 10 detik saat jadwal dimulai"
            isChecked = existing?.fadeIn ?: false
        }
        val equalizerCheck = CheckBox(this).apply {
            text = "Aktifkan equalizer untuk jadwal ini"
            isChecked = existing?.equalizerEnabled ?: false
        }
        val equalizerContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val equalizerPresets = listOf("Normal", "Voice", "Room", "Concert", "Ballroom", "Hall", "Plate", "Custom")
        val equalizerPreset = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, equalizerPresets)
        }
        equalizerContainer.addView(label("Preset equalizer dan efek ruang"))
        equalizerContainer.addView(equalizerPreset)
        val frequencies = listOf("60 Hz", "230 Hz", "910 Hz", "3,6 kHz", "14 kHz")
        val equalizerValues = (existing?.equalizerBands ?: listOf(0, 0, 0, 0, 0)).toMutableList()
        val equalizerLabels = mutableListOf<TextView>()
        val equalizerSliders = mutableListOf<SeekBar>()
        var applyingPreset = false
        frequencies.forEachIndexed { index, frequency ->
            val bandLabel = label("$frequency: ${formatDb(equalizerValues[index])}")
            val bandSlider = SeekBar(this).apply {
                max = 200
                progress = equalizerValues[index] + 100
                setOnSeekBarChangeListener(SimpleSeekListener { progress ->
                    equalizerValues[index] = progress - 100
                    bandLabel.text = "$frequency: ${formatDb(equalizerValues[index])}"
                    if (!applyingPreset && equalizerPreset.selectedItem?.toString() != "Custom") {
                        equalizerPreset.setSelection(equalizerPresets.indexOf("Custom"))
                    }
                })
            }
            equalizerLabels.add(bandLabel); equalizerSliders.add(bandSlider)
            equalizerContainer.addView(bandLabel); equalizerContainer.addView(bandSlider)
        }
        var initialEqualizerSelection = true
        equalizerPreset.onItemSelectedListener = SimpleItemSelectedListener {
            if (!initialEqualizerSelection) {
                val presetValues = equalizerPresetValues(equalizerPreset.selectedItem.toString())
                if (presetValues != null) {
                    applyingPreset = true
                    presetValues.forEachIndexed { index, value ->
                        equalizerValues[index] = value
                        equalizerSliders[index].progress = value + 100
                        equalizerLabels[index].text = "${frequencies[index]}: ${formatDb(value)}"
                    }
                    applyingPreset = false
                }
            }
        }
        equalizerPreset.setSelection(equalizerPresets.indexOf(existing?.equalizerPreset ?: "Normal").coerceAtLeast(0))
        equalizerPreset.post { initialEqualizerSelection = false }
        equalizerContainer.visibility = if (equalizerCheck.isChecked) View.VISIBLE else View.GONE
        equalizerCheck.setOnCheckedChangeListener { _, checked ->
            equalizerContainer.visibility = if (checked) View.VISIBLE else View.GONE
        }
        form.addView(name)
        form.addView(daysTitle); form.addView(dayRow)
        form.addView(label("Jenis sumber")); form.addView(typeSpinner)
        form.addView(label("Audio atau playlist")); form.addView(targetSpinner)
        form.addView(label("Mode pemutaran")); form.addView(modeSpinner)
        form.addView(startButton)
        form.addView(label("Cara berhenti")); form.addView(stopSpinner); form.addView(endButton)
        form.addView(volumeLabel); form.addView(volume)
        form.addView(fadeCheck)
        form.addView(equalizerCheck)
        form.addView(equalizerContainer)
        if (existing != null) {
            typeSpinner.setSelection(if (existing.targetType == "track") 0 else 1)
        }
        refreshTargets()
        if (existing != null) {
            val targets = if (existing.targetType == "track") tracks.map { it.id } else playlists.map { it.id }
            targetSpinner.setSelection(targets.indexOf(existing.targetId).coerceAtLeast(0))
            if (existing.targetType == "playlist") modeSpinner.setSelection(if (existing.playbackMode == "shuffle_cycle") 1 else 0)
            stopSpinner.setSelection(if (existing.stopMode == "time") 0 else 1)
        }

        val scroll = ScrollView(this).apply { addView(form) }
        MaterialAlertDialogBuilder(this).setTitle(if (existing == null) "Jadwal baru" else "Edit jadwal").setView(scroll)
            .setNegativeButton("Batal", null).setPositiveButton("Simpan") { _, _ ->
                if (name.text.isBlank()) {
                    toast("Nama jadwal wajib diisi")
                    return@setPositiveButton
                }
                val isTrack = typeSpinner.selectedItemPosition == 0
                val targetId = if (isTrack) tracks[targetSpinner.selectedItemPosition].id else playlists[targetSpinner.selectedItemPosition].id
                val selectedDays = dayValues.filterIndexed { index, _ -> dayChecks[index].isChecked }
                if (selectedDays.isEmpty()) {
                    toast("Pilih minimal satu hari")
                    return@setPositiveButton
                }
                val playbackMode = if (isTrack) "single" else if (modeSpinner.selectedItemPosition == 0) "sequential" else "shuffle_cycle"
                val item = PlaybackSchedule(existing?.id ?: UUID.randomUUID().toString(), name.text.toString().trim(),
                    if (isTrack) "track" else "playlist", targetId, startMinutes, endMinutes, volume.progress,
                    playbackMode, if (stopSpinner.selectedItemPosition == 0) "time" else "after_source",
                    selectedDays, fadeCheck.isChecked, equalizerCheck.isChecked,
                    equalizerPreset.selectedItem.toString(), equalizerValues.toList(), existing?.enabled ?: true)
                val schedules = store.schedules()
                if (existing == null) schedules.add(item) else {
                    AlarmScheduler.cancel(this, existing.id)
                    val position = schedules.indexOfFirst { it.id == existing.id }
                    if (position >= 0) schedules[position] = item else schedules.add(item)
                }
                store.saveSchedules(schedules)
                AlarmScheduler.schedule(this, item)
                showSchedules()
            }.show()
    }

    private fun play(uris: List<String>, title: String) {
        val intent = Intent(this, PlaybackService::class.java).apply {
            action = PlaybackService.ACTION_PLAY
            putStringArrayListExtra(PlaybackService.EXTRA_URIS, ArrayList(uris))
            putExtra(PlaybackService.EXTRA_TITLE, title)
        }
        ContextCompat.startForegroundService(this, intent)
        nowPlaying.text = "Sedang diputar: $title"
    }

    private fun chooseTime(current: Int, result: (Int) -> Unit) {
        TimePickerDialog(this, { _, hour, minute -> result(hour * 60 + minute) }, current / 60, current % 60, true).show()
    }

    private fun card(title: String, subtitle: String, actionText: String, action: () -> Unit,
                     secondText: String?, secondAction: (() -> Unit)?,
                     thirdText: String? = null, thirdAction: (() -> Unit)? = null): View {
        val box = verticalContainer().apply {
            setBackgroundColor(0xFFFFFFFF.toInt())
            setPadding(dp(16), dp(14), dp(16), dp(14))
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            params.setMargins(0, 0, 0, dp(12)); layoutParams = params
            elevation = dp(2).toFloat()
        }
        box.addView(TextView(this).apply { text = title; textSize = 17f; setTextColor(0xFF1B2521.toInt()); setTypeface(typeface, Typeface.BOLD) })
        box.addView(TextView(this).apply { text = subtitle; textSize = 14f; setTextColor(0xFF53615B.toInt()); setPadding(0, dp(4), 0, dp(8)) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply { text = actionText; setOnClickListener { action() } })
        if (secondText != null && secondAction != null) row.addView(Button(this).apply { text = secondText; setOnClickListener { secondAction() } })
        if (thirdText != null && thirdAction != null) row.addView(Button(this).apply { text = thirdText; setOnClickListener { thirdAction() } })
        box.addView(row)
        return box
    }

    private fun primaryButton(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text
        setOnClickListener { action() }
        val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        params.setMargins(0, 0, 0, dp(14)); layoutParams = params
    }

    private fun emptyText(message: String) = TextView(this).apply {
        text = message; textSize = 15f; setTextColor(0xFF53615B.toInt()); setPadding(dp(8), dp(20), dp(8), dp(20))
    }

    private fun label(textValue: String) = TextView(this).apply {
        text = textValue; textSize = 14f; setTextColor(0xFF1B2521.toInt()); setPadding(0, dp(12), 0, dp(4))
    }

    private fun verticalContainer() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(8), dp(16), dp(8))
    }

    private fun confirmDelete(message: String, action: () -> Unit) {
        MaterialAlertDialogBuilder(this).setMessage(message).setNegativeButton("Batal", null)
            .setPositiveButton("Hapus") { _, _ -> action() }.show()
    }

    private fun displayName(uri: Uri): String {
        var result = "Audio"
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) result = cursor.getString(0) ?: result
        }
        return result
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 200)
        }
    }

    private fun time(minutes: Int) = String.format(Locale.getDefault(), "%02d:%02d", minutes / 60, minutes % 60)
    private fun formatDb(value: Int): String = String.format(Locale.getDefault(), "%+.1f dB", value / 10f)
    private fun equalizerPresetValues(name: String): List<Int>? = when (name) {
        "Voice" -> listOf(-20, -5, 25, 35, 10)
        "Room" -> listOf(10, 5, 0, 5, 10)
        "Concert" -> listOf(15, 5, -5, 10, 20)
        "Ballroom" -> listOf(10, 10, 0, 10, 15)
        "Hall" -> listOf(5, 0, -5, 10, 20)
        "Plate" -> listOf(0, 5, 5, 10, 10)
        "Normal" -> listOf(0, 0, 0, 0, 0)
        else -> null
    }
    private fun daySummary(days: List<Int>): String {
        if (days.size == 7) return "Setiap hari"
        val labels = mapOf(1 to "Min", 2 to "Sen", 3 to "Sel", 4 to "Rab", 5 to "Kam", 6 to "Jum", 7 to "Sab")
        return days.sortedBy { if (it == 1) 8 else it }.joinToString(", ") { labels[it] ?: "" }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}

private class SimpleItemSelectedListener(private val selected: () -> Unit) : android.widget.AdapterView.OnItemSelectedListener {
    override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) = selected()
    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
}

private class SimpleSeekListener(private val changed: (Int) -> Unit) : SeekBar.OnSeekBarChangeListener {
    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = changed(progress)
    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
    override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
}
