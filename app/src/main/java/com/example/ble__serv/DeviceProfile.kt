package com.example.ble__serv

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothProfile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.random.Random

object DeviceProfile {

    // Service UUID to expose our time characteristics
    var SERVICE_UUID: UUID = UUID.fromString("1706BBC0-88AB-4B8D-877E-2237916EE929")

    // Read-only characteristic providing number of elapsed seconds since offset
    var CHARACTERISTIC_UUID: UUID = UUID.fromString("275348FB-C14D-4FD5-B434-7C3F351DEA5F")

    /**
     * Function that converts the connection state with a BLE device into a readable text
     */
    fun getStateDescription(state: Int): String {
        return when (state) {
            BluetoothProfile.STATE_CONNECTED -> "Connected"
            BluetoothProfile.STATE_CONNECTING -> "Connecting"
            BluetoothProfile.STATE_DISCONNECTED -> "Disconnected"
            BluetoothProfile.STATE_DISCONNECTING -> "Disconnecting"
            else -> "Unknown State $state"
        }
    }

    /**
     * Function used to understand the status of a BLE operation
     */
    fun getStatusDescription(status: Int): String {
        return when (status) {
            BluetoothGatt.GATT_SUCCESS -> "SUCCESS"
            else -> "Unknown Status $status"
        }
    }

    /**
     * Generates a random value within a specified range.
     */
    fun getRandomValue(): ByteArray {
        return bytesFromInt(Random.nextInt(0, 100))
    }


    /**
     * Function that converts an integer to a byte array suitable for GATT transmission.
     */
    fun bytesFromInt(value: Int): ByteArray {
        // Convert result into raw bytes. GATT APIs expect LE order
        return ByteBuffer.allocate(4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(value)
            .array()
    }
}