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
                tmpZip = File(cacheDir, "dos_run.zip").also { buildDosZip(target, it) }
                gameFile = tmpZip!!
                gameExt = "zip"
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

    private fun buildDosZip(target: File, out: File) {
        val dir = target.parentFile ?: throw IllegalStateException("폴더 없음")
        val limit = 64L * 1024 * 1024
        var total = 0L
        ZipOutputStream(FileOutputStream(out)).use { zip ->
            val files = listOf(target) + (dir.listFiles()?.filter { it.isFile && it != target } ?: emptyList())
            for (f in files) {
                if (total + f.length() > limit) continue
                zip.putNextEntry(ZipEntry(f.name))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                total += f.length()
            }
        }
    }

    override fun onDestroy() {
        web.destroy()
        tmpZip?.delete()
        super.onDestroy()
    }
}
