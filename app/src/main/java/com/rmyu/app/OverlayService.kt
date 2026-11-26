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
    private var currentLayer: String? = null

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

    // Us app ki screen-layer dhundhta hai
    private fun findLayer(pkg: String): String? {
        val list = runShell("dumpsys SurfaceFlinger --list")
        val matches = list.lines().map { it.trim() }
            .filter { it.contains(pkg) && !it.startsWith("Background for") }
        if (matches.isEmpty()) return null
        return matches.firstOrNull { it.startsWith("SurfaceView") && it.contains("BLAST") }
            ?: matches.firstOrNull { it.startsWith("SurfaceView") }
            ?: matches[0]
    }

    // Frame ke times se FPS nikalta hai
    private fun calcFps(lat: String): Int? {
        val times = ArrayList<Long>()
        val lines = lat.trim().lines()
        for (i in 1 until lines.size) {
            val parts = lines[i].trim().split(Regex("\\s+"))
            if (parts.size >= 3) {
                val t = parts[1].toLongOrNull() ?: continue
                if (t > 0L && t != Long.MAX_VALUE) times.add(t)
            }
        }
        if (times.size < 2) return null
        times.sort()
        val first = times.first()
        val last = times.last()
        val span = last - first
        if (span <= 0L) return null
        return if (span >= 1_000_000_000L) {
            times.count { it > last - 1_000_000_000L }
        } else {
            ((times.size - 1) * 1_000_000_000L / span).toInt()
        }
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
                            val p = foregroundPackage()
                            if (p != currentPkg) {
                                currentPkg = p
                                currentLayer = null
                            }
                        }
                        val pkg = currentPkg
                        if (pkg == null) {
                            dbg = "app ka naam nahi mila"
                        } else if (pkg == packageName) {
                            dbg = "rmyu khula hai, game kholo"
                        } else {
                            if (currentLayer == null) currentLayer = findLayer(pkg)
                            val layer = currentLayer
                            if (layer == null) {
                                dbg = pkg + " | layer nahi mili"
                            } else {
                                val lat = runShell("dumpsys SurfaceFlinger --latency \"" + layer + "\"")
                                val fps = calcFps(lat)
                                if (fps == null) {
                                    dbg = pkg + " | latency khali (" + lat.lines().size + " line)"
                                    currentLayer = null
                                } else {
                                    fpsText = "FPS " + fps
                                    dbg = pkg
                                }
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
