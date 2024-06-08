package com.example.bluetooth

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.components.Description
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.google.gson.Gson
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.util.*

class MainActivity : AppCompatActivity() {
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val deviceAddress = "C8:F0:9E:53:34:26" // 실제 아두이노 블루투스 모듈의 MAC 주소
    private var bluetoothSocket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private val gson = Gson()

    companion object {
        private const val REQUEST_BLUETOOTH_PERMISSIONS = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 권한 요청
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val permissions = arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
            ActivityCompat.requestPermissions(this, permissions, REQUEST_BLUETOOTH_PERMISSIONS)
        } else {
            checkBluetoothEnabled()
        }

        val connectButton: Button = findViewById(R.id.button_connect)
        connectButton.setOnClickListener {
            checkBluetoothEnabled()
        }

        // SharedPreferences에서 JSON 데이터를 불러와 차트에 표시
        val jsonData = loadJsonData()
        setupBarChart(jsonData)
    }

    private fun checkBluetoothEnabled() {
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth is not supported on this device", Toast.LENGTH_SHORT).show()
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            Toast.makeText(this, "Please enable Bluetooth", Toast.LENGTH_SHORT).show()
            return
        }

        connectToDevice()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_BLUETOOTH_PERMISSIONS) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                checkBluetoothEnabled()
            } else {
                Log.e("MainActivity", "Bluetooth permissions denied")
            }
        }
    }

    private fun connectToDevice() {
        val device = bluetoothAdapter?.getRemoteDevice(deviceAddress)
        if (device != null) {
            try {
                // 기본 SPP UUID 사용
                val uuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

                if (ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    bluetoothSocket = device.createRfcommSocketToServiceRecord(uuid)
                    bluetoothSocket?.let { socket ->
                        try {
                            socket.connect()
                            inputStream = socket.inputStream
                            listenForData()
                        } catch (connectException: IOException) {
                            Log.e("MainActivity", "Could not connect to device", connectException)
                            try {
                                socket.close()
                            } catch (closeException: IOException) {
                                Log.e("MainActivity", "Could not close the client socket", closeException)
                            }
                        }
                    }
                } else {
                    Log.e("MainActivity", "BLUETOOTH_CONNECT permission not granted")
                }
            } catch (e: IOException) {
                Log.e("MainActivity", "IOException during connect", e)
            } catch (e: IllegalArgumentException) {
                Log.e("MainActivity", "Invalid device address", e)
            }
        } else {
            Log.e("MainActivity", "Device not found with address: $deviceAddress")
        }
    }

    private fun listenForData() {
        val buffer = ByteArray(1024)
        var bytes: Int

        while (true) {
            try {
                bytes = inputStream?.read(buffer) ?: break
                val readMessage = String(buffer, 0, bytes)
                handleJsonData(readMessage)
            } catch (e: IOException) {
                Log.e("MainActivity", "Input stream was disconnected", e)
                break
            }
        }
    }

    private fun handleJsonData(data: String) {
        // JSON 데이터 처리 로직
        Log.d("BluetoothData", data)
        try {
            val jsonArray = JSONArray(data)
            // JSON 데이터를 SharedPreferences에 저장
            saveJsonData(jsonArray.toString())
            // SharedPreferences에서 JSON 데이터를 불러와 차트에 표시
            val jsonData = loadJsonData()
            setupBarChart(jsonData)
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to parse JSON", e)
        }
    }

    private fun saveJsonData(jsonString: String) {
        val sharedPref = getSharedPreferences("bar_chart_data", Context.MODE_PRIVATE)
        with(sharedPref.edit()) {
            putString("json_data", jsonString)
            apply()
        }
    }

    private fun loadJsonData(): JSONArray? {
        val sharedPref = getSharedPreferences("bar_chart_data", Context.MODE_PRIVATE)
        val jsonString = sharedPref.getString("json_data", null)
        return if (jsonString != null) {
            JSONArray(jsonString)
        } else {
            null
        }
    }

    private fun setupBarChart(jsonData: JSONArray?) {
        val barChart = findViewById<BarChart>(R.id.barChart)
        val entries = ArrayList<BarEntry>()

        if (jsonData != null) {
            for (i in 0 until jsonData.length()) {
                val jsonObject = jsonData.getJSONObject(i)
                val time = jsonObject.getInt("time")
                val count = jsonObject.getInt("count")
                val countPerMinute = count.toFloat() / time
                entries.add(BarEntry(time.toFloat(), countPerMinute))
            }
        }

        val dataSet = BarDataSet(entries, "Count per Minute")
        val barData = BarData(dataSet)
        barChart.data = barData

        // 차트 설명 제거
        val description = Description()
        description.text = ""
        barChart.description = description

        barChart.invalidate() // 차트를 갱신합니다.
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            bluetoothSocket?.close()
        } catch (e: IOException) {
            Log.e("MainActivity", "Could not close the client socket", e)
        }
    }
}

// 확장 함수로 JSONObject에서 Float 값 가져오기
private fun JSONObject.getFloat(key: String): Float {
    return this.getDouble(key).toFloat()
}