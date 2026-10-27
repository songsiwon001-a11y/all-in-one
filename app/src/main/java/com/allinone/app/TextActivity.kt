package com.allinone.app

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.Toast
import java.io.File

/** 간단한 텍스트 편집기 (모든 확장자를 텍스트로 열 수 있음). */
class TextActivity : Activity() {

    private lateinit var file: File
    private lateinit var edit: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        file = File(intent.getStringExtra("path") ?: return finish())
        title = file.name

        edit = EditText(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 13f
            gravity = Gravity.TOP or Gravity.START
            setHorizontallyScrolling(false)
            // 5MB 초과 파일은 앞부분만 읽는다.
            val text = if (file.length() > 5_000_000)
                file.inputStream().use { s ->
                    val buf = ByteArray(5_000_000)
                    var n = 0
                    while (n < buf.size) {
                        val r = s.read(buf, n, buf.size - n)
                        if (r < 0) break
                        n += r
                    }
                    String(buf, 0, n)
                } else file.readText()
            setText(text)
        }
        setContentView(edit)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "저장").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 1) {
            if (file.length() > 5_000_000) {
                Toast.makeText(this, "큰 파일은 저장을 지원하지 않습니다", Toast.LENGTH_SHORT).show()
            } else {
                file.writeText(edit.text.toString())
                Toast.makeText(this, "저장됨", Toast.LENGTH_SHORT).show()
            }
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
