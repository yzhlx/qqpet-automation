package com.ynk.virtualdisplay.automation

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Debug-only harmless target. It has no QQ interaction or automation session. */
class GateTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = display?.displayId ?: error("Display missing")
        touches.putIfAbsent(id, AtomicInteger())
        setContentView(TargetView(this, id))
    }

    private class TargetView(context: Context, private val id: Int) : View(context) {
        private val paint = Paint().apply { color = Color.WHITE; textSize = 32f }
        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.rgb(32, 96, 160))
            canvas.drawText("Gate target display=$id touches=${touches[id]?.get()}", 24f, 80f, paint)
            // Keep producing frames even with no preview surface attached.
            postInvalidateDelayed(100)
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_UP) touches[id]?.incrementAndGet()
            return true
        }
    }

    companion object {
        val touches = ConcurrentHashMap<Int, AtomicInteger>()
    }
}
