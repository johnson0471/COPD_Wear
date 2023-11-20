/*
 * Copyright 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.exercise

import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.findNavController
import androidx.navigation.fragment.NavHostFragment
import androidx.wear.ambient.AmbientModeSupport
import androidx.wear.ambient.AmbientModeSupport.AmbientCallbackProvider
import dagger.hilt.android.AndroidEntryPoint
import java.io.IOException
import java.util.UUID

/**
 * This Activity serves a handful of functions:
 * - to host a [NavHostFragment]
 * - to capture KeyEvents
 * - to support Ambient Mode, because [AmbientCallbackProvider] must be an `Activity`.
 *
 * [MainViewModel] is used to coordinate between this Activity and the [ExerciseFragment], which
 * contains UI during an active exercise.
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity(R.layout.activity_main), AmbientCallbackProvider {

    private val NAME = "MyBTService"
    private val BTMODULEUUID =
        UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val viewModel: MainViewModel by viewModels()

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment)
        return navController.navigateUp()
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_STEM_1,
            KeyEvent.KEYCODE_STEM_2,
            KeyEvent.KEYCODE_STEM_3,
            KeyEvent.KEYCODE_STEM_PRIMARY -> {
                viewModel.sendKeyPress()
                true
            }
            else -> super.onKeyUp(keyCode, event)
        }
    }

    override fun getAmbientCallback(): AmbientModeSupport.AmbientCallback = AmbientModeCallback()

    inner class AmbientModeCallback : AmbientModeSupport.AmbientCallback() {
        override fun onEnterAmbient(ambientDetails: Bundle) {
            viewModel.sendAmbientEvent(AmbientEvent.Enter(ambientDetails))
        }

        override fun onExitAmbient() {
            viewModel.sendAmbientEvent(AmbientEvent.Exit)
        }

        override fun onUpdateAmbient() {
            viewModel.sendAmbientEvent(AmbientEvent.Update)
        }
    }
    private inner class AcceptThread : Thread() {

        private val mmServerSocket: BluetoothServerSocket? by lazy(LazyThreadSafetyMode.NONE) {
            val bluetoothAdapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            bluetoothAdapter?.listenUsingInsecureRfcommWithServiceRecord(NAME, BTMODULEUUID)
        }

        override fun run() {
            // Keep listening until exception occurs or a socket is returned.
            var shouldLoop = true
            while (shouldLoop) {
                val socket: BluetoothSocket? = try {
                    mmServerSocket?.accept()
                } catch (e: IOException) {
                    Log.e(TAG, "Socket's accept() method failed", e)
                    shouldLoop = false
                    null
                }
                socket?.also {
//                    manageMyConnectedSocket(it)
                    mmServerSocket?.close()
                    shouldLoop = false
                }
            }
        }

        // Closes the connect socket and causes the thread to finish.
        fun cancel() {
            try {
                mmServerSocket?.close()
            } catch (e: IOException) {
                Log.e(TAG, "Could not close the connect socket", e)
            }
        }
    }
}
