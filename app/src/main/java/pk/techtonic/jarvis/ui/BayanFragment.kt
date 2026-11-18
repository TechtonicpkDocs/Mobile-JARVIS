package pk.techtonic.jarvis.ui

import android.app.AlertDialog
import android.app.Fragment
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import pk.techtonic.jarvis.R
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Bayan tab (W3): six scholar cards (YouTube search links only — never an
 * invented title or video link), a Tasbih counter, and a Qibla compass.
 */
class BayanFragment : Fragment(), SensorEventListener {

    private lateinit var tasbihCount: TextView
    private lateinit var targetButtons: List<Button>
    private lateinit var qiblaArrow: TextView
    private lateinit var qiblaBearing: TextView
    private lateinit var qiblaStatus: TextView

    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var magnetometer: Sensor? = null
    private var gravity: FloatArray? = null
    private var geomagnetic: FloatArray? = null
    private var smoothedAzimuth = Float.NaN

    private val qiblaBearingDegrees: Double by lazy { computeQiblaBearing() }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_bayan, container, false)
        val main = activity as MainActivity

        // Scholar cards (contract order).
        val scholarButtonIds = intArrayOf(
            R.id.bayan_scholar_0, R.id.bayan_scholar_1, R.id.bayan_scholar_2,
            R.id.bayan_scholar_3, R.id.bayan_scholar_4, R.id.bayan_scholar_5
        )
        for (i in SCHOLARS.indices) {
            val name = SCHOLARS[i]
            view.findViewById<Button>(scholarButtonIds[i]).setOnClickListener {
                openScholarSearch(name)
            }
        }

        // ---- Tasbih ----
        tasbihCount = view.findViewById(R.id.tasbih_count)
        val btnAdd = view.findViewById<Button>(R.id.tasbih_add)
        val btnReset = view.findViewById<Button>(R.id.tasbih_reset)
        targetButtons = listOf(
            view.findViewById(R.id.tasbih_target_33),
            view.findViewById(R.id.tasbih_target_100),
            view.findViewById(R.id.tasbih_target_500)
        )
        val targets = intArrayOf(33, 100, 500)
        for (i in targetButtons.indices) {
            val target = targets[i]
            targetButtons[i].setOnClickListener {
                main.prefs.tasbihTarget = target
                refreshTargetButtons(main.prefs.tasbihTarget)
            }
        }
        btnAdd.setOnClickListener {
            val today = todayStr()
            val count = main.db.tasbihToday(today) + 1
            main.db.setTasbihToday(today, count)
            tasbihCount.text = count.toString()
            if (count == main.prefs.tasbihTarget) {
                vibrateTargetReached()
                Toast.makeText(activity, R.string.tasbih_reached, Toast.LENGTH_SHORT).show()
            }
        }
        btnReset.setOnClickListener {
            AlertDialog.Builder(activity)
                .setMessage(R.string.tasbih_reset_confirm)
                .setPositiveButton(R.string.yes) { _, _ ->
                    main.db.setTasbihToday(todayStr(), 0)
                    tasbihCount.text = "0"
                }
                .setNegativeButton(R.string.no, null)
                .show()
        }
        tasbihCount.text = main.db.tasbihToday(todayStr()).toString()
        refreshTargetButtons(main.prefs.tasbihTarget)

        // ---- Qibla ----
        qiblaArrow = view.findViewById(R.id.qibla_arrow)
        qiblaBearing = view.findViewById(R.id.qibla_bearing)
        qiblaStatus = view.findViewById(R.id.qibla_status)
        sensorManager = activity.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        magnetometer = sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        if (accelerometer == null || magnetometer == null) {
            qiblaArrow.visibility = View.GONE
            qiblaBearing.visibility = View.GONE
            qiblaStatus.setText(R.string.qibla_no_sensor)
            qiblaStatus.visibility = View.VISIBLE
        } else {
            qiblaBearing.text = String.format(
                Locale.US, "%.1f°", qiblaBearingDegrees
            )
        }
        return view
    }

    override fun onResume() {
        super.onResume()
        val sm = sensorManager
        if (sm != null && accelerometer != null && magnetometer != null) {
            sm.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
            sm.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        sensorManager?.unregisterListener(this)
        super.onPause()
    }

    private fun refreshTargetButtons(selected: Int) {
        val targets = intArrayOf(33, 100, 500)
        for (i in targetButtons.indices) {
            val isSelected = targets[i] == selected
            targetButtons[i].isSelected = isSelected
            targetButtons[i].alpha = if (isSelected) 1.0f else 0.6f
        }
    }

    private fun vibrateTargetReached() {
        try {
            val vibrator = activity?.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(200)
            }
        } catch (ignored: Exception) {
            // No vibrator on this device; the toast already told the user.
        }
    }

    private fun openScholarSearch(englishName: String) {
        val url = "https://www.youtube.com/results?search_query=" +
            URLEncoder.encode("$englishName latest bayan", "UTF-8")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        if (intent.resolveActivity(activity.packageManager) != null) {
            startActivity(intent)
        }
    }

    private fun todayStr(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Calendar.getInstance().time)

    // ---------- Qibla compass ----------

    /**
     * Great-circle initial bearing from Layyah (30.96, 70.94) to the Kaaba
     * (21.4225, 39.8262), computed in code (~255°). Never a hardcoded
     * display value.
     */
    private fun computeQiblaBearing(): Double {
        val lat1 = Math.toRadians(LAT_LAYYAH)
        val lon1 = Math.toRadians(LON_LAYYAH)
        val lat2 = Math.toRadians(LAT_KAABA)
        val lon2 = Math.toRadians(LON_KAABA)
        val dLon = lon2 - lon1
        val y = Math.sin(dLon) * Math.cos(lat2)
        val x = Math.cos(lat1) * Math.sin(lat2) -
            Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon)
        var bearing = Math.toDegrees(Math.atan2(y, x))
        if (bearing < 0) bearing += 360.0
        return bearing
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!isAdded) return
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> gravity = event.values.clone()
            Sensor.TYPE_MAGNETIC_FIELD -> geomagnetic = event.values.clone()
            else -> return
        }
        val g = gravity ?: return
        val m = geomagnetic ?: return
        val rotationMatrix = FloatArray(9)
        if (!SensorManager.getRotationMatrix(rotationMatrix, null, g, m)) return
        val orientation = FloatArray(3)
        SensorManager.getOrientation(rotationMatrix, orientation)
        var azimuth = Math.toDegrees(orientation[0].toDouble()).toFloat()
        if (azimuth < 0) azimuth += 360f

        // Light smoothing (shortest-path interpolation) so the needle
        // does not jitter with every sensor event.
        smoothedAzimuth = if (smoothedAzimuth.isNaN()) {
            azimuth
        } else {
            var delta = azimuth - smoothedAzimuth
            if (delta > 180f) delta -= 360f
            if (delta < -180f) delta += 360f
            var next = smoothedAzimuth + delta * 0.15f
            if (next < 0) next += 360f
            if (next >= 360f) next -= 360f
            next
        }
        val rotation = (qiblaBearingDegrees - smoothedAzimuth).toFloat()
        qiblaArrow.rotation = rotation
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        // Compass accuracy changes need no UI action.
    }

    companion object {
        /** Contract order; English names are the YouTube search names. */
        private val SCHOLARS = listOf(
            "Imran Attari",
            "Abdul Habib Attari",
            "Mufti Samar Abbas Attari",
            "Muhammad Raza Saqib Mustafai",
            "Mufti Salman Azhari",
            "Ajmal Raza Qadri"
        )

        private const val LAT_LAYYAH = 30.96
        private const val LON_LAYYAH = 70.94
        private const val LAT_KAABA = 21.4225
        private const val LON_KAABA = 39.8262
    }
}
