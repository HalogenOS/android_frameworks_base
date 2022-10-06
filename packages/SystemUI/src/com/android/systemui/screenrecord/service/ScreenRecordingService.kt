/* Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.screenrecord.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.media.projection.StopReason
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes
import com.android.systemui.Flags
import com.android.systemui.mediaprojection.MediaProjectionCaptureTarget
import com.android.systemui.res.R
import com.android.systemui.screenrecord.RecordingServiceStrings
import com.android.systemui.screenrecord.ScreenMediaRecorder
import com.android.systemui.screenrecord.ScreenMediaRecorder.SavedRecording
import com.android.systemui.screenrecord.ScreenRecordingAudioSource
import com.android.systemui.screenrecord.domain.ScreenRecordingPreferenceUtil
import com.android.systemui.screenrecord.notification.NotificationInteractor
import com.android.systemui.screenrecord.notification.ScreenRecordingServiceNotificationInteractor
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "ScreenRecordingService"

open class ScreenRecordingService
protected constructor(
    private val tag: String,
    private val createNotificationInteractor: Context.() -> NotificationInteractor,
    private val onRecordingSaved:
        ScreenRecordingService.(
            recordingContext: RecordingContext, recording: SavedRecording,
        ) -> Unit,
) : ComponentService() {

    @Suppress("unused") // used by the system
    constructor() :
        this(
            tag = TAG,
            createNotificationInteractor = {
                ScreenRecordingServiceNotificationInteractor(
                    context = this,
                    notificationManager = getSystemService(NotificationManager::class.java)!!,
                    strings = RecordingServiceStrings(resources),
                    channelId = CHANNEL_ID,
                    tag = TAG,
                    serviceClass = ScreenRecordingService::class.java,
                )
            },
            onRecordingSaved = { recordingContext, recording ->
                notificationInteractor.notifySaved(
                    notificationId = recordingContext.notificationId,
                    audioSource = recordingContext.audioSource,
                    savedRecording = recording,
                )
            },
        )

    private val backgroundContext = Dispatchers.IO
    private val binder = BinderInterface()
    private val screenMediaRecorderListener: ScreenMediaRecorder.ScreenMediaRecorderListener =
        object : ScreenMediaRecorder.ScreenMediaRecorderListener {
            override fun onStarted() {
                launchCallbackAction { onRecordingStarted() }
            }

            override fun onInfo(mr: MediaRecorder?, what: Int, extra: Int) {
                launchCallbackAction { onRecordingInterrupted(userId, StopReason.STOP_ERROR) }
            }

            override fun onStopped(userId: Int, @StopReason stopReason: Int) {
                launchCallbackAction { onRecordingInterrupted(userId, stopReason) }
            }
        }

    private lateinit var notificationInteractor: NotificationInteractor
    private lateinit var preferenceUtil: ScreenRecordingPreferenceUtil

    private var recordingContext: RecordingContext? = null
    private var callback: IScreenRecordingServiceCallback? = null

    override fun onCreate() {
        super.onCreate()
        notificationInteractor = createNotificationInteractor()
        preferenceUtil = ScreenRecordingPreferenceUtil(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_STOP ->
                launchCallbackAction {
                    onRecordingInterrupted(
                        userId,
                        intent.getIntExtra(EXTRA_STOP_REASON, StopReason.STOP_UNKNOWN),
                    )
                }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder

    private fun RecordingContext.startRecording() {
        try {
            Log.d(tag, "Starting screen recording user=$userId $this")
            if (Flags.restoreShowTapsSetting()) {
                preferenceUtil.updateShowTaps(shouldShowTaps)
            } else {
                setShouldShowTouches(shouldShowTaps)
            }
            recorder.start()
            notificationInteractor.notifyRecording(
                notificationId = notificationId,
                audioSource = audioSource,
            )
        } catch (e: Exception) {
            if (Flags.restoreShowTapsSetting()) {
                preferenceUtil.restoreShowTapsSetting()
            } else {
                setShouldShowTouches(originalShouldShowTouches)
            }
            Log.d(tag, "Error starting screen recording", e)
            notificationInteractor.notifyErrorStarting(notificationId)
            showToast(R.string.screenrecord_start_error)
            stopSelf()
        }
    }

    private suspend fun RecordingContext.saveRecording() {
        try {
            Log.d(tag, "Saving screen recording")
            notificationInteractor.notifyProcessing(
                notificationId = notificationId,
                audioSource = audioSource,
            )
            val savedRecording: SavedRecording =
                withContext(backgroundContext) {
                    recorder.save().apply { callback?.onRecordingSaved(uri, thumbnail) }
                }
            onRecordingSaved(this, savedRecording)
        } catch (e: Exception) {
            notificationInteractor.notifyErrorSaving(notificationId)
            Log.e(tag, "Error saving screen recording", e)
            showToast(R.string.screenrecord_save_error)
        } finally {
            recorder.release()
            stopSelf()
        }
    }

    private fun RecordingContext.stopRecording(@StopReason reason: Int) {
        try {
            Log.d(tag, "Stopping screen recording reason=$reason")
            recordingContext = null
            if (Flags.restoreShowTapsSetting()) {
                preferenceUtil.restoreShowTapsSetting()
            } else {
                setShouldShowTouches(originalShouldShowTouches)
            }
            recorder.end(reason)
            coroutineScope.launch { saveRecording() }
        } catch (e: Exception) {
            notificationInteractor.notifyErrorSaving(notificationId)
            Log.e(tag, "Error stopping screen recording", e)
            showToast(R.string.screenrecord_save_error)
            recorder.release()
            stopSelf() // only stop if there is an error. Otherwise leave it to saveRecording
        }
    }

    private fun setShouldShowTouches(isOn: Boolean) {
        Settings.System.putInt(contentResolver, Settings.System.SHOW_TOUCHES, if (isOn) 1 else 0)
    }

    private fun getShouldShowTouches(): Boolean =
        Settings.System.getInt(contentResolver, Settings.System.SHOW_TOUCHES, 0) != 0

    private fun launchCallbackAction(action: IScreenRecordingServiceCallback.() -> Unit) {
        callback?.let { coroutineScope.launch(backgroundContext) { it.action() } }
    }

    private inner class BinderInterface : IScreenRecordingService.Stub() {

        override fun setCallback(serviceCallback: IScreenRecordingServiceCallback?) {
            callback = serviceCallback
        }

        override fun stopRecording(@StopReason reason: Int) {
            recordingContext?.stopRecording(reason)
        }

        override fun startRecording(
            captureTarget: MediaProjectionCaptureTarget?,
            audioSource: Int,
            displayId: Int,
            shouldShowTaps: Boolean,
            lowQuality: Boolean,
            longerDuration: Boolean,
            hevc: Boolean,
        ) {
            val screenRecordingAudioSource = ScreenRecordingAudioSource.entries[audioSource]
            RecordingContext(
                    notificationId = UUID.randomUUID().mostSignificantBits.toInt(),
                    originalShouldShowTouches = getShouldShowTouches(),
                    captureTarget = captureTarget,
                    audioSource = screenRecordingAudioSource,
                    displayId = displayId,
                    shouldShowTaps = shouldShowTaps,
                    recorder =
                        ScreenMediaRecorder(
                            this@ScreenRecordingService,
                            Handler(Looper.getMainLooper()),
                            Process.myUid(),
                            screenRecordingAudioSource,
                            captureTarget,
                            displayId,
                            screenMediaRecorderListener,
                            lowQuality,
                            longerDuration,
                            hevc,
                        ),
                )
                .also { context ->
                    recordingContext = context
                    context.startRecording()
                }
        }
    }

    protected data class RecordingContext(
        val recorder: ScreenMediaRecorder,
        val originalShouldShowTouches: Boolean,
        val captureTarget: MediaProjectionCaptureTarget?,
        val audioSource: ScreenRecordingAudioSource,
        val displayId: Int,
        val shouldShowTaps: Boolean,
        val notificationId: Int,
    )

    companion object {

        const val CHANNEL_ID = "screen_record"

        const val ACTION_STOP =
            "com.android.systemui.screenrecord.ScreenRecordingService.ACTION_STOP"
        const val ACTION_SHARE =
            "com.android.systemui.screenrecord.ScreenRecordingService.ACTION_SHARE"
        const val EXTRA_STOP_REASON =
            "com.android.systemui.screenrecord.ScreenRecordingService.EXTRA_STOP_REASON"
    }
}

private fun Service.showToast(@StringRes message: Int) {
    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
