package com.adskipper.ui

import android.os.Bundle
import android.os.CountDownTimer
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat

/**
 * Fake splash ad for verifying the pipeline end-to-end: a full-screen "ad"
 * with a 跳过 countdown button in the top-right corner.
 */
class TestAdActivity : ComponentActivity() {

    private var timer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this).apply {
            setBackgroundColor(0xFF123456.toInt())
        }
        val title = TextView(this).apply {
            text = "这是一条模拟开屏广告"
            textSize = 28f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
        }
        val skip = Button(this).apply {
            text = "跳过 30"
            contentDescription = "跳过广告"
        }
        skip.setOnClickListener { finish() }

        root.addView(title, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.TOP or Gravity.END
        lp.topMargin = 64
        lp.marginEnd = 32
        root.addView(skip, lp)
        setContentView(root)

        timer = object : CountDownTimer(30000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                skip.text = "跳过 ${millisUntilFinished / 1000 + 1}"
            }

            override fun onFinish() {
                finish()
            }
        }.start()
    }

    override fun onDestroy() {
        timer?.cancel()
        super.onDestroy()
    }
}
