package com.jarvis.assistant.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * HeadsetAudioRouter: Manages audio routing between:
 * 1. External headsets (Bluetooth earbuds / AirPods / wired headsets) -> mic & audio to headset.
 * 2. Device Loudspeaker -> When NO headset is connected, forces playback strictly through
 *    the phone's main loudspeaker (TYPE_BUILTIN_SPEAKER), NEVER the call earpiece speaker.
 */
class HeadsetAudioRouter(private val context: Context) {

    companion object {
        private const val TAG = "HeadsetAudioRouter"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var isRegistered = false
    private var isScoStarted = false
    @Volatile
    private var cachedIsHeadsetConnected = false
    @Volatile
    private var communicationRouteActive = false

    private val headsetReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            Log.d(TAG, "Audio broadcast received: $action")
            when (action) {
                AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED -> {
                    val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_ERROR)
                    if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                        Log.i(TAG, "Bluetooth SCO connected: microphone active on Bluetooth headset.")
                    } else if (state == AudioManager.SCO_AUDIO_STATE_DISCONNECTED) {
                        Log.i(TAG, "Bluetooth SCO disconnected.")
                        routeToOptimalAudio()
                    }
                }
                Intent.ACTION_HEADSET_PLUG -> {
                    val state = intent.getIntExtra("state", -1)
                    Log.i(TAG, "Wired headset plug state changed: state=$state")
                    routeToOptimalAudio()
                }
                else -> {
                    routeToOptimalAudio()
                }
            }
        }
    }

    private val audioDeviceCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                Log.d(TAG, "Audio device added, re-routing audio...")
                routeToOptimalAudio()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                Log.d(TAG, "Audio device removed, re-routing audio...")
                routeToOptimalAudio()
            }
        }
    } else null

    fun start() {
        try {
            if (!isRegistered) {
                val filter = IntentFilter().apply {
                    addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
                    addAction(Intent.ACTION_HEADSET_PLUG)
                    addAction("android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED")
                    addAction("android.bluetooth.adapter.action.STATE_CHANGED")
                }
                context.registerReceiver(headsetReceiver, filter)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && audioDeviceCallback != null) {
                    audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
                }

                isRegistered = true
            }

            routeToOptimalAudio()
        } catch (e: Exception) {
            Log.e(TAG, "Error starting HeadsetAudioRouter: ${e.message}", e)
        }
    }

    /**
     * Checks if an external headset (Bluetooth or wired) is connected.
     * Uses volatile cached state updated on device callbacks to prevent 50Hz IPC overhead.
     */
    fun isHeadsetConnected(): Boolean = cachedIsHeadsetConnected

    /** True only when a communication route was selected (not merely requested). */
    fun isCommunicationModeActive(): Boolean =
        communicationRouteActive && audioManager.mode == AudioManager.MODE_IN_COMMUNICATION

    private fun findConnectedHeadset(): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val available = audioManager.availableCommunicationDevices
            return available.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }
        } else {
            val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            return inputs.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }
        }
    }

    /**
     * Dynamically chooses and sets the best communication device:
     * - Headset connected: route to headset mic and speaker.
     * - No headset connected: route to phone's LOUDSPEAKER (never the call speaker).
     */
    fun routeToOptimalAudio() {
        try {
            val targetHeadset = findConnectedHeadset()
            cachedIsHeadsetConnected = (targetHeadset != null)

            if (targetHeadset != null) {
                // External Headset Connected: Route to headset
                Log.i(TAG, "External headset detected: ${targetHeadset.productName} (type=${targetHeadset.type}). Routing to headset.")
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val success = audioManager.setCommunicationDevice(targetHeadset)
                    communicationRouteActive = success
                    Log.i(TAG, "setCommunicationDevice: ${targetHeadset.productName} -> success=$success")
                } else {
                    if (audioManager.isBluetoothScoAvailableOffCall && !audioManager.isBluetoothScoOn) {
                        audioManager.startBluetoothSco()
                        audioManager.isBluetoothScoOn = true
                        isScoStarted = true
                    }
                    // Before Android 12 the platform selects a wired headset automatically;
                    // Bluetooth communication uses SCO above.
                    communicationRouteActive = true
                }
                audioManager.isSpeakerphoneOn = false
            } else {
                // No external headset: keep playback on the main loudspeaker, but use Android's
                // communication route so VOICE_COMMUNICATION capture can benefit from AEC.
                // If the OS cannot select the built-in speaker as a communication device, fall
                // back to ordinary media routing and let the VAD use its conservative no-AEC path.
                Log.i(TAG, "No external headset connected. Selecting built-in speaker for communication audio.")

                if (isScoStarted) {
                    try {
                        audioManager.stopBluetoothSco()
                        audioManager.isBluetoothScoOn = false
                    } catch (_: Exception) {}
                    isScoStarted = false
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val builtInSpeaker = audioManager.availableCommunicationDevices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    }
                    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                    val routed = builtInSpeaker?.let { audioManager.setCommunicationDevice(it) } == true
                    if (!routed) {
                        audioManager.clearCommunicationDevice()
                        // Keep the legacy speakerphone fallback, but do not claim the route is
                        // suitable for AEC. The detector therefore uses its guarded fallback.
                        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                        audioManager.isSpeakerphoneOn = true
                        communicationRouteActive = false
                        Log.w(TAG, "Communication speaker route unavailable; keeping speakerphone fallback without trusted AEC.")
                    } else {
                        communicationRouteActive = true
                        Log.i(TAG, "Communication route selected: ${builtInSpeaker?.productName ?: "built-in speaker"}; AEC may be used when the effect reports enabled.")
                    }
                } else {
                    // Pre-Android 12 speakerphone routing uses MODE_IN_COMMUNICATION + speakerphone.
                    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                    audioManager.isSpeakerphoneOn = true
                    communicationRouteActive = true
                }
            }
        } catch (e: Exception) {
            communicationRouteActive = false
            Log.w(TAG, "Failed in routeToOptimalAudio: ${e.message}")
        }
    }

    fun stop() {
        try {
            if (isRegistered) {
                try {
                    context.unregisterReceiver(headsetReceiver)
                } catch (_: Exception) {}

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && audioDeviceCallback != null) {
                    try {
                        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
                    } catch (_: Exception) {}
                }

                isRegistered = false
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            }

            if (isScoStarted) {
                try {
                    audioManager.stopBluetoothSco()
                    audioManager.isBluetoothScoOn = false
                } catch (_: Exception) {}
                isScoStarted = false
            }

            audioManager.mode = AudioManager.MODE_NORMAL
            audioManager.isSpeakerphoneOn = true
            communicationRouteActive = false
            cachedIsHeadsetConnected = false
            Log.i(TAG, "HeadsetAudioRouter stopped, audio mode reset to NORMAL (speakerphone active).")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping HeadsetAudioRouter: ${e.message}", e)
        }
    }
}
