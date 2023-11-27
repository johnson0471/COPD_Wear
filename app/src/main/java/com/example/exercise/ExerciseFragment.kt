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


import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.MediaPlayer
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.PowerManager.WakeLock
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.util.Timer
import java.util.TimerTask
import javax.inject.Inject
import kotlin.concurrent.timer
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

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var countDownTimer: CountDownTimer

    //    private var isPaused = false
    private var remainingTime: Long = 21000
    private var pausedTime: Long = 0

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

//        // 初始化 CountDownTimer
//        countDownTimer = createCountDownTimer(remainingTime)
//
//        // 開始計時
//        countDownTimer.start()
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
//            sixMinTrain()
//            countDownTimer = createCountDownTimer(remainingTime)
//            countDownTimer.start()
        } else {
            Log.d(TAG, "endExercise")
            checkNotNull(serviceConnection.exerciseService) {
                "Failed to achieve ExerciseService instance"
            }.endExercise()
//            countDownTimer.cancel()
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
            //new
//            countDownTimer = createCountDownTimer(pausedTime)
//            countDownTimer.start()
        } else {
            service.pauseExercise()
            //new
//            pausedTime = remainingTime
//            countDownTimer.cancel()
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
                    }
                }
//                launch { sixMinTrain() }
            }
        }
    }

    private fun createCountDownTimer(time: Long): CountDownTimer {
        return object : CountDownTimer(time, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                remainingTime = millisUntilFinished
                updateUI((millisUntilFinished / 1000).toInt())
            }

            override fun onFinish() {
                updateUI(0)
                countDownTimer.cancel()
                handler.post {
                    updateChronometer()
                }
                // 呼叫exerciseService停止運動
                val service = checkNotNull(serviceConnection.exerciseService)
                service.endExercise()
                wakeUpScreen()
                remainingTime = 21000
            }
        }
    }

    private fun updateUI(seconds: Int) {
        Log.d(TAG, "Time: $seconds seconds")
    }


    private fun sixMinTrain2() {
        val timer = Timer()
        val timerTask = object : TimerTask() {
            var second = 0
            override fun run() {
                if (second < 20) {
                    Log.d(TAG, "時間${second}秒")
                    second++
                } else {
                    Log.d(TAG, "計時結束")
                    stopChronometer()
                    handler.post {
                        updateChronometer()
                    }
                    timer.cancel()
                    // 呼叫exerciseService停止運動
                    val service = checkNotNull(serviceConnection.exerciseService)
                    service.endExercise()
//                    //更新錶面的時間
//                    updateChronometer()
//                    //撥放警示音
//                    playSoundAsync()
                }
            }
        }
        timer.scheduleAtFixedRate(timerTask, 1000, 1000)
    }


    //六分鐘訓練,second為運行中的秒數
    private suspend fun sixMinTrain(second: Int = 30) {
        val duration = activeDurationCheckpoint.displayDuration(
            Instant.now(),
            cachedExerciseState
        )
        Log.d(TAG, "state = $cachedExerciseState")
        //擷取總秒數，當秒數=360時重製計時器並發出警示音
        if (cachedExerciseState == ExerciseState.ACTIVE) {
            val totalSecond = duration.seconds.toInt()
            Log.d(TAG, "sec = ${duration.seconds}")
            if (totalSecond > second) {
//                stopChronometer()
                Log.d(TAG, "update")
                // 呼叫exerciseService停止運動
                val service = checkNotNull(serviceConnection.exerciseService)
                service.endExercise()
                wakeUpScreen()
                //更新錶面的時間
                updateChronometer()
                //撥放警示音
                playSoundAsync()
            }
        }
    }


    //當運動結束時，播放2次警示音後結束
//    private fun alertSound() {
//        val mediaPlayer = MediaPlayer.create(context, R.raw.sound_file_1)
//        val repeatCount = 2
//        var playCount = 1
//
//        // 音樂播放完成時的處理
//        mediaPlayer.setOnCompletionListener { mp ->
//            if (playCount < repeatCount) {
//                playCount++
//                mp.start()  // 重複播放
//            } else {
//                // 停止播放並釋放資源
//                if (mp.isPlaying) {
//                    try {
//                        mp.pause()
//                        mp.stop()
//                        mp.release()
//                        Log.d(TAG, "Music is stop")
//                    } catch (e: Exception) {
//                        e.printStackTrace()
//                        Log.d(TAG, e.toString())
//                    }
//                }
//            }
//        }
//        mediaPlayer.start()
//    }

    //當運動結束時，播放2次警示音後結束
    private suspend fun playSoundAsync() = withContext(Dispatchers.IO) {
        try {
            repeat(2) {
                val mediaPlayer = MediaPlayer.create(context, R.raw.sound_file_1)
                mediaPlayer?.start()
                // 等待撥放完成
                while (mediaPlayer?.isPlaying == true) {
                    delay(100)
                }
                // 釋放資源
                mediaPlayer?.release()
                // 等待一點時間，避免立即啟動下一個 MediaPlayer
                delay(200)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun wakeUpScreen() {
        val powerManager = context?.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock: WakeLock? = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "COPD:WakeLockTag"
        )
        Log.d(TAG, "wakeUpScreen")
        wakeLock?.acquire(10 * 1000L /*10 minutes*/)
        Log.d(TAG, "wakeUpScreen1")
        wakeLock?.release()
        Log.d(TAG, "wakeUpScreen2")
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
        if (state == ExerciseState.ACTIVE && !ambientController.isAmbient) {
            startChronometer()
            Log.d(TAG, "startChronometer")
        } else {
            stopChronometer()
            Log.d(TAG, "stopChronometer")
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


//    private fun updateLaps(laps: Int) {
//        binding.lapsText.text = laps.toString()
//    }

    private fun startChronometer2() {
        if (chronoTickJob == null) {
            chronoTickJob = viewLifecycleOwner.lifecycleScope.launch {
                viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    while (true) {
                        val countDownTimer = object : CountDownTimer(20000, 1000) {
                            override fun onTick(millisUntilFinished: Long) {
                                val secondsRemaining = millisUntilFinished / 1000
                                Log.d(TAG, secondsRemaining.toString())
                                updateChronometer()
                            }

                            override fun onFinish() {
                                Log.d(TAG, "A")
                                stopChronometer()
                            }
                        }
                        countDownTimer.start()
                    }
                }
            }
        }
    }

    private fun startChronometer() {
        if (chronoTickJob == null) {
            chronoTickJob = viewLifecycleOwner.lifecycleScope.launch {
                viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    while (true) {
                        delay(CHRONO_TICK_MS)
                        updateChronometer()
                        val duration = activeDurationCheckpoint.displayDuration(
                            Instant.now(),
                            cachedExerciseState
                        )
                        Log.d(TAG, duration.seconds.toString())
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

        service.latestMetrics.value?.let {
            updateMetrics(it)
        }
        activeDurationCheckpoint = service.activeDurationCheckpoint.value
//        sixMinTrain()
        updateChronometer()
        Log.d(TAG, "performOneTimeUiUpdate")
    }


    inner class AmbientModeHandler {
        internal  fun onAmbientEvent(event: AmbientEvent) {
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
            Log.d(TAG, "onEnterAmbient")
        }


        private fun onExitAmbient() {
            performOneTimeUiUpdate()
            setAmbientUiState(false)
            bindViewsToService()
            Log.d(TAG, "onExitAmbient")
        }

        private fun onUpdateAmbient() {
            performOneTimeUiUpdate()
            Log.d(TAG, "onUpdateAmbient")
        }
    }

    private companion object {
        const val CHRONO_TICK_MS = 200L
    }


}
