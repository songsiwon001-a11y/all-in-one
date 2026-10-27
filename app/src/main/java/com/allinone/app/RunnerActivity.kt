package com.allinone.app

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.File

/** HTML / SVG / JS 파일을 WebView에서 즉석 실행한다. */
class RunnerActivity : Activity() {

    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val file = File(intent.getStringExtra("path") ?: return finish())
        title = file.name

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            @Suppress("DEPRECATION")
            settings.allowFileAccessFromFileURLs = true
            @Suppress("DEPRECATION")
            settings.allowUniversalAccessFromFileURLs = true
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
        }
        setContentView(web)

        when (file.extension.lowercase()) {
            "js", "mjs" -> {
                // JS 단독 파일: 콘솔 출력을 화면에 보여주는 래퍼 페이지를 만든다.
                val code = file.readText().replace("</script>", "<\\/script>")
                val page = """
                    <html><body style="font-family:monospace;background:#111;color:#0f0;padding:8px">
                    <pre id="out"></pre>
                    <script>
                    const out=document.getElementById('out');
                    const log=(...a)=>{out.textContent+=a.map(x=>typeof x==='object'?JSON.stringify(x):String(x)).join(' ')+'\n'};
                    console.log=log;console.error=(...a)=>log('[error]',...a);
                    window.onerror=(m,s,l)=>log('[error]',m,'line',l);
                    </script>
                    <script>$code</script></body></html>
                """.trimIndent()
                web.loadDataWithBaseURL("file://${file.parentFile?.absolutePath}/", page, "text/html", "utf-8", null)
            }
            "svg" -> web.loadUrl("file://${file.absolutePath}")
            else -> web.loadUrl("file://${file.absolutePath}")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
