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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseState
import androidx.health.services.client.data.ExerciseUpdate
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.wear.ambient.AmbientModeSupport
import com.example.exercise.databinding.FragmentExerciseBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.util.Timer
import java.util.TimerTask
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Fragment showing the exercise controls and current exercise metrics.
 */
@AndroidEntryPoint
class ExerciseFragment : Fragment() {

    @Inject
    lateinit var healthServicesManager: HealthServicesManager

    private val viewModel: MainViewModel by activityViewModels()

    private var _binding: FragmentExerciseBinding? = null
    private val binding get() = _binding!!
    private var serviceConnection = ExerciseServiceConnection()

    private var cachedExerciseState = ExerciseState.ENDED
    private var activeDurationCheckpoint =
        ExerciseUpdate.ActiveDurationCheckpoint(Instant.now(), Duration.ZERO)
    private var chronoTickJob: Job? = null
    private var uiBindingJob: Job? = null

    private lateinit var ambientController: AmbientModeSupport.AmbientController
    private lateinit var ambientModeHandler: AmbientModeHandler

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentExerciseBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.startEndButton.setOnClickListener {
            // 應用程式可能需要相當長的時間才能在狀態之間進行轉換； 將按鈕置於停用狀態以提供 UI 回饋。
            it.isEnabled = false
            startEndExercise()
        }
        binding.pauseResumeButton.setOnClickListener {
            // 應用程式可能需要相當長的時間才能在狀態之間進行轉換； 將按鈕置於停用狀態以提供 UI 回饋。
            it.isEnabled = false
            pauseResumeExercise()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val capabilities =
                    healthServicesManager.getExerciseCapabilities() ?: return@repeatOnLifecycle
                val supportedTypes = capabilities.supportedDataTypes
                // Set enabled state for relevant text elements.
                binding.heartRateText.isEnabled = DataType.HEART_RATE_BPM in supportedTypes
                binding.stepText.isEnabled = DataType.STEPS in supportedTypes
                binding.distanceText.isEnabled = DataType.DISTANCE in supportedTypes


            }
//            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
//                viewModel.keyPressFlow.collect {
//                    healthServicesManager.markLap()
//                }
//            }
        }

        // Ambient Mode
        ambientModeHandler = AmbientModeHandler()
        ambientController = AmbientModeSupport.attach(requireActivity())
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.ambientEventFlow.collect {
                    ambientModeHandler.onAmbientEvent(it)
                }
            }
        }

        // Bind to our service. Views will only update once we are connected to it.
        ExerciseService.bindService(requireContext().applicationContext, serviceConnection)
        bindViewsToService()

    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Unbind from the service.
        ExerciseService.unbindService(requireContext().applicationContext, serviceConnection)
        _binding = null
    }

    //當開始按鈕按下時，會檢查目前是否為運動狀態，如果已結束就重新開始運動，否則確認exerciseService狀態不為空並結束運動
    private fun startEndExercise() {
        if (cachedExerciseState.isEnded) {
            Log.d(TAG, "startExercise")
            tryStartExercise()
        } else {
            Log.d(TAG, "endExercise")
            checkNotNull(serviceConnection.exerciseService) {
                "Failed to achieve ExerciseService instance"
            }.endExercise()
        }
    }

    private fun tryStartExercise() {
        viewLifecycleOwner.lifecycleScope.launch {
            if (healthServicesManager.isTrackingExerciseInAnotherApp()) {
                // Show the user a confirmation screen.
                findNavController().navigate(R.id.to_newExerciseConfirmation)
            } else if (!healthServicesManager.isExerciseInProgress()) {
                checkNotNull(serviceConnection.exerciseService) {
                    "Failed to achieve ExerciseService instance"
                }.startExercise()
            }
        }
    }

    private fun pauseResumeExercise() {
        val service = checkNotNull(serviceConnection.exerciseService) {
            "Failed to achieve ExerciseService instance"
        }
        if (cachedExerciseState.isPaused) {
            service.resumeExercise()
        } else {
            service.pauseExercise()
        }
    }

    private fun bindViewsToService() {
        if (uiBindingJob != null) return

        uiBindingJob = viewLifecycleOwner.lifecycleScope.launch {
            serviceConnection.repeatWhenConnected { service ->
                // Use separate launch blocks because each .collect executes indefinitely.
                launch {
                    service.exerciseState.collect {
                        updateExerciseStatus(it)
                    }
                }
                launch {
                    service.latestMetrics.collect {
                        it?.let {
                            updateMetrics(it)
                        }
                        sixMinTrain()
                    }

                }
//                launch {
//                    service.exerciseLaps.collect {
//                        updateLaps(it)S
//                    }
//                }
                launch {
                    service.activeDurationCheckpoint.collect {
                        // We don't update the chronometer here since these updates come at irregular
                        // intervals. Instead we store the duration and update the chronometer with
                        // our own regularly-timed intervals.
                        activeDurationCheckpoint = it
                        Log.d(TAG, it.activeDuration.toString().replace("PT", "").replace("S", ""))
//                        val activeSecond = it.activeDuration.toString().replace("PT","").replace("S","").toInt()
//                        if (activeSecond == 60){
//
//                        }
//                        val duration = it.displayDuration(Instant.now(), cachedExerciseState)
//                        Log.d(TAG,duration.seconds.toString())
                    }
                }
            }
        }
    }

    private fun sixMinTrain(second: Int = 20) {
        val duration = activeDurationCheckpoint.displayDuration(
            Instant.now(),
            cachedExerciseState
        )
        Log.d(TAG, "state = $cachedExerciseState")
        //擷取總秒數，當秒數=360時重製計時器並發出警示音
        if (cachedExerciseState == ExerciseState.ACTIVE) {
            val totalSecond = duration.seconds.toInt()
            Log.d(TAG, "sec = ${duration.seconds}")
            while (totalSecond == second || totalSecond > second) {
                stopChronometer()
                Log.d(TAG, "update")
                // 呼叫exerciseService停止運動
                val service = checkNotNull(serviceConnection.exerciseService)
                service.endExercise()
//                showNotification()
//            serviceConnection.run { exerciseService?.endExercise() }
//            updateExerciseStatus(cachedExerciseState)
//            Log.d(TAG, "state = $cachedExerciseState")
//                alertSound()
                break
            }
        }
        val handler = Handler()
        handler.postDelayed({
            updateChronometer()
        }, 1000)
    }

