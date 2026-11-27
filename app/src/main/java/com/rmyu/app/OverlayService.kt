package com.rmyu.app

import android.app.ActivityManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.BatteryManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import rikka.shizuku.Shizuku

class OverlayService : Service() {
    // Yahan se size aur position badal sakte ho
    private val TEXT_SIZE = 16f
    private val POS_X = 60
    private val POS_Y = 20
    private val DEBUG = true   // debug line hatani ho to false karo

    private var wm: WindowManager? = null
    private var box: TextView? = null
    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var running = false
    private var worker: Thread? = null
    private var currentPkg: String? = null

    private var tsEnabled = false
    private var prevTotals: Map<String, Long> = emptyMap()
    private var prevTimeNs = 0L
    private var prevPkg: String? = null

    private fun runShell(cmd: String): String {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        m.isAccessible = true
        val args = arrayOf<Any?>(arrayOf("sh", "-c", cmd), null, null)
        val p = m.invoke(null, *args) as Process
        val out = p.inputStream.bufferedReader().readText()
        val err = p.errorStream.bufferedReader().readText()
        p.waitFor()
        return out + err
    }

    // Abhi screen par kaunsa app khula hai
    private fun foregroundPackage(): String? {
        val w = runShell("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'")
        val m = Regex("u0 ([A-Za-z0-9_.]+)/").find(w)
        if (m != null) return m.groupValues[1]
        val a = runShell("dumpsys activity activities | grep -E 'topResumedActivity|ResumedActivity'")
        return Regex(" ([A-Za-z0-9_.]+)/").find(a)?.groupValues?.get(1)
    }

    // Us app ki har layer ke "total frames" padhta hai
    private fun readTotals(pkg: String): Map<String, Long> {
        val out = runShell("dumpsys SurfaceFlinger --timestats -dump | grep -A1 -F \"" + pkg + "\"")
        val result = HashMap<String, Long>()
        var name: String? = null
        for (raw in out.lines()) {
            val line = raw.trim()
            if (line.startsWith("layerName")) {
                name = line.substringAfter("=").trim()
            } else if (line.startsWith("totalFrames") && name != null) {
                val v = line.substringAfter("=").trim().toLongOrNull()
                if (v != null) result[name] = v
                name = null
            }
        }
        return result
    }

    private fun startWorker() {
        running = true
        worker = Thread {
            var n = 0
            while (running) {
                var fpsText = "FPS --"
                var dbg = ""
                try {
                    if (!Shizuku.pingBinder()) {
                        dbg = "Shizuku band hai"
                    } else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                        dbg = "Shizuku permission nahi"
                    } else {
                        if (n % 3 == 0 || currentPkg == null) {
                            currentPkg = foregroundPackage()
                        }
                        val pkg = currentPkg
                        if (pkg == null) {
                            dbg = "app ka naam nahi mila"
                        } else if (pkg == packageName) {
                            dbg = "rmyu khula hai, game kholo"
                        } else {
                            if (!tsEnabled) {
                                runShell("dumpsys SurfaceFlinger --timestats -enable")
                                tsEnabled = true
                            }
                            val totals = readTotals(pkg)
                            val now = System.nanoTime()
                            if (totals.isEmpty()) {
                                dbg = pkg + " | timestats me layer nahi"
                                prevTotals = emptyMap()
                            } else {
                                if (prevPkg == pkg && prevTotals.isNotEmpty() && now > prevTimeNs) {
                                    var best = -1L
                                    for ((k, v) in totals) {
                                        val p = prevTotals[k]
                                        if (p != null) {
                                            val d = v - p
                                            if (d > best) best = d
                                        }
                                    }
                                    if (best >= 0L) {
                                        val fps = (best * 1_000_000_000L / (now - prevTimeNs)).toInt()
                                        fpsText = "FPS " + fps
                                        dbg = pkg + " (" + totals.size + " layer)"
                                    } else {
                                        dbg = pkg + " | naya layer, ruko"
                                    }
                                } else {
                                    dbg = pkg + " | pehla sample, ruko"
                                }
                                prevTotals = totals
                                prevTimeNs = now
                                prevPkg = pkg
                            }
                        }
                    }
                } catch (e: Throwable) {
                    dbg = "ERR " + (e.message ?: "")
                }

                var extra = ""
                try {
                    val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                    val mem = ActivityManager.MemoryInfo()
                    am.getMemoryInfo(mem)
                    val freeMb = mem.availMem / (1024 * 1024)
                    val bat: Intent? = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    val temp = (bat?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
                    extra = "  |  RAM " + freeMb + " MB  |  " + temp + " C"
                } catch (e: Throwable) {
                    extra = ""
                }

                val shown = if (DEBUG) fpsText + extra + "\n" + dbg else fpsText + extra
                handler.post { box?.text = shown }
                n++
                try {
                    Thread.sleep(1000)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
        worker?.start()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val tv = TextView(this)
            tv.setTextColor(Color.WHITE)
            tv.setBackgroundColor(Color.TRANSPARENT)
            tv.textSize = TEXT_SIZE
            tv.setPadding(6, 2, 6, 2)
            tv.setShadowLayer(6f, 0f, 0f, Color.BLACK)
            tv.text = "FPS --"

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.BOTTOM or Gravity.START
            params.x = POS_X
            params.y = POS_Y

            wm?.addView(tv, params)
            box = tv
            startWorker()
        } catch (e: Throwable) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        try {
            box?.let { wm?.removeView(it) }
        } catch (e: Throwable) {
        }
        super.onDestroy()
    }
}
