package com.allinone.app

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * DOS 프로그램(.exe/.com/.bat)을 js-dos(DOSBox WebAssembly)로 실행한다.
 * 실행 파일이 있는 폴더(up=1이면 상위 폴더)를 하위 폴더까지 통째로 .jsdos 번들로 묶어
 * C: 드라이브로 마운트하고 실행 파일을 자동으로 시작한다.
 * 16/32비트 DOS 프로그램만 동작하며 Windows(GUI) 프로그램은 실행되지 않는다.
 */
class DosActivity : Activity() {

    private var web: WebView? = null
    private lateinit var bundle: File
    private lateinit var status: TextView
    @Volatile private var destroyed = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = File(intent.getStringExtra("path") ?: return finish())
        val up = intent.getIntExtra("up", 0)
        title = "DOS · ${target.name}"
        bundle = File(cacheDir, "run.jsdos")

        status = TextView(this).apply {
            text = "게임 파일 준비 중…"
            textSize = 16f
            gravity = Gravity.CENTER
            setBackgroundColor(0xFF000000.toInt())
            setTextColor(0xFFFFFFFF.toInt())
        }
        setContentView(status)

        // 폴더가 크면 시간이 걸리므로 백그라운드에서 묶는다.
        Thread {
            try {
                val info = buildBundle(target, bundle, up)
                runOnUiThread {
                    if (destroyed) return@runOnUiThread
                    if (info.skipped > 0) Toast.makeText(
                        this, "용량 제한으로 파일 ${info.skipped}개를 제외했습니다", Toast.LENGTH_LONG).show()
                    startWeb()
                }
            } catch (e: Throwable) {   // OutOfMemoryError 포함
                bundle.delete()
                runOnUiThread {
                    status.text = "준비 실패: ${e.javaClass.simpleName}\n${e.message ?: ""}\n\n" +
                        "폴더가 너무 크면 게임 폴더만 따로 두고 다시 시도하세요."
                }
            }
        }.start()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun startWeb() {
        val w = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                    android.util.Log.d("AllInOneDOS", "${m.message()} (${m.sourceId()}:${m.lineNumber()})")
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                // WebView 렌더러가 메모리 부족 등으로 죽어도 앱이 같이 종료되지 않게 한다.
                override fun onRenderProcessGone(
                    view: WebView, detail: android.webkit.RenderProcessGoneDetail
                ): Boolean {
                    runOnUiThread {
                        web?.destroy(); web = null
                        status.text = "DOS 화면이 메모리 부족 등으로 종료되었습니다.\n" +
                            "게임 폴더 용량이 크면 줄여서 다시 시도하세요."
                        setContentView(status)
                    }
                    return true
                }

                override fun shouldInterceptRequest(
                    view: WebView, request: WebResourceRequest
                ): WebResourceResponse? {
                    val url = request.url
                    if (url.host != "allinone.local") return null
                    val headers = mapOf("Access-Control-Allow-Origin" to "*")
                    return when (url.path) {
                        "/dos.html" -> WebResourceResponse(
                            "text/html", "utf-8", 200, "OK", headers, assets.open("dos.html"))
                        "/run.jsdos" -> WebResourceResponse(
                            "application/octet-stream", null, 200, "OK", headers, bundle.inputStream())
                        else -> null
                    }
                }
            }
        }
        web = w
        setContentView(w)
        w.loadUrl("https://allinone.local/dos.html")
    }

    private class BundleInfo(val skipped: Int)

    private fun buildBundle(target: File, out: File, up: Int): BundleInfo {
        var root = target.parentFile ?: throw IllegalStateException("폴더 없음")
        repeat(up) { root = root.parentFile ?: root }
        // WebView가 번들 전체를 메모리에 올리므로 용량·파일 수를 보수적으로 제한한다.
        val limit = 150L * 1024 * 1024
        val maxFiles = 3000
        var total = 0L
        var skipped = 0

        // 저장소 최상위 같은 너무 넓은 폴더를 통째로 묶지 않도록 막는다.
        val storageRoot = android.os.Environment.getExternalStorageDirectory().absolutePath
        if (root.absolutePath == storageRoot) root = target.parentFile ?: root

        fun rel(f: File) = f.relativeTo(root).path.replace(File.separatorChar, '/')

        // 작은 파일부터 담아 용량 제한에 걸려도 게임 파일이 최대한 들어가게 한다.
        val all = ArrayList<File>()
        for (f in root.walkTopDown()) {
            if (f.isFile && f != target) all.add(f)
            if (all.size > maxFiles) break
        }
        if (all.size > maxFiles) skipped += all.size - maxFiles
        val files = all.take(maxFiles).sortedBy { it.length() }

        ZipOutputStream(FileOutputStream(out)).use { zip ->
            zip.setLevel(Deflater.NO_COMPRESSION)

            val relDir = rel(target).substringBeforeLast('/', "").replace('/', '\\')
            val runCmd = if (target.extension.equals("bat", true)) "call ${target.name}" else target.name
            val conf = buildString {
                append("[autoexec]\r\n")
                append("@echo off\r\n")
                append("mount c .\r\n")
                append("c:\r\n")
                if (relDir.isNotEmpty()) append("cd \\$relDir\r\n")
                append(runCmd).append("\r\n")
            }
            zip.putNextEntry(ZipEntry(".jsdos/dosbox.conf"))
            zip.write(conf.toByteArray())
            zip.closeEntry()

            // 실행 파일은 항상 포함
            zip.putNextEntry(ZipEntry(rel(target)))
            target.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
            total += target.length()

            for (f in files) {
                if (total + f.length() > limit) { skipped++; continue }
                zip.putNextEntry(ZipEntry(rel(f)))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                total += f.length()
            }
        }
        return BundleInfo(skipped)
    }

    override fun onDestroy() {
        destroyed = true
        web?.destroy()
        bundle.delete()
        super.onDestroy()
    }
}
