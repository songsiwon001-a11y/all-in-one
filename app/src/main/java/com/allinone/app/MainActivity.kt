package com.allinone.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 파일 관리자 메인 화면. 확장자에 따라 알맞은 실행기로 연결한다. */
class MainActivity : Activity() {

    private lateinit var pathView: TextView
    private lateinit var listView: ListView
    private var current: File = Environment.getExternalStorageDirectory()
    private var items: List<File> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "All in One"

        pathView = TextView(this).apply {
            setPadding(24, 20, 24, 20)
            textSize = 13f
            setBackgroundColor(0xFFEEEEEE.toInt())
        }
        listView = ListView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(pathView, ViewGroup.LayoutParams(-1, -2))
            addView(listView, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        setContentView(root)

        listView.setOnItemClickListener { _, _, pos, _ -> onClickItem(items[pos]) }
        listView.setOnItemLongClickListener { _, _, pos, _ -> showMenu(items[pos]); true }

        requestStorageAccess()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:$packageName"))
                    )
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }
        } else {
            requestPermissions(arrayOf(
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ), 1)
        }
    }

    private fun refresh() {
        val list = current.listFiles()?.toList() ?: emptyList()
        items = list.sortedWith(compareByDescending<File> { it.isDirectory }
            .thenBy { it.name.lowercase(Locale.ROOT) })
        pathView.text = current.absolutePath
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val labels = items.map {
            val icon = if (it.isDirectory) "📁" else iconFor(it)
            val info = if (it.isDirectory) "" else "  ·  ${humanSize(it.length())}"
            "$icon  ${it.name}\n      ${fmt.format(Date(it.lastModified()))}$info"
        }
        listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
    }

    private fun iconFor(f: File) = when (f.extension.lowercase()) {
        "html", "htm" -> "🌐"
        "exe", "com", "bat" -> "🖥️"
        "nes", "sfc", "smc", "gba", "gb", "gbc", "n64", "z64", "nds", "md", "gen", "sms", "gg" -> "🎮"
        "js" -> "📜"
        "png", "jpg", "jpeg", "gif", "webp", "bmp" -> "🖼️"
        "mp3", "wav", "ogg", "flac", "m4a" -> "🎵"
        "mp4", "mkv", "avi", "webm", "3gp" -> "🎬"
        "zip", "rar", "7z", "tar", "gz" -> "🗜️"
        "apk" -> "📦"
        "txt", "md", "json", "xml", "csv", "log", "kt", "java", "py", "css" -> "📝"
        else -> "📄"
    }

    private fun humanSize(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
        b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / 1048576.0)
        else -> "%.2f GB".format(b / 1073741824.0)
    }

    private fun onClickItem(f: File) {
        if (f.isDirectory) {
            current = f
            refresh()
        } else {
            openFile(f)
        }
    }

    /** 확장자별 실행 분기 */
    private fun openFile(f: File) {
        when (f.extension.lowercase()) {
            "html", "htm", "xhtml", "svg", "js", "mjs" ->
                startActivity(Intent(this, RunnerActivity::class.java).putExtra("path", f.absolutePath))
            "zip", "7z" -> askArchive(f)
            "txt", "md", "json", "xml", "csv", "log", "kt", "java", "py", "css", "ini", "cfg",
            "yml", "yaml", "gradle", "kts", "sh", "c", "cpp", "h" ->
                startActivity(Intent(this, TextActivity::class.java).putExtra("path", f.absolutePath))
            else -> {
                val core = EMU_CORES[f.extension.lowercase()]
                if (core != null) launchEmu(f, core) else openWithSystem(f)
            }
        }
    }

    private fun launchEmu(f: File, core: String) {
        startActivity(
            Intent(this, EmulatorActivity::class.java)
                .putExtra("path", f.absolutePath).putExtra("core", core)
        )
    }

    /** zip/7z는 아케이드 ROM일 수도, 일반 압축 파일일 수도 있어 묻는다. */
    private fun askArchive(f: File) {
        val names = arrayOf("아케이드 (FBNeo)", "아케이드 (MAME 2003)", "DOS 프로그램", "다른 앱으로 열기")
        AlertDialog.Builder(this).setTitle(f.name)
            .setItems(names) { _, which ->
                when (which) {
                    0 -> launchEmu(f, "fbneo")
                    1 -> launchEmu(f, "mame2003")
                    2 -> launchEmu(f, "dosbox_pure")
                    else -> openWithSystem(f)
                }
            }.show()
    }

    private fun chooseEmulator(f: File) {
        val labels = EMU_CHOICES.map { it.first }.toTypedArray()
        AlertDialog.Builder(this).setTitle("에뮬레이터 선택")
            .setItems(labels) { _, which -> launchEmu(f, EMU_CHOICES[which].second) }.show()
    }

    companion object {
        /** 확장자 → EmulatorJS 코어 (zip/7z는 모호해서 askArchive에서 선택) */
        val EMU_CORES = mapOf(
            "exe" to "dosbox_pure", "com" to "dosbox_pure", "bat" to "dosbox_pure",
            "nes" to "fceumm", "fds" to "fceumm", "unf" to "fceumm",
            "sfc" to "snes9x", "smc" to "snes9x",
            "gba" to "mgba", "gb" to "gambatte", "gbc" to "gambatte",
            "n64" to "mupen64plus_next", "z64" to "mupen64plus_next", "v64" to "mupen64plus_next",
            "nds" to "melonds",
            "md" to "genesis_plus_gx", "gen" to "genesis_plus_gx", "smd" to "genesis_plus_gx",
            "gg" to "genesis_plus_gx", "sms" to "smsplus", "32x" to "picodrive",
            "a26" to "stella2014", "a78" to "prosystem", "lnx" to "handy",
            "pce" to "mednafen_pce", "ngp" to "mednafen_ngp", "ws" to "mednafen_wswan",
            "wsc" to "mednafen_wswan", "vb" to "beetle_vb",
            "cue" to "pcsx_rearmed", "pbp" to "pcsx_rearmed", "chd" to "pcsx_rearmed"
        )

        val EMU_CHOICES = listOf(
            "아케이드 (FBNeo)" to "fbneo",
            "아케이드 (MAME 2003)" to "mame2003",
            "DOS (DOSBox Pure)" to "dosbox_pure",
            "NES" to "fceumm",
            "SNES" to "snes9x",
            "Game Boy / Color" to "gambatte",
            "Game Boy Advance" to "mgba",
            "Nintendo 64" to "mupen64plus_next",
            "Nintendo DS" to "melonds",
            "Sega 메가드라이브" to "genesis_plus_gx",
            "PlayStation" to "pcsx_rearmed",
            "Atari 2600" to "stella2014"
        )
    }

    private fun openWithSystem(f: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            val mime = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "열기"
                )
            )
        } catch (e: Exception) {
            // 지원 앱이 없으면 텍스트로 시도
            startActivity(Intent(this, TextActivity::class.java).putExtra("path", f.absolutePath))
        }
    }

    private fun showMenu(f: File) {
        val actions = mutableListOf("열기 (시스템 앱)", "이름 바꾸기", "삭제")
        if (f.isFile) {
            actions.add("HTML로 실행")
            actions.add("에뮬레이터로 실행")
            actions.add("텍스트로 열기")
        }
        AlertDialog.Builder(this).setTitle(f.name)
            .setItems(actions.toTypedArray()) { _, which ->
                when (actions[which]) {
                    "열기 (시스템 앱)" -> if (f.isDirectory) onClickItem(f) else openWithSystem(f)
                    "이름 바꾸기" -> rename(f)
                    "삭제" -> confirmDelete(f)
                    "HTML로 실행" ->
                        startActivity(Intent(this, RunnerActivity::class.java).putExtra("path", f.absolutePath))
                    "에뮬레이터로 실행" -> chooseEmulator(f)
                    "텍스트로 열기" ->
                        startActivity(Intent(this, TextActivity::class.java).putExtra("path", f.absolutePath))
                }
            }.show()
    }

    private fun rename(f: File) {
        val input = EditText(this).apply { setText(f.name) }
        AlertDialog.Builder(this).setTitle("이름 바꾸기").setView(input)
            .setPositiveButton("확인") { _, _ ->
                val dest = File(f.parentFile, input.text.toString())
                if (!f.renameTo(dest)) toast("실패했습니다")
                refresh()
            }.setNegativeButton("취소", null).show()
    }

    private fun confirmDelete(f: File) {
        AlertDialog.Builder(this).setTitle("삭제").setMessage("${f.name} 을(를) 삭제할까요?")
            .setPositiveButton("삭제") { _, _ ->
                if (!f.deleteRecursively()) toast("실패했습니다")
                refresh()
            }.setNegativeButton("취소", null).show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val root = Environment.getExternalStorageDirectory()
        val parent = current.parentFile
        if (current.absolutePath != root.absolutePath && parent != null) {
            current = parent
            refresh()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }
}
