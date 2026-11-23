package com.ahmedxg.gamebot

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Label the recorded frames ON THE PHONE (no computer): pick a class, drag a box around each object, next.
 * Labels are saved in YOLO format; "تصدير" writes gamebot_dataset.zip (images/ + labels/) to Downloads, ready to
 * upload to Kaggle and train with training/kaggle_train_detector.ipynb.
 */
class LabelActivity : Activity() {
    class Box(var cls: Int, var x1: Float, var y1: Float, var x2: Float, var y2: Float)

    private lateinit var view: LabelView
    private lateinit var info: TextView
    private var files: List<File> = emptyList()
    private var idx = 0
    private var framesDir: File? = null
    private lateinit var labelsDir: File
    private var classes: List<String> = listOf("enemy", "enemy_head", "ally")
    private val classButtons = ArrayList<Button>()

    private fun colorOf(c: Int): Int = when (c % 4) {
        0 -> Color.rgb(244, 67, 54)
        1 -> Color.rgb(255, 193, 7)
        2 -> Color.rgb(76, 175, 80)
        else -> Color.rgb(33, 150, 243)
    }

    inner class LabelView(ctx: Context) : View(ctx) {
        var bmp: Bitmap? = null
        val boxes = ArrayList<Box>()
        var curClass = 0
        private val fit = RectF()
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private var dragging = false
        private var sx = 0f
        private var sy = 0f
        private var ex = 0f
        private var ey = 0f

        private fun updateFit() {
            val b = bmp ?: return
            val s = minOf(width.toFloat() / b.width, height.toFloat() / b.height)
            val w = b.width * s
            val h = b.height * s
            fit.set((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
        }

        override fun onDraw(c: Canvas) {
            c.drawColor(Color.BLACK)
            val b = bmp ?: return
            updateFit()
            c.drawBitmap(b, null, fit, null)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 3f
            p.textSize = 26f
            for (bx in boxes) {
                p.color = colorOf(bx.cls)
                val r = RectF(fit.left + bx.x1 * fit.width(), fit.top + bx.y1 * fit.height(), fit.left + bx.x2 * fit.width(), fit.top + bx.y2 * fit.height())
                c.drawRect(r, p)
                p.style = Paint.Style.FILL
                c.drawText(classes.getOrNull(bx.cls) ?: "?", r.left + 4f, r.top - 6f, p)
                p.style = Paint.Style.STROKE
            }
            if (dragging) {
                p.color = colorOf(curClass)
                c.drawRect(minOf(sx, ex), minOf(sy, ey), maxOf(sx, ex), maxOf(sy, ey), p)
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (bmp == null) return true
            updateFit()
            val x = e.x.coerceIn(fit.left, fit.right)
            val y = e.y.coerceIn(fit.top, fit.bottom)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = true
                    sx = x; sy = y; ex = x; ey = y
                }
                MotionEvent.ACTION_MOVE -> {
                    ex = x; ey = y
                }
                MotionEvent.ACTION_UP -> {
                    dragging = false
                    if (Math.abs(ex - sx) > 10f && Math.abs(ey - sy) > 10f) {
                        val x1 = (minOf(sx, ex) - fit.left) / fit.width()
                        val x2 = (maxOf(sx, ex) - fit.left) / fit.width()
                        val y1 = (minOf(sy, ey) - fit.top) / fit.height()
                        val y2 = (maxOf(sy, ey) - fit.top) / fit.height()
                        boxes.add(Box(curClass, x1, y1, x2, y2))
                    }
                }
                MotionEvent.ACTION_CANCEL -> dragging = false
            }
            invalidate()
            return true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.init(this)
        val cfg = Config.load(this)
        val l = cfg.labelList()
        if (l.isNotEmpty()) classes = l
        framesDir = getExternalFilesDir("frames")
        labelsDir = File(getExternalFilesDir(null), "labels")
        labelsDir.mkdirs()
        files = (framesDir?.listFiles { f -> f.name.endsWith(".jpg") } ?: emptyArray()).sortedBy { it.name }

        val root = FrameLayout(this)
        view = LabelView(this)
        root.addView(view)
        info = TextView(this)
        info.setTextColor(Color.WHITE)
        info.setBackgroundColor(0xAA000000.toInt())
        info.textSize = 13f
        root.addView(info, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.setBackgroundColor(0xCC000000.toInt())
        for (i in classes.indices) {
            val b = Button(this)
            b.text = classes[i]
            b.textSize = 11f
            b.setBackgroundColor(colorOf(i))
            b.setTextColor(Color.WHITE)
            b.setOnClickListener { selectClass(i) }
            classButtons.add(b)
            bar.addView(b)
        }
        bar.addView(btn("تراجع") { if (view.boxes.isNotEmpty()) { view.boxes.removeAt(view.boxes.size - 1); view.invalidate() } })
        bar.addView(btn("مسح") { view.boxes.clear(); view.invalidate() })
        bar.addView(btn("◀") { go(idx - 1) })
        bar.addView(btn("▶") { go(idx + 1) })
        bar.addView(btn("تصدير") { exportZip() })
        root.addView(bar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        setContentView(root)
        selectClass(0)

        if (files.isEmpty()) {
            info.text = "مفيش لقطات لسه. فعّل 'سجّل لقطات للتدريب' وشغّل البوت أو العب، وارجع هنا."
        } else {
            load(0)
        }
    }

    private fun btn(t: String, f: () -> Unit): Button {
        val b = Button(this)
        b.text = t
        b.textSize = 11f
        b.setOnClickListener { f() }
        return b
    }

    private fun selectClass(i: Int) {
        view.curClass = i
        for (k in classButtons.indices) classButtons[k].alpha = if (k == i) 1f else 0.45f
    }

    private fun labelFile(f: File): File = File(labelsDir, f.nameWithoutExtension + ".txt")

    private fun labeledCount(): Int = files.count { labelFile(it).exists() }

    private fun saveCurrent() {
        if (files.isEmpty() || view.bmp == null) return
        val sb = StringBuilder()
        for (b in view.boxes) {
            val cx = (b.x1 + b.x2) / 2f
            val cy = (b.y1 + b.y2) / 2f
            sb.append(String.format(Locale.US, "%d %.6f %.6f %.6f %.6f\n", b.cls, cx, cy, b.x2 - b.x1, b.y2 - b.y1))
        }
        try {
            labelFile(files[idx]).writeText(sb.toString())
        } catch (t: Throwable) {
            Logger.i("label save failed: ${t.message}")
        }
    }

    private fun load(i: Int) {
        if (files.isEmpty()) return
        idx = i.coerceIn(0, files.size - 1)
        val f = files[idx]
        view.bmp = BitmapFactory.decodeFile(f.absolutePath)
        view.boxes.clear()
        val lf = labelFile(f)
        if (lf.exists()) {
            for (line in lf.readLines()) {
                val s = line.trim().split(' ')
                if (s.size >= 5) {
                    try {
                        val cx = s[1].toFloat(); val cy = s[2].toFloat(); val w = s[3].toFloat(); val h = s[4].toFloat()
                        view.boxes.add(Box(s[0].toInt(), cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f))
                    } catch (e: Exception) {
                    }
                }
            }
        }
        view.invalidate()
        info.text = "${idx + 1}/${files.size}   مصنّف: ${labeledCount()}   (الصور اللي بدون عناصر برضه مفيدة: اضغط ▶)"
    }

    private fun go(i: Int) {
        saveCurrent()
        if (i < 0 || i >= files.size) {
            info.text = info.text.toString() + "  (آخر صورة)"
            return
        }
        load(i)
    }

    private fun exportZip() {
        saveCurrent()
        val labeled = files.filter { labelFile(it).exists() }
        if (labeled.isEmpty()) {
            Toast.makeText(this, "صنّف صورة على الأقل الأول", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "بيجهّز الملف...", Toast.LENGTH_SHORT).show()
        Thread {
            var msg: String
            try {
                val values = ContentValues()
                values.put(MediaStore.Downloads.DISPLAY_NAME, "gamebot_dataset.zip")
                values.put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                values.put(MediaStore.Downloads.RELATIVE_PATH, "Download")
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("cannot create the file in Downloads")
                contentResolver.openOutputStream(uri)!!.use { os ->
                    ZipOutputStream(os).use { z ->
                        for (f in labeled) {
                            z.putNextEntry(ZipEntry("images/" + f.name))
                            f.inputStream().use { it.copyTo(z) }
                            z.closeEntry()
                            z.putNextEntry(ZipEntry("labels/" + f.nameWithoutExtension + ".txt"))
                            z.write(labelFile(f).readBytes())
                            z.closeEntry()
                        }
                        z.putNextEntry(ZipEntry("classes.txt"))
                        z.write(classes.joinToString("\n").toByteArray())
                        z.closeEntry()
                    }
                }
                msg = "تم: Download/gamebot_dataset.zip (${labeled.size} صورة). ارفعه على Kaggle كـ Dataset"
            } catch (t: Throwable) {
                msg = "فشل التصدير: ${t.javaClass.simpleName} ${t.message}"
            }
            Logger.i("export: $msg")
            runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
        }.start()
    }

    override fun onPause() {
        saveCurrent()
        super.onPause()
    }
}
