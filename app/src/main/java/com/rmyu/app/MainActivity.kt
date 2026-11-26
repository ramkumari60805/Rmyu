package com.rmyu.app

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private var info: TextView? = null
    private val handler = Handler(Looper.getMainLooper())

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

        root.addView(tv)
        root.addView(startBtn)
        root.addView(stopBtn)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }
}