//    private fun alertSound() {
//        val mediaPlayer = MediaPlayer.create(context, R.raw.sound_file_1)
//        mediaPlayer.start()
//        val timer = Timer()
//        timer.schedule(object : TimerTask() {
//            override fun run() {
//                if (mediaPlayer.isPlaying) {
//                    mediaPlayer.stop()
//                    mediaPlayer.release()
//                    Log.d(TAG, "Music is stop")
//                    timer.cancel()
//                }
//            }
//        }, 2000)
//    }

    //當運動結束時，播放2次警示音後結束
    private fun alertSound() {
        val mediaPlayer = MediaPlayer.create(context, R.raw.sound_file_1)
        val repeatCount = 2
        var playCount = 0

        // 音樂播放完成時的處理
        mediaPlayer.setOnCompletionListener { mp ->
            if (playCount < repeatCount) {
                playCount++
                mp.start()  // 重複播放
                Log.d(TAG,"第${playCount}次撥放")
            } else {
                // 停止播放並釋放資源
                if (mp.isPlaying) {
                    mp.stop()
                    mp.reset()
                }
                Log.d(TAG, "Music is stop")
            }
        }
        mediaPlayer.start()
    }

    private fun unbindViewsFromService() {
        uiBindingJob?.cancel()
        uiBindingJob = null
    }

    private fun updateExerciseStatus(state: ExerciseState) {
        val previousStatus = cachedExerciseState
        if (previousStatus.isEnded && !state.isEnded) {
            // 重置錶面的數據
            resetDisplayedFields()
        }
        //如果現在狀態為運動且不在環境模式狀態中
        //&& !ambientController.isAmbient
        if (state == ExerciseState.ACTIVE) {
            startChronometer()
        } else {
            stopChronometer()
        }

        updateButtons(state)
        cachedExerciseState = state
    }

    private fun updateButtons(state: ExerciseState) {
        Log.d(TAG, "updateButtons")
        binding.startEndButton.setText(if (state.isEnded) R.string.start else R.string.end)
        binding.startEndButton.isEnabled = true
        binding.pauseResumeButton.setText(if (state.isPaused) R.string.resume else R.string.pause)
        binding.pauseResumeButton.isEnabled = !state.isEnded
    }

    private fun updateMetrics(latestMetrics: DataPointContainer) {
        latestMetrics.getData(DataType.HEART_RATE_BPM).let {
            if (it.isNotEmpty()) {
                val heartRate = it.last().value.roundToInt().toString()
                binding.heartRateText.text = heartRate
                Log.d(TAG, "心跳 = $heartRate ")
            }
        }
        latestMetrics.getData(DataType.DISTANCE_TOTAL)?.let {
            binding.distanceText.text = formatDistanceKm(it.total).toString()
            Log.d(TAG, formatDistanceKm(it.total).toString())
        }
        latestMetrics.getData(DataType.STEPS_TOTAL).let {
            if (it != null) {
                binding.stepText.text = it.total.toString()
                Log.d(TAG, "step = ${it.total}")
            } else Log.d(TAG, "Steps is Empty")

        }
    }

    private fun showNotification() {
        val notificationManager =
            context?.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channel =
            NotificationChannel("channel_id", "Channel Name", NotificationManager.IMPORTANCE_HIGH)
        notificationManager.createNotificationChannel(channel)


        val builder = Notification.Builder(context, "channel_id")
            .setContentTitle("Your Title")
            .setContentText("Your Content")
            .setSmallIcon(R.drawable.ic_run)

        val wearableExtender = Notification.WearableExtender()
        // 在 WearableExtender 中設定 Wear OS 相關的選項
        builder.extend(wearableExtender)

        notificationManager.notify(1, builder.build())
    }

