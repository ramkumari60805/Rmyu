package com.rmyu.app

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import rikka.shizuku.Shizuku

class MainActivity : Activity() {
    private var info: TextView? = null
    private var shizukuInfo: TextView? = null
    private val handler = Handler(Looper.getMainLooper())

    private val permListener =
        Shizuku.OnRequestPermissionResultListener { _, _ ->
            runOnUiThread { shizukuInfo?.text = shizukuStatus() }
        }

    private fun shizukuStatus(): String {
        return try {
            if (!Shizuku.pingBinder()) {
                "Shizuku: NAHI chal raha"
            } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                "Shizuku: chal raha hai, permission mil gayi"
            } else {
                "Shizuku: chal raha hai, permission NAHI mili"
            }
        } catch (e: Throwable) {
            "Shizuku error: " + e.message
        }
    }

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

    private fun calcFps(latency: String): String {
        val lines = latency.trim().lines()
        if (lines.size < 2) return "latency ka output khaali hai"
        val times = ArrayList<Long>()
        for (i in 1 until lines.size) {
            val parts = lines[i].trim().split(Regex("\\s+"))
            if (parts.size >= 3) {
                val t = parts[1].toLongOrNull() ?: continue
                if (t > 0 && t != Long.MAX_VALUE) times.add(t)
            }
        }
        if (times.isEmpty()) return "koi frame time nahi mila"
        val last = times.max()
        val count = times.count { it > last - 1_000_000_000L }
        return "FPS: $count"
    }

    private val tick = object : Runnable {
        override fun run() {
            try {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val mem = ActivityManager.MemoryInfo()
                am.getMemoryInfo(mem)
                val totalMb = mem.totalMem / (1024 * 1024)
                val freeMb = mem.availMem / (1024 * 1024)

                val bat: Intent? = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val temp = (bat?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0

                info?.text = "rmyu\n\nRAM free: $freeMb MB\nRAM total: $totalMb MB\nBattery temp: $temp C"
                handler.postDelayed(this, 1000)
            } catch (e: Throwable) {
                info?.text = "ERROR (tick):\n" + Log.getStackTraceString(e)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(48, 96, 48, 48)

        val tv = TextView(this)
        tv.textSize = 18f
        tv.text = "rmyu start ho raha hai..."
        info = tv

        val startBtn = Button(this)
        startBtn.text = "Start Overlay"
        startBtn.setOnClickListener {
            try {
                if (!Settings.canDrawOverlays(this)) {
                    val i = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(i)
                } else {
                    startService(Intent(this, OverlayService::class.java))
                }
            } catch (e: Throwable) {
                info?.text = "ERROR (start):\n" + Log.getStackTraceString(e)
            }
        }

        val stopBtn = Button(this)
        stopBtn.text = "Stop Overlay"
        stopBtn.setOnClickListener {
            try {
                stopService(Intent(this, OverlayService::class.java))
            } catch (e: Throwable) {
                info?.text = "ERROR (stop):\n" + Log.getStackTraceString(e)
            }
        }

        val sTv = TextView(this)
        sTv.textSize = 16f
        sTv.setPadding(0, 48, 0, 16)
        sTv.text = shizukuStatus()
        shizukuInfo = sTv

        val permBtn = Button(this)
        permBtn.text = "Shizuku Permission Maango"
        permBtn.setOnClickListener {
            try {
                if (Shizuku.pingBinder()) {
                    Shizuku.requestPermission(100)
                }
                sTv.text = shizukuStatus()
            } catch (e: Throwable) {
                sTv.text = "Shizuku error: " + e.message
            }
        }

        val testBtn = Button(this)
        testBtn.text = "Shizuku Test (id command)"
        testBtn.setOnClickListener {
            sTv.text = "Test chal raha hai..."
            Thread {
                val result = try {
                    runShell("id")
                } catch (e: Throwable) {
                    "ERROR (test):\n" + Log.getStackTraceString(e)
                }
                runOnUiThread { sTv.text = "Test result:\n" + result }
            }.start()
        }

        val fpsBtn = Button(this)
        fpsBtn.text = "FPS Test (6 sec baad)"
        fpsBtn.setOnClickListener {
            sTv.text = "6 second me Free Fire kholo aur game screen par raho. Phir 10 second baad rmyu me wapas aao."
            Thread {
                val result = try {
                    Thread.sleep(6000)
                    val list = runShell("dumpsys SurfaceFlinger --list")
                    val matches = list.lines().filter { it.contains("freefire", true) }
                    if (matches.isEmpty()) {
                        "Free Fire ki layer nahi mili. Layers:\n" +
                            list.lines().take(15).joinToString("\n")
                    } else {
                        val layer = (matches.firstOrNull { it.contains("SurfaceView") }
                            ?: matches[0]).trim()
                        val lat = runShell("dumpsys SurfaceFlinger --latency \"" + layer + "\"")
                        "Layer: " + layer + "\n" + calcFps(lat)
                    }
                } catch (e: Throwable) {
                    "ERROR (fps):\n" + Log.getStackTraceString(e)
                }
                runOnUiThread { sTv.text = "FPS result:\n" + result }
            }.start()
        }

        root.addView(tv)
        root.addView(startBtn)
        root.addView(stopBtn)
        root.addView(sTv)
        root.addView(permBtn)
        root.addView(testBtn)
        root.addView(fpsBtn)

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)

        try {
            Shizuku.addRequestPermissionResultListener(permListener)
        } catch (e: Throwable) {
            sTv.text = "Shizuku error: " + e.message
        }
    }

    override fun onResume() {
        super.onResume()
        shizukuInfo?.text = shizukuStatus()
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    override fun onDestroy() {
        try {
            Shizuku.removeRequestPermissionResultListener(permListener)
        } catch (e: Throwable) {
        }
        super.onDestroy()
    }
}
