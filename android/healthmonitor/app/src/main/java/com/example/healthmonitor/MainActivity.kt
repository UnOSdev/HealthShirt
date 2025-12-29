package com.example.healthmonitor

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import com.google.android.gms.tasks.OnSuccessListener
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.healthmonitor.databinding.ActivityMainBinding
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.google.firebase.database.FirebaseDatabase
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.collections.ArrayList

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothSocket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var workerThread: Thread? = null
    private var isConnected = false

    // HC-05 UUID ve MAC
    private val HC05_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val HC05_ADDRESS = "00:24:09:01:04:84"
    private val PERMISSION_REQUEST_CODE = 1

    // Veri Analizi ve Grafik Değişkenleri
    private val bpmReadingsBuffer = ArrayList<Float>() // 1 dakikalık veriyi tutmak için
    private var lastAverageTime: Long = 0 // Son ortalama hesaplama zamanı
    private val chartEntries = ArrayList<Entry>()
    private var minuteCounter = 0f // X ekseni için sayaç

    // Firebase
    private val database = FirebaseDatabase.getInstance()
    private val myRef = database.getReference("HealthData") // "HealthData" düğümü altına yazar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()

        setupChart() // Grafiği hazırla

        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth desteklenmiyor", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        checkPermissions()

        binding.btnConnect.setOnClickListener {
            if (isConnected) disconnect() else connect()
        }
    }

    // Grafik ayarlarını yapan fonksiyon
    private fun setupChart() {
        val chart = binding.pulseChart
        chart.description.isEnabled = false
        chart.setTouchEnabled(true)
        chart.setPinchZoom(true)
        chart.setDrawGridBackground(false)

        // X Ekseni (Zaman/Dakika)
        val xAxis = chart.xAxis
        xAxis.position = XAxis.XAxisPosition.BOTTOM
        xAxis.setDrawGridLines(false)
        xAxis.granularity = 1f // Her 1 birim 1 dakikayı temsil eder

        // Y Ekseni (BPM)
        chart.axisRight.isEnabled = false
        chart.axisLeft.setDrawGridLines(true)
        chart.axisLeft.gridColor = Color.parseColor("#E0E6ED")
    }

    // Grafiğe yeni veri ekleyen fonksiyon
    private fun addEntryToChart(bpmValue: Float) {
        minuteCounter += 1f
        chartEntries.add(Entry(minuteCounter, bpmValue))

        val dataSet = LineDataSet(chartEntries, "Ortalama Nabız")
        dataSet.color = Color.parseColor("#D64545") // Çizgi rengi kırmızı
        dataSet.valueTextColor = Color.parseColor("#102A43")
        dataSet.lineWidth = 2f
        dataSet.circleRadius = 4f
        dataSet.setCircleColor(Color.parseColor("#D64545"))
        dataSet.setDrawValues(true)
        dataSet.mode = LineDataSet.Mode.CUBIC_BEZIER // Çizgiyi yumuşat

        val lineData = LineData(dataSet)
        binding.pulseChart.data = lineData
        binding.pulseChart.notifyDataSetChanged()
        binding.pulseChart.invalidate() // Grafiği yenile
    }

    // Veri tabanına kaydedilecek veri modeli
    data class HealthRecord(
        val timestamp: String,
        val avgBpm: Float,
        val date: String
    )

    private fun saveToFirebase(avgBpm: Float) {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val currentDateAndTime = sdf.format(Date())

        val recordId = myRef.push().key // Benzersiz ID oluştur
        val record = HealthRecord(
            timestamp = System.currentTimeMillis().toString(),
            avgBpm = avgBpm,
            date = currentDateAndTime
        )

        recordId?.let {
            myRef.child(it).setValue(record)
                .addOnSuccessListener {
                    // İsteğe bağlı log veya toast
                     Toast.makeText(this, "Veri kaydedildi", Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun checkPermissions() {
        val permissions = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

        val permissionsNeeded = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsNeeded.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsNeeded.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    private fun connect() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Bluetooth izni gerekli", Toast.LENGTH_SHORT).show()
            return
        }

        binding.tvStatus.text = "Bağlanıyor..."
        binding.btnConnect.isEnabled = false

        Thread {
            try {
                val device: BluetoothDevice = bluetoothAdapter!!.getRemoteDevice(HC05_ADDRESS)
                bluetoothSocket = device.createRfcommSocketToServiceRecord(HC05_UUID)
                bluetoothSocket?.connect()

                inputStream = bluetoothSocket?.inputStream
                outputStream = bluetoothSocket?.outputStream

                // Bağlantı başarılı olduğunda zamanlayıcıyı başlat
                lastAverageTime = System.currentTimeMillis()
                bpmReadingsBuffer.clear()

                runOnUiThread {
                    isConnected = true
                    binding.tvStatus.text = "Bağlandı"
                    binding.btnConnect.text = "Bağlantıyı Kes"
                    binding.btnConnect.isEnabled = true
                    Toast.makeText(this, "HC-05'e bağlandı", Toast.LENGTH_SHORT).show()
                }

                startListening()

            } catch (e: IOException) {
                runOnUiThread {
                    binding.tvStatus.text = "Bağlantı başarısız"
                    binding.btnConnect.isEnabled = true
                    Toast.makeText(this, "Hata: ${e.message}", Toast.LENGTH_LONG).show()
                }
                e.printStackTrace()
            }
        }.start()
    }

    private fun disconnect() {
        runOnUiThread {
            isConnected = false
            binding.tvStatus.text = "Bağlantı kesildi"
            binding.btnConnect.text = "BAĞLAN"
            binding.btnConnect.isEnabled = true
            binding.tvPulse.text = "--"
            binding.tvPulseAvg.text = "--"
            binding.tvPulseStatus.text = "Bekleniyor"
            binding.tvPulseStatus.setBackgroundColor(0xFFF0F4F8.toInt())
            binding.tvAvgPulseStatus.text = "Bekleniyor"
            binding.tvAvgPulseStatus.setBackgroundColor(0xFFF0F4F8.toInt())
        }

        try { workerThread?.interrupt() } catch (_: Exception) {}
        try { inputStream?.close() } catch (_: Exception) {}
        try { outputStream?.close() } catch (_: Exception) {}
        try { bluetoothSocket?.close() } catch (_: Exception) {}

        inputStream = null
        outputStream = null
        bluetoothSocket = null
        workerThread = null
    }

    private fun startListening() {
        workerThread = Thread {
            val buffer = ByteArray(1024)
            var bytes: Int
            val handler = Handler(Looper.getMainLooper())

            while (isConnected) {
                try {
                    bytes = inputStream!!.read(buffer)
                    val readMessage = String(buffer, 0, bytes)

                    handler.post {
                        parseData(readMessage)
                    }

                } catch (e: IOException) {
                    if (isConnected) {
                        handler.post { disconnect() }
                    }
                    break
                }
            }
        }
        workerThread?.start()
    }

    private fun parseData(data: String) {
        try {
            val lines = data.split("\n")

            for (line in lines) {
                val clean = line.trim()
                if (clean.isEmpty()) continue

                val parts = clean.split(",").map { it.trim() }
                if (parts.size < 2) continue // En az 2 veri bekliyoruz (IR, BPM)

                // parts[1] genellikle BPM olur (Arduino kodunuza göre değişebilir)
                val bpm = parts[1].toFloatOrNull()

                // Anlık veri geldiğinde:
                bpm?.let { currentValue ->
                    // arayüzü güncelle
                    binding.tvPulse.text = String.format(Locale.US, "%.1f", currentValue)
                    updatePulseStatus(currentValue.toInt())

                    // buffera ekleme işlemi: (0 olmayan geçerli nabızlar eklenir)
                    if (currentValue > 30) { // gürültüyü önlemek için alt sınır
                        bpmReadingsBuffer.add(currentValue)
                    }

                    // 3. Zaman kontrolü (1 dakika geçti mi?)
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastAverageTime >= 60000) { // 60000 ms = 1 dakika
                        processMinuteData()
                        lastAverageTime = currentTime
                    }
                }

                // Arduino'dan gelen hazır ortalama varsa onu da ekranda gösterelim
                if (parts.size > 2) {
                    val avgFromDevice = parts[2].toIntOrNull()
                    avgFromDevice?.let {
                        // Sadece anlık gösterim için kullanıyoruz, kayıt için kendi hesapladığımızı kullanacağız
                        // binding.tvPulseAvg.text = it.toString()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // 1 dakikalık veriyi işleyip kaydeden fonksiyon
    private fun processMinuteData() {
        if (bpmReadingsBuffer.isNotEmpty()) {
            val sum = bpmReadingsBuffer.sum()
            val average = sum / bpmReadingsBuffer.size

            binding.tvPulseAvg.text = String.format(Locale.US, "%.1f", average)
            updateAvgPulseStatus(average.toInt())

            addEntryToChart(average)

            saveToFirebase(average)

            bpmReadingsBuffer.clear()

            Toast.makeText(this, "1 dakikalık veri kaydedildi: $average BPM", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updatePulseStatus(pulse: Int) {
        val (text, textColor, bgColor) = when {
            pulse < 50 -> Triple("Düşük Nabız", 0xFFB00020.toInt(), 0xFFFFEBEE.toInt())
            pulse > 100 -> Triple("Yüksek Nabız", 0xFFB00020.toInt(), 0xFFFFEBEE.toInt())
            else -> Triple("Normal", 0xFF0B6E4F.toInt(), 0xFFE6FFFA.toInt())
        }
        binding.tvPulseStatus.text = text
        binding.tvPulseStatus.setTextColor(textColor)
        binding.tvPulseStatus.setBackgroundColor(bgColor)
    }

    private fun updateAvgPulseStatus(avg: Int) {
        val (text, textColor, bgColor) = when {
            avg < 50 -> Triple("Ortalama Düşük", 0xFFB00020.toInt(), 0xFFFFEBEE.toInt())
            avg > 100 -> Triple("Ortalama Yüksek", 0xFFB00020.toInt(), 0xFFFFEBEE.toInt())
            else -> Triple("Ortalama Normal", 0xFF0B6E4F.toInt(), 0xFFE6FFFA.toInt())
        }
        binding.tvAvgPulseStatus.text = text
        binding.tvAvgPulseStatus.setTextColor(textColor)
        binding.tvAvgPulseStatus.setBackgroundColor(bgColor)
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnect()
    }
}