//    private fun updateLaps(laps: Int) {
//        binding.lapsText.text = laps.toString()
//    }

    private fun startChronometer() {
        if (chronoTickJob == null) {
            chronoTickJob = viewLifecycleOwner.lifecycleScope.launch {
                viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    while (true) {
                        delay(CHRONO_TICK_MS)
                        updateChronometer()
                    }
                }
            }
        }
    }

    private fun stopChronometer() {
        chronoTickJob?.cancel()
        chronoTickJob = null
    }

    //更新錶面的時間
    private fun updateChronometer() {
        val duration = activeDurationCheckpoint.displayDuration(Instant.now(), cachedExerciseState)
        binding.elapsedTime.text = formatElapsedTime(duration, !ambientController.isAmbient)
    }

    private fun resetDisplayedFields() {
        getString(R.string.empty_metric).let {
            binding.heartRateText.text = it
            binding.stepText.text = it
            binding.distanceText.text = it
        }
        binding.elapsedTime.text = formatElapsedTime(Duration.ZERO, true)
    }

    // -- Ambient Mode support

    private fun setAmbientUiState(isAmbient: Boolean) {
        // Change icons to white while in ambient mode.
        val iconTint = if (isAmbient) {
            Color.WHITE
        } else {
            resources.getColor(R.color.primary_orange, null)
        }
        ColorStateList.valueOf(iconTint).let {
            binding.clockIcon.imageTintList = it
            binding.heartRateIcon.imageTintList = it
            binding.stepsIcon.imageTintList = it
            binding.distanceIcon.imageTintList = it
        }

        // Hide the buttons in ambient mode.
        val buttonVisibility = if (isAmbient) View.INVISIBLE else View.VISIBLE
        buttonVisibility.let {
            binding.startEndButton.visibility = it
            binding.pauseResumeButton.visibility = it
        }
    }

    private fun performOneTimeUiUpdate() {
        val service = checkNotNull(serviceConnection.exerciseService) {
            "Failed to achieve ExerciseService instance"
        }
        updateExerciseStatus(service.exerciseState.value)
//        updateLaps(service.exerciseLaps.value)

        service.latestMetrics.value?.let { updateMetrics(it) }

        activeDurationCheckpoint = service.activeDurationCheckpoint.value
        updateChronometer()
    }

    inner class AmbientModeHandler {
        internal fun onAmbientEvent(event: AmbientEvent) {
            when (event) {
                is AmbientEvent.Enter -> onEnterAmbient()
                is AmbientEvent.Exit -> onExitAmbient()
                is AmbientEvent.Update -> onUpdateAmbient()
                else -> {}
            }
        }

        private fun onEnterAmbient() {
            // Note: Apps should also handle low-bit ambient and burn-in protection.
            unbindViewsFromService()
            setAmbientUiState(true)
            performOneTimeUiUpdate()
        }

        private fun onExitAmbient() {
            performOneTimeUiUpdate()
            setAmbientUiState(false)
            bindViewsToService()
        }

        private fun onUpdateAmbient() {
            performOneTimeUiUpdate()
        }
    }

    private companion object {
        const val CHRONO_TICK_MS = 200L
    }


}
