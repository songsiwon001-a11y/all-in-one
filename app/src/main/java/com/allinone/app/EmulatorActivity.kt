package com.allinone.app

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * EmulatorJS(libretro 코어를 WebAssembly로 구동)로 게임/프로그램을 실행한다.
 * 아케이드(FBNeo, MAME), DOS, NES, SNES, GBA, PSX, N64 등.
 *
 * extras: path = 실행할 파일, core = EmulatorJS 코어 이름
 */
class EmulatorActivity : Activity() {

    private lateinit var web: WebView
    private var tmpZip: File? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = File(intent.getStringExtra("path") ?: return finish())
        val core = intent.getStringExtra("core") ?: return finish()
        title = target.name

        // 실제로 에뮬레이터에 넘길 파일과 확장자 결정
        var gameFile = target
        var gameExt = target.extension.lowercase()
        if (core == "dosbox_pure" && gameExt != "zip") {
            // exe/com/bat는 같은 폴더를 zip으로 묶어서 전달 (DOSBox Pure는 zip 안의 실행 파일을 찾는다)
            try {
                val up = intent.getIntExtra("up", 0)
                val skipped = File(cacheDir, "dos_run.zip").also { tmpZip = it }
                    .let { buildDosZip(target, it, up) }
                gameFile = tmpZip!!
                gameExt = "zip"
                if (skipped > 0) Toast.makeText(
                    this, "용량 제한으로 파일 ${skipped}개를 제외했습니다", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "준비 실패: ${e.message}", Toast.LENGTH_LONG).show()
                finish(); return
            }
        }
        val served = gameFile

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView, request: WebResourceRequest
                ): WebResourceResponse? {
                    val url = request.url
                    if (url.host != "allinone.local") return null
                    val headers = mapOf("Access-Control-Allow-Origin" to "*")
                    val p = url.path ?: return null
                    return when {
                        p == "/emu.html" -> WebResourceResponse(
                            "text/html", "utf-8", 200, "OK", headers, assets.open("emu.html"))
                        p.startsWith("/game.") -> WebResourceResponse(
                            "application/octet-stream", null, 200, "OK", headers, served.inputStream())
                        else -> null
                    }
                }
            }
        }
        setContentView(web)

        val name = URLEncoder.encode(target.nameWithoutExtension, "UTF-8")
        web.loadUrl("https://allinone.local/emu.html?core=$core&game=/game.$gameExt&name=$name")
    }

    /**
     * 게임 폴더 전체(하위 폴더 포함)를 zip으로 묶고, DOSBox Pure가 바로 실행하도록
     * 루트에 DOSBOX.CONF(autoexec)를 넣는다. up > 0 이면 exe가 있는 폴더의 상위 폴더를 루트로 삼는다.
     * @return 용량 제한으로 제외된 파일 수
     */
    private fun buildDosZip(target: File, out: File, up: Int): Int {
        var root = target.parentFile ?: throw IllegalStateException("폴더 없음")
        repeat(up) { root = root.parentFile ?: root }
        val limit = 400L * 1024 * 1024
        var total = 0L
        var skipped = 0

        val rootPath = root.toPath()
        fun rel(f: File) = rootPath.relativize(f.toPath()).toString().replace(File.separatorChar, '/')

        val files = root.walkTopDown().filter { it.isFile }.toList()
            .sortedBy { if (it == target) 0 else 1 }   // 실행 파일 우선 포함
        val hasConf = files.any { rel(it).equals("dosbox.conf", ignoreCase = true) }

        ZipOutputStream(FileOutputStream(out)).use { zip ->
            zip.setLevel(java.util.zip.Deflater.NO_COMPRESSION)  // 빠르게 묶기

            if (!hasConf) {
                val relDir = rel(target).substringBeforeLast('/', "").replace('/', '\\')
                val runCmd = if (target.extension.equals("bat", true)) "call ${target.name}" else target.name
                val conf = buildString {
                    append("[autoexec]\r\n@echo off\r\nc:\r\n")
                    if (relDir.isNotEmpty()) append("cd \\$relDir\r\n")
                    append(runCmd).append("\r\n")
                }
                zip.putNextEntry(ZipEntry("DOSBOX.CONF"))
                zip.write(conf.toByteArray())
                zip.closeEntry()
            }

            for (f in files) {
                if (total + f.length() > limit) { skipped++; continue }
                zip.putNextEntry(ZipEntry(rel(f)))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                total += f.length()
            }
        }
        return skipped
    }

    override fun onDestroy() {
        web.destroy()
        tmpZip?.delete()
        super.onDestroy()
    }
}
