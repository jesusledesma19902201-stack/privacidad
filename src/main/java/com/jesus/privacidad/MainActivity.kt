package com.jesus.privacidad

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
import android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
import android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
import android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
import android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
import android.widget.*

class MainActivity : Activity() {
    private lateinit var estado: TextView
    private lateinit var btnPermiso: Button
    private lateinit var btnToggle: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val pad = (24 * d).toInt()
        val prefs = getSharedPreferences("p", MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        root.addView(TextView(this).apply { text = "Modo privacidad"; textSize = 26f })
        estado = TextView(this).apply { textSize = 16f; setPadding(0, pad / 2, 0, pad / 2) }
        root.addView(estado)

        btnPermiso = Button(this).apply {
            text = "1. Dar permiso de superposición"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }
        root.addView(btnPermiso)

        btnToggle = Button(this).apply {
            setOnClickListener {
                val i = Intent(this@MainActivity, OverlayService::class.java)
                if (OverlayService.running) {
                    startService(i.setAction("STOP"))
                } else {
                    startForegroundService(i)
                }
                postDelayed({ refrescar() }, 500)
            }
        }
        root.addView(btnToggle)

        root.addView(TextView(this).apply { text = "Oscuridad de la pantalla"; setPadding(0, pad, 0, 0) })
        root.addView(SeekBar(this).apply {
            max = 60
            progress = prefs.getInt("lvl", 70) - 20
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    prefs.edit().putInt("lvl", p + 20).apply()
                    if (OverlayService.running) {
                        startService(Intent(this@MainActivity, OverlayService::class.java).setAction("REFRESH"))
                    }
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })
        setContentView(root)

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        refrescar()
    }

    private fun refrescar() {
        val ok = Settings.canDrawOverlays(this)
        btnPermiso.visibility = if (ok) View.GONE else View.VISIBLE
        btnToggle.isEnabled = ok
        btnToggle.text = if (OverlayService.running) "Detener modo privacidad" else "2. Iniciar modo privacidad"
        estado.text = when {
            !ok -> "Falta el permiso de superposición."
            OverlayService.running -> "Activo. Toca la burbuja verde para oscurecer o aclarar la pantalla. Puedes moverla arrastrándola."
            else -> "Listo para usar."
        }
    }
}

class OverlayService : Service() {
    companion object { var running = false }

    private lateinit var wm: WindowManager
    private lateinit var pOscuro: WindowManager.LayoutParams
    private var oscuro: View? = null
    private var burbuja: View? = null
    private val fondo = GradientDrawable()
    private var activo = true

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("priv", "Modo privacidad", NotificationManager.IMPORTANCE_LOW)
        )
        val stop = PendingIntent.getService(
            this, 0, Intent(this, OverlayService::class.java).setAction("STOP"),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, "priv")
            .setContentTitle("Modo privacidad activo")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Detener", stop)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }

        val d = resources.displayMetrics.density

        // Capa oscura (no bloquea los toques)
        oscuro = View(this).apply { setBackgroundColor(Color.BLACK) }
        pOscuro = WindowManager.LayoutParams(
            -1, -1, TYPE_APPLICATION_OVERLAY,
            FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCHABLE or FLAG_LAYOUT_IN_SCREEN or FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        pOscuro.alpha = nivel()
        wm.addView(oscuro, pOscuro)

        // Burbuja flotante
        val tam = (52 * d).toInt()
        fondo.shape = GradientDrawable.OVAL
        fondo.setColor(0xCC00AA55.toInt())
        fondo.setStroke((2 * d).toInt(), Color.WHITE)
        val pB = WindowManager.LayoutParams(
            tam, tam, TYPE_APPLICATION_OVERLAY,
            FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        )
        pB.gravity = Gravity.TOP or Gravity.START
        pB.x = 0
        pB.y = (200 * d).toInt()

        var x0 = 0
        var y0 = 0
        var tx = 0f
        var ty = 0f
        var movio = false
        burbuja = View(this).apply {
            background = fondo
            setOnTouchListener { v, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        x0 = pB.x; y0 = pB.y; tx = e.rawX; ty = e.rawY; movio = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - tx
                        val dy = e.rawY - ty
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) movio = true
                        pB.x = x0 + dx.toInt()
                        pB.y = y0 + dy.toInt()
                        wm.updateViewLayout(v, pB)
                    }
                    MotionEvent.ACTION_UP -> if (!movio) alternar()
                }
                true
            }
        }
        wm.addView(burbuja, pB)
    }

    private fun alternar() {
        activo = !activo
        oscuro?.visibility = if (activo) View.VISIBLE else View.GONE
        fondo.setColor(if (activo) 0xCC00AA55.toInt() else 0xCCAA3333.toInt())
    }

    private fun nivel(): Float =
        getSharedPreferences("p", MODE_PRIVATE).getInt("lvl", 70) / 100f

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        when (i?.action) {
            "STOP" -> stopSelf()
            "REFRESH" -> {
                pOscuro.alpha = nivel()
                oscuro?.let { wm.updateViewLayout(it, pOscuro) }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        oscuro?.let { wm.removeView(it) }
        burbuja?.let { wm.removeView(it) }
        super.onDestroy()
    }
}
