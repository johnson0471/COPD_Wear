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

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.findNavController
import androidx.navigation.fragment.NavHostFragment
import androidx.wear.ambient.AmbientModeSupport
import androidx.wear.ambient.AmbientModeSupport.AmbientCallbackProvider
import dagger.hilt.android.AndroidEntryPoint
import java.io.IOException
import java.io.OutputStream
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

    private var bluetoothSocket: BluetoothSocket? = null
    private val acceptThread: AcceptThread? = null
    private val handler = Handler(Looper.getMainLooper())
    private val Name = "MyBTService"
    private val btUUID =
        UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val viewModel: MainViewModel by viewModels()

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment)
        return navController.navigateUp()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startBluetoothServer()
    }

    //處理手錶按鍵的區塊
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

    override fun onDestroy() {
        super.onDestroy()
        acceptThread?.cancel()
    }

    private fun startBluetoothServer() {
        if (acceptThread == null) {
            val acceptThread = AcceptThread()
            acceptThread.start()
            Log.d(TAG, "startBluetoothServer")
        }

    }

    //定義一個繼承自 Thread 的內部類別 AcceptThread，用於在後台執行緒中等待藍芽連線
    inner class AcceptThread : Thread() {

        @SuppressLint("MissingPermission")
        private val mmServerSocket: BluetoothServerSocket? = run {
            val bluetoothAdapter: BluetoothAdapter =
                (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            try {
                bluetoothAdapter.listenUsingInsecureRfcommWithServiceRecord(Name, btUUID)
            } catch (e: IOException) {
                Log.e(TAG, e.toString())
            } as BluetoothServerSocket?
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
                    Log.d(TAG, "mmServerSocket is accept")
                    manageMyConnectedSocket(it)
                    mmServerSocket?.close()
                    shouldLoop = false
                    val bluetoothViewModel =
                        ViewModelProvider(this@MainActivity)[BluetoothViewModel::class.java]
                    bluetoothViewModel.bluetoothSocket = socket
                    Log.d(TAG, socket.toString())
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
//        fun sendData(data:String) {
//            try {
//                val output = bluetoothSocket?.outputStream
//                output?.write(data.toByteArray())
//                val message = handler.obtainMessage()
//                message.obj = data
//                handler.sendMessage(message)
//                Log.d(TAG,bluetoothSocket.toString())
//                Log.d(TAG,data)
//            }catch (e:IOException){
//                Log.e(TAG,"Couldn't send data",e)
//            }
//        }
    }



    private fun manageMyConnectedSocket(socket: BluetoothSocket) {
        val connectedThread = ConnectedThread(socket)
        connectedThread.start()
        Log.d(TAG, "connectedThread.start()")
    }


    inner class ConnectedThread(private val mmSocket: BluetoothSocket) : Thread() {

        private val mmOutStream: OutputStream = mmSocket.outputStream
        private val mmBuffer: ByteArray = ByteArray(1024) // mmBuffer store for the stream

        // Call this from the main activity to send data to the remote device.
        fun write(bytes: ByteArray) {
            try {
                mmOutStream.write(bytes)
                val writtenMsg = handler.obtainMessage(MESSAGE_WRITE, -1, -1, bytes)
                writtenMsg.sendToTarget()
            } catch (e: IOException) {
                Log.e(TAG, "Error occurred when sending data", e)

                // Send a failure message back to the activity.
                val writeErrorMsg = handler.obtainMessage(MESSAGE_TOAST)
                val bundle = Bundle().apply {
                    putString("toast", "Couldn't send data to the other device")
                }
                writeErrorMsg.data = bundle
                handler.sendMessage(writeErrorMsg)
            }

            // Share the sent message with the UI activity.
            val writtenMsg = handler.obtainMessage(
                MESSAGE_WRITE, -1, -1, mmBuffer
            )
            writtenMsg.sendToTarget()
        }

        // Call this method from the main activity to shut down the connection.
        fun cancel() {
            try {
                mmSocket.close()
            } catch (e: IOException) {
                Log.e(TAG, "Could not close the connect socket", e)
            }
        }
    }




    companion object {
        const val MESSAGE_READ: Int = 0
        const val MESSAGE_WRITE: Int = 1
        const val MESSAGE_TOAST: Int = 2
    }
}

