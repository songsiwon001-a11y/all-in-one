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
    // 실행할 때마다 달라지는 번호: js-dos가 주소를 기준으로 게임 파일을 저장해 두고 재사용하는 것을 막는다.
    private val launchId = System.currentTimeMillis()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = File(intent.getStringExtra("path") ?: return finish())
        val up = intent.getIntExtra("up", 0)
        title = "DOS · ${target.name}"
        bundle = File(cacheDir, "run_$launchId.jsdos")
        // 이전 실행에서 남은 번들 정리
        cacheDir.listFiles { f -> f.name.startsWith("run") && f.name.endsWith(".jsdos") }
            ?.forEach { it.delete() }

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
            settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                    android.util.Log.d("AllInOneDOS", "${m.message()} (${m.sourceId()}:${m.lineNumber()})")
                    if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) report("console: ${m.message()}")
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onReceivedError(
                    view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError
                ) {
                    report("${request.url} -> ${error.description} (${error.errorCode})")
                }

                override fun onReceivedHttpError(
                    view: WebView, request: WebResourceRequest, resp: WebResourceResponse
                ) {
                    report("HTTP ${resp.statusCode}: ${request.url}")
                }

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
                    val headers = mutableMapOf(
                        "Access-Control-Allow-Origin" to "*",
                        "Cross-Origin-Resource-Policy" to "cross-origin",
                        "Cache-Control" to "no-store"
                    )
                    val path = url.path ?: return null
                    return try {
                        when {
                            path == "/dos.html" -> {
                                // SharedArrayBuffer를 쓸 수 있도록 cross-origin isolation 헤더 추가
                                headers["Cross-Origin-Opener-Policy"] = "same-origin"
                                headers["Cross-Origin-Embedder-Policy"] = "credentialless"
                                WebResourceResponse(
                                    "text/html", "utf-8", 200, "OK", headers,
                                    DOS_HTML.byteInputStream(Charsets.UTF_8))
                            }
                            path.startsWith("/run-") && path.endsWith(".jsdos") -> WebResourceResponse(
                                "application/octet-stream", null, 200, "OK", headers, bundle.inputStream())
                            path.startsWith("/jsdos/") -> {
                                val rel = path.removePrefix("/jsdos/")
                                if (rel.contains("..")) null
                                else WebResourceResponse(
                                    mimeOf(rel), null, 200, "OK", headers, assets.open("jsdos/$rel"))
                            }
                            else -> null
                        }
                    } catch (e: java.io.FileNotFoundException) {
                        WebResourceResponse("text/plain", "utf-8", 404, "Not Found", headers,
                            "not found".byteInputStream())
                    } catch (e: Throwable) {
                        // 이 스레드에서 예외가 나면 앱이 종료되므로 오류 응답으로 바꾼다.
                        WebResourceResponse("text/plain", "utf-8", 500, "Error", headers,
                            "error: ${e.message}".byteInputStream())
                    }
                }
            }
        }
        web = w
        setContentView(w)
        w.loadUrl("https://allinone.local/dos.html?b=$launchId")
    }

    private fun mimeOf(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
        "js", "mjs" -> "application/javascript"
        "css" -> "text/css"
        "wasm" -> "application/wasm"
        "json" -> "application/json"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "html" -> "text/html"
        else -> "application/octet-stream"
    }

    /** 네이티브 쪽에서 감지한 오류를 페이지의 진단 로그에 표시한다. */
    private fun report(msg: String) {
        val w = web ?: return
        w.post {
            w.evaluateJavascript("window.__native && window.__native(" + org.json.JSONObject.quote(msg) + ")", null)
        }
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


/** js-dos 실행 페이지 (assets 파일에 의존하지 않도록 코드에 내장) */
private val DOS_HTML = """<!doctype html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
<style>
  html, body { margin: 0; height: 100%; background: #000; overflow: hidden;
               -webkit-user-select: none; user-select: none; -webkit-touch-callout: none; }
  #dos { width: 100%; height: 100%; }
  #log { position: absolute; left: 0; right: 0; top: 0; z-index: 20; max-height: 50%; overflow: auto;
         color: #9f9; font: 12px/1.45 monospace; padding: 8px; white-space: pre-wrap;
         background: rgba(0,0,0,.75); }

  /* ---- on-screen controls (화면 폭에 맞춰 크기 조절) ---- */
  :root { --b: min(13vw, 60px); }
  #pad { position: absolute; left: 0; right: 0; top: 0; bottom: 0; z-index: 10; pointer-events: none; }
  #pad.hidden .ctl { display: none; }
  .ctl { pointer-events: auto; touch-action: none; position: absolute; }
  #stick { left: 10px; bottom: 14px; width: calc(var(--b) * 2.4); height: calc(var(--b) * 2.4);
           border-radius: 50%; background: rgba(255,255,255,.10); border: 2px solid rgba(255,255,255,.35); }
  #knob { position: absolute; left: 50%; top: 50%; width: var(--b); height: var(--b);
          margin: calc(var(--b) * -0.5) 0 0 calc(var(--b) * -0.5);
          border-radius: 50%; background: rgba(255,255,255,.35); border: 2px solid rgba(255,255,255,.7);
          pointer-events: none; box-sizing: border-box; }
  #btns { right: 8px; bottom: 14px; display: grid; grid-template-columns: repeat(4, var(--b));
          gap: calc(var(--b) * 0.12); }
  .btn { width: var(--b); height: var(--b); border-radius: 50%; box-sizing: border-box;
         background: rgba(255,255,255,.14); border: 2px solid rgba(255,255,255,.45);
         color: #fff; font: bold calc(var(--b) * 0.26) / calc(var(--b) - 4px) sans-serif; text-align: center; }
  .btn.on { background: rgba(80,160,255,.6); }
  .spacer { width: var(--b); height: var(--b); }
  #toggle { pointer-events: auto; position: absolute; right: 10px; top: 10px; width: 42px; height: 42px;
            border-radius: 50%; background: rgba(255,255,255,.18); border: 2px solid rgba(255,255,255,.5);
            color: #fff; font: 20px/38px sans-serif; text-align: center; touch-action: manipulation; }
</style>
</head>
<body>
<div id="log">tap to hide
</div>
<div id="dos"></div>

<div id="pad">
  <div id="toggle">&#127918;</div>
  <div id="stick" class="ctl"><div id="knob"></div></div>
  <div id="btns" class="ctl">
    <div class="btn" data-key="ctrl">Ctrl</div>
    <div class="btn" data-key="enter">Enter</div>
    <div class="btn" data-key="esc">Esc</div>
    <div class="spacer"></div>
    <div class="btn" data-key="a">A</div>
    <div class="btn" data-key="s">S</div>
    <div class="btn" data-key="d">D</div>
    <div class="btn" data-key="space">Space</div>
  </div>
</div>

<script>
(function () {
  var logEl = document.getElementById('log');
  var hadError = false;
  function log(t, isErr) { if (isErr) hadError = true; logEl.textContent += t + '\n'; }
  logEl.onclick = function () { logEl.style.display = 'none'; };
  window.__native = function (t) { log('[native] ' + t, true); };

  log('WebAssembly=' + (typeof WebAssembly) + '  SharedArrayBuffer=' + (typeof SharedArrayBuffer) +
      '  isolated=' + window.crossOriginIsolated);

  window.addEventListener('error', function (e) {
    var what = e.message || (e.target && (e.target.src || e.target.href)) || '?';
    log('[error] ' + what, true);
  }, true);
  window.addEventListener('unhandledrejection', function (e) {
    log('[promise] ' + (e.reason && e.reason.message ? e.reason.message : e.reason), true);
  });

  // ================= 키 입력 =================
  // js-dos CommandInterface(ci)가 쓰는 키 코드(GLFW 방식) + 예비용 브라우저 키 정보
  var KEYS = {
    ctrl:  { glfw: 341, key: 'Control',    code: 'ControlLeft', kc: 17 },
    enter: { glfw: 257, key: 'Enter',      code: 'Enter',       kc: 13 },
    esc:   { glfw: 256, key: 'Escape',     code: 'Escape',      kc: 27 },
    space: { glfw: 32,  key: ' ',          code: 'Space',       kc: 32 },
    a:     { glfw: 65,  key: 'a',          code: 'KeyA',        kc: 65 },
    s:     { glfw: 83,  key: 's',          code: 'KeyS',        kc: 83 },
    d:     { glfw: 68,  key: 'd',          code: 'KeyD',        kc: 68 },
    left:  { glfw: 263, key: 'ArrowLeft',  code: 'ArrowLeft',   kc: 37 },
    up:    { glfw: 265, key: 'ArrowUp',    code: 'ArrowUp',     kc: 38 },
    right: { glfw: 262, key: 'ArrowRight', code: 'ArrowRight',  kc: 39 },
    down:  { glfw: 264, key: 'ArrowDown',  code: 'ArrowDown',   kc: 40 }
  };
  var ci = null;
  var ciReady = false;
  var held = {};

  function domKey(type, k) {
    var ev = new KeyboardEvent(type, { key: k.key, code: k.code, keyCode: k.kc, which: k.kc,
                                       bubbles: true, cancelable: true });
    try { Object.defineProperty(ev, 'keyCode', { get: function () { return k.kc; } }); } catch (e) {}
    try { Object.defineProperty(ev, 'which', { get: function () { return k.kc; } }); } catch (e) {}
    (document.activeElement || document.body).dispatchEvent(ev);
  }

  function setKey(name, down) {
    if (!!held[name] === down) return;
    held[name] = down;
    var k = KEYS[name];
    if (ci && typeof ci.sendKeyEvent === 'function') {
      try { ci.sendKeyEvent(k.glfw, down); return; } catch (e) { log('[key] ci error: ' + e, true); }
    }
    domKey(down ? 'keydown' : 'keyup', k);
  }

  function releaseAll() { for (var n in held) { if (held[n]) setKey(n, false); } }
  window.addEventListener('blur', releaseAll);
  document.addEventListener('visibilitychange', function () { if (document.hidden) releaseAll(); });

  // ---- 버튼 (멀티터치 지원) ----
  var btns = document.querySelectorAll('.btn');
  for (var bi = 0; bi < btns.length; bi++) {
    (function (el) {
      var name = el.getAttribute('data-key');
      el.addEventListener('pointerdown', function (e) {
        e.preventDefault();
        try { el.setPointerCapture(e.pointerId); } catch (x) {}
        el.classList.add('on'); setKey(name, true);
      });
      var up = function (e) { e.preventDefault(); el.classList.remove('on'); setKey(name, false); };
      el.addEventListener('pointerup', up);
      el.addEventListener('pointercancel', up);
      el.addEventListener('contextmenu', function (e) { e.preventDefault(); });
    })(btns[bi]);
  }

  // ---- 조이스틱 -> 방향키 ----
  var stick = document.getElementById('stick');
  var knob = document.getElementById('knob');
  var stickId = null;
  function stickMove(e) {
    var r = stick.getBoundingClientRect();
    var cx = r.left + r.width / 2, cy = r.top + r.height / 2;
    var dx = e.clientX - cx, dy = e.clientY - cy;
    var R = r.width / 2, mag = Math.sqrt(dx * dx + dy * dy);
    var kx = dx, ky = dy;
    if (mag > R) { kx = dx * R / mag; ky = dy * R / mag; }
    knob.style.transform = 'translate(' + kx + 'px,' + ky + 'px)';
    var dz = R * 0.30, ax = Math.abs(dx), ay = Math.abs(dy);
    var horiz = ax > dz && ax > 0.5 * ay;
    var vert = ay > dz && ay > 0.5 * ax;
    setKey('left',  horiz && dx < 0);
    setKey('right', horiz && dx > 0);
    setKey('up',    vert && dy < 0);
    setKey('down',  vert && dy > 0);
  }
  function stickEnd() {
    stickId = null;
    knob.style.transform = 'translate(0,0)';
    setKey('left', false); setKey('right', false); setKey('up', false); setKey('down', false);
  }
  stick.addEventListener('pointerdown', function (e) {
    e.preventDefault();
    stickId = e.pointerId;
    try { stick.setPointerCapture(e.pointerId); } catch (x) {}
    stickMove(e);
  });
  stick.addEventListener('pointermove', function (e) { if (e.pointerId === stickId) { e.preventDefault(); stickMove(e); } });
  stick.addEventListener('pointerup', function (e) { if (e.pointerId === stickId) stickEnd(); });
  stick.addEventListener('pointercancel', function (e) { if (e.pointerId === stickId) stickEnd(); });

  // ---- 컨트롤 숨기기/보이기 ----
  document.getElementById('toggle').addEventListener('click', function () {
    var pad = document.getElementById('pad');
    pad.classList.toggle('hidden');
    if (pad.classList.contains('hidden')) { releaseAll(); stickEnd(); }
  });

  // ---- 키보드: I J K L 을 방향키로 ----
  var IJKL = { KeyI: 'up', KeyJ: 'left', KeyK: 'down', KeyL: 'right' };
  function ijkl(e) {
    var name = IJKL[e.code];
    if (!name) return;
    e.preventDefault();
    e.stopImmediatePropagation();
    if (e.repeat) return;
    setKey(name, e.type === 'keydown');
  }
  window.addEventListener('keydown', ijkl, true);
  window.addEventListener('keyup', ijkl, true);

  // ================= js-dos 불러오기 =================
  // 1순위: 앱에 내장된 js-dos, 2순위: 인터넷(CDN)
  var SOURCES = [
    { name: 'built-in', js: '/jsdos/js-dos.js', css: '/jsdos/js-dos.css', prefix: '/jsdos/emulators/' },
    { name: 'cdn', js: 'https://v8.js-dos.com/latest/js-dos.js', css: 'https://v8.js-dos.com/latest/js-dos.css',
      prefix: 'https://v8.js-dos.com/latest/emulators/' }
  ];

  var BUNDLE = '/run-' + ((location.search.match(/[?&]b=(\d+)/) || [0, '0'])[1]) + '.jsdos';
  log('bundle url: ' + BUNDLE);

  function startDos(src) {
    log('js-dos loaded (' + src.name + '). typeof Dos=' + (typeof Dos));
    if (typeof Dos === 'undefined') { log('[fail] Dos is undefined', true); return; }
    fetch(BUNDLE).then(function (r) {
      log('bundle http ' + r.status);
      if (r.body) r.body.cancel();
    }).catch(function (e) { log('[fail] bundle fetch: ' + e, true); });
    try {
      Dos(document.getElementById('dos'), {
        url: BUNDLE, autoStart: true, noCloud: true, pathPrefix: src.prefix,
        onEvent: function (event, arg) {
          log('event: ' + event);
          if (event === 'ci-ready') {
            ci = arg; ciReady = true;
            log('ci-ready. sendKeyEvent=' + (ci && typeof ci.sendKeyEvent));
          }
        }
      });
      log('Dos() called');
    } catch (err) {
      log('[fail] Dos() threw: ' + err, true);
    }
  }

  function tryLoad(i) {
    if (i >= SOURCES.length) { log('[fail] js-dos could not be loaded from any source', true); return; }
    var src = SOURCES[i];
    log('loading js-dos from ' + src.name + ' ...');
    var css = document.createElement('link');
    css.rel = 'stylesheet'; css.href = src.css;
    document.head.appendChild(css);
    var js = document.createElement('script');
    js.src = src.js;
    js.onload = function () { startDos(src); };
    js.onerror = function () { log('[fail] ' + src.name + ': ' + src.js, true); tryLoad(i + 1); };
    document.head.appendChild(js);
  }
  tryLoad(0);

  // 12초가 지나도 게임이 시작되지 않으면 멈춘 것이므로 로그를 계속 보여준다.
  setTimeout(function () {
    if (!ciReady) log('[wait] 12s passed but the game has not started (no ci-ready)', true);
  }, 12000);
  setTimeout(function () { if (!hadError && ciReady) logEl.style.display = 'none'; }, 10000);
})();
</script>
</body>
</html>
"""
