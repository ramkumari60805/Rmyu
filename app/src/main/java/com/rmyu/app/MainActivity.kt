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

        root.addView(tv)
        root.addView(startBtn)
        root.addView(stopBtn)
        root.addView(sTv)
        root.addView(permBtn)
        root.addView(testBtn)

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
