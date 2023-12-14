package com.example.exercise

import android.bluetooth.BluetoothSocket
import androidx.lifecycle.ViewModel

class BluetoothViewModel :ViewModel() {
    var bluetoothSocket: BluetoothSocket? = null
}