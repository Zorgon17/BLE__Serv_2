package com.example.ble__serv

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.ParcelUuid
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Реализует сервер при BLE соединении
 */
class MainActivity : ComponentActivity() {

    private var mBluetoothManager: BluetoothManager? = null
    private var mBluetoothAdapter: BluetoothAdapter? = null
    private var mBluetoothLeAdvertiser: BluetoothLeAdvertiser? = null
    private var mGattServer: BluetoothGattServer? = null

    private var mConnectedDevices: ArrayList<BluetoothDevice> = ArrayList()

    private val mHandler = Handler()

    // Launcher to request multiple permissions
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.all { it.value }) {
                Log.i(TAG, "All permissions granted.")
                initializeBluetooth()
            } else {
                Toast.makeText(this, "Permissions not granted, exiting.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize Bluetooth
        mBluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        mBluetoothAdapter = mBluetoothManager!!.adapter

        // Check for permissions before running the app
        if (!checkPermissions()) {
            requestPermissions()
        } else {
            setContent {
                MyBluetoothApp()
            }
        }
    }

    private fun checkPermissions(): Boolean {
        return requiredPermissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun requestPermissions() {
        requestPermissionLauncher.launch(requiredPermissions)
    }

    override fun onResume() {
        super.onResume()
        // ensure that permissions are checked again in the case onResume is called
        if (!checkPermissions()) {
            requestPermissions()  // Request permissions if they have been revoked
            return
        }
        // Check Bluetooth state, LE support, and advertising support
        initializeBluetooth()
    }

    private fun initializeBluetooth() {
        if (mBluetoothAdapter == null || !mBluetoothAdapter!!.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            startActivity(enableBtIntent)
            finish()
            return
        }

        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(this, "No LE Support.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        if (!mBluetoothAdapter!!.isMultipleAdvertisementSupported) {
            Toast.makeText(this, "No Advertising Support.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        mBluetoothLeAdvertiser = mBluetoothAdapter!!.bluetoothLeAdvertiser

        mGattServer = mBluetoothManager!!.openGattServer(this, mGattServerCallback)
        initServer()
        startAdvertising()
    }

    override fun onPause() {
        super.onPause()
        stopAdvertising()
        shutdownServer()
    }

    private fun initServer() {
        val service = BluetoothGattService(
            DeviceProfile.SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        val elapsedCharacteristic = BluetoothGattCharacteristic(
            DeviceProfile.CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        service.addCharacteristic(elapsedCharacteristic)

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        mGattServer?.addService(service)
    }

    private fun shutdownServer() {
        mHandler.removeCallbacks(mNotifyRunnable)
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        mGattServer?.close()
        mGattServer = null
    }

    private val mNotifyRunnable: Runnable = object : Runnable {
        override fun run() {
            notifyConnectedDevices()
            mHandler.postDelayed(this, 2000)
        }
    }

    private val mGattServerCallback: BluetoothGattServerCallback =
        object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(
                device: BluetoothDevice,
                status: Int,
                newState: Int
            ) {
                super.onConnectionStateChange(device, status, newState)
                Log.i(
                    TAG, ("onConnectionStateChange "
                            + DeviceProfile.getStatusDescription(status)) + " "
                            + DeviceProfile.getStateDescription(newState)
                )

                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    postDeviceChange(device, true)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    postDeviceChange(device, false)
                }
            }

            @SuppressLint("MissingPermission")
            override fun onCharacteristicReadRequest(
                device: BluetoothDevice,
                requestId: Int,
                offset: Int,
                characteristic: BluetoothGattCharacteristic
            ) {
                super.onCharacteristicReadRequest(device, requestId, offset, characteristic)
                Log.i(TAG, "onCharacteristicReadRequest ${characteristic.uuid}")

                when (characteristic.uuid) {
                    DeviceProfile.CHARACTERISTIC_UUID -> {
                        mGattServer?.sendResponse(
                            device,
                            requestId,
                            BluetoothGatt.GATT_SUCCESS,
                            0,
                            getStoredValue
                        )
                    }

                    else -> {
                        mGattServer?.sendResponse(
                            device,
                            requestId,
                            BluetoothGatt.GATT_FAILURE,
                            0,
                            null
                        )
                    }
                }
            }
        }

    private fun startAdvertising() {
        if (mBluetoothLeAdvertiser == null) return

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(DeviceProfile.SERVICE_UUID))
            .build()

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_ADVERTISE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        mBluetoothLeAdvertiser!!.startAdvertising(settings, data, mAdvertiseCallback)
    }

    private fun stopAdvertising() {
        if (mBluetoothLeAdvertiser == null) return

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_ADVERTISE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        mBluetoothLeAdvertiser!!.stopAdvertising(mAdvertiseCallback)
    }

    private val mAdvertiseCallback: AdvertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.i(TAG, "Peripheral Advertise Started.")
            // Update UI or show status message
        }

        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "Peripheral Advertise Failed: $errorCode")
            // Update UI or show error message
        }
    }

    private fun postDeviceChange(device: BluetoothDevice, toAdd: Boolean) {
        mHandler.post {
            if (toAdd) {
                mConnectedDevices.add(device)
            } else {
                mConnectedDevices.remove(device)
            }
            // Trigger periodic notification when devices are connected
            mHandler.removeCallbacks(mNotifyRunnable)
            if (mConnectedDevices.isNotEmpty()) {
                mHandler.post(mNotifyRunnable)
            }
        }
    }

    private fun notifyConnectedDevices() {
        for (device in mConnectedDevices) {
            val readCharacteristic = mGattServer!!.getService(DeviceProfile.SERVICE_UUID)
                .getCharacteristic(DeviceProfile.CHARACTERISTIC_UUID)
            readCharacteristic.setValue(getStoredValue)
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            mGattServer!!.notifyCharacteristicChanged(device, readCharacteristic, false)
        }
    }

    private val mLock = Any()

    private val getStoredValue: ByteArray
        get() {
            synchronized(mLock) {
                return DeviceProfile.getRandomValue()
            }
        }

    @Composable
    fun MyBluetoothApp() {
        MaterialTheme {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxSize()
                ) {
                    DeviceList(connectedDevices = mConnectedDevices)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    @Composable
    fun DeviceList(connectedDevices: List<BluetoothDevice>) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item { Text(text = "Девайсы:") }
            items(connectedDevices) { device ->
                Text(text = device.name ?: "Unknown Device", modifier = Modifier.padding(16.dp))
            }
        }
    }

    private val requiredPermissions = arrayOf(
        Manifest.permission.BLUETOOTH,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        Manifest.permission.BLUETOOTH_ADVERTISE
    )

    companion object {
        private const val TAG = "PeripheralActivity"
    }
}
