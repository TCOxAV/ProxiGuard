// EchoSense Mobile App - Core Logic (Single Kotlin File)
// This file simulates the core components of an Android Studio project.
// In a real project, this logic would be split into:
// 1. MainActivity.kt
// 2. AudioMonitorService.kt (Foreground Service)
// 3. EmergencyAlertActivity.kt (Fullscreen Popup)
// 4. AndroidManifest.xml (For permissions and service declaration)
// 5. build.gradle (For dependencies: Gemini SDK, Firebase, Coroutines)

package com.echosense.app

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.telephony.SmsManager
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

// --- 1. Constants and Permissions ---
const val TAG = "EchoSense"
const val NOTIFICATION_CHANNEL_ID = "EchoSense_Monitor_Channel"
const val NOTIFICATION_ID = 101

// Required Runtime Permissions for the App
val REQUIRED_PERMISSIONS = arrayOf(
    android.Manifest.permission.RECORD_AUDIO,
    android.Manifest.permission.ACCESS_FINE_LOCATION,
    android.Manifest.permission.SEND_SMS,
    android.Manifest.permission.VIBRATE,
    // Add BLUETOOTH_CONNECT for Android 12+ if connecting to a specific device
)

// --- 2. Emergency Alert Activity (The Full-Screen Popup) ---

/**
 * Simulates a high-priority, full-screen alert activity that overrides the lock screen.
 * In a real app, this would be a separate file named EmergencyAlertActivity.kt
 */
class EmergencyAlertActivity : Activity() {
    private var tts: TextToSpeech? = null

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        // Ensure this activity pops up even when the device is locked/keyguard is secure
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        // Simple UI/Layout setup (in a real app this is defined in activity_emergency_alert.xml)
        val dangerType = intent.getStringExtra("DANGER_TYPE") ?: "Distress Detected"

        val textView = android.widget.TextView(this).apply {
            text = "!!! EMERGENCY ALERT !!!\n$dangerType\n\nTap to Dismiss"
            textSize = 32f
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.RED)
            gravity = android.view.Gravity.CENTER
            setOnClickListener {
                stopTextToSpeech() // Stop TTS before finishing
                finish()
            }
        }
        setContentView(textView)

        // Text-to-Speech for visually impaired users
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.speak(
                    "Emergency! $dangerType has been detected. Act quickly!",
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    null
                )
            }
        }

        // Stop the siren and haptics in the service when this activity is dismissed
        // This would typically be sent via LocalBroadcastManager or a bound service call
        // For simplicity, we just log a message here.
        Log.d(TAG, "EmergencyAlertActivity launched. User must now handle the situation.")
    }

    private fun stopTextToSpeech() {
        tts?.stop()
        tts?.shutdown()
    }

    override fun onDestroy() {
        stopTextToSpeech()
        super.onDestroy()
    }
}


// --- 3. 24/7 Monitoring Foreground Service ---

/**
 * The core service that runs 24/7 for audio input, location monitoring, and emergency triggering.
 */
class AudioMonitorService : Service() {
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private var audioRecord: AudioRecord? = null
    private lateinit var audioManager: AudioManager
    private lateinit var vibrator: Vibrator
    private lateinit var locationClient: FusedLocationProviderClient
    private var isListening = false
    private var originalAlarmVolume = 0 // Used to restore volume after siren stops
    private var mediaPlayer: MediaPlayer? = null // For the siren sound

    // Dummy user/auth data (In a real app, this comes from Google Sign-In and Firestore)
    private var geminiAuthToken: String? = null
    private var sosContactNumber: String = "1234567890" // Prototype SOS number

    // Required for the Gemini Live API connection (using WebSocket)
    // NOTE: A real implementation requires a WebSocket client library and the Gemini Live API protocol.
    private fun connectToGeminiLiveApi() {
        Log.i(TAG, "Attempting to establish real-time WebSocket connection to Gemini Live API...")
        if (geminiAuthToken.isNullOrEmpty()) {
            Log.e(TAG, "Gemini API key is missing. Re-attempting authentication.")
            return
        }
        isListening = true // Assume success for this prototype

        // This is where you would initialize and open the WebSocket connection,
        // passing the Gemini token and defining the 'System Instruction' for
        // distress detection (e.g., "Analyze audio for screams, car crash sounds, breaking glass,
        // or verbal distress calls, and return structured JSON { 'P_Score': 1-10, 'Danger_Type': '...' }").
        startAudioStreaming()
    }

    private fun startAudioStreaming() {
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Audio recording permission denied. Cannot start listening.")
            stopSelf()
            return
        }

        val sampleRate = 16000 // 16kHz is standard for speech processing
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )

        audioRecord?.startRecording()
        Log.i(TAG, "Audio recording started successfully. Buffer size: $bufferSize bytes.")

        serviceScope.launch {
            val audioBuffer = ByteArray(bufferSize)
            while (isListening) {
                val bytesRead = audioRecord?.read(audioBuffer, 0, bufferSize) ?: 0
                if (bytesRead > 0) {
                    // Send this chunk of audio data to the Gemini Live API via WebSocket
                    sendAudioChunkToGemini(audioBuffer, bytesRead)
                }
            }
        }
    }

    /**
     * Placeholder function for streaming audio to Gemini.
     * In a real app, this uses the established WebSocket.
     */
    private fun sendAudioChunkToGemini(data: ByteArray, length: Int) {
        // Log.v(TAG, "Sending $length bytes to Gemini for analysis.")

        // *** MOCK AI RESPONSE FOR PROTOTYPE TESTING ***
        // Simulate an emergency detection every ~30 seconds of run time
        if (Random.nextInt(1000) == 1) { // 1 in 1000 chance per loop iteration
            val mockDangerType = listOf("Car Crash", "Screams of Distress", "Fire Alarm").random()
            Log.w(TAG, "MOCK AI DETECTION: $mockDangerType. Initiating Emergency Protocol.")
            initiateEmergencyProtocol(mockDangerType)
        }
    }

    // --- EMERGENCY PROTOCOL IMPLEMENTATION (The 4 Actions) ---

    private fun initiateEmergencyProtocol(dangerType: String) {
        serviceScope.launch(Dispatchers.Main) {
            Log.e(TAG, "!!! EMERGENCY PROTOCOL STARTED for: $dangerType !!!")

            // (4) Strong Vibrations
            startStrongHaptics()

            // (1) Overrides Silent Mode and Siren Sound
            playSiren()

            // (2) Makes a Full-Screen Pop-up
            showFullscreenAlert(dangerType)

            // (3) SOS Signalling to Authorities and Server
            getCurrentLocation { location ->
                sendSOSAndServerSignal(dangerType, location)
            }

            // Allow the alert to run for a few seconds before stopping haptics/sound
            delay(5000)
            // Note: The user dismisses the full-screen alert to stop the sound/haptics in a full implementation.
        }
    }

    private fun playSiren() {
        // Check for DND access (Required for full override on modern Android versions)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !audioManager.isNotificationPolicyAccessGranted) {
            Log.w(TAG, "Missing DND access. Cannot fully override silent mode.")
            // Prompt user: Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        }

        // Alarm stream overrides most DND/Silent settings
        originalAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)

        // Temporarily set alarm stream to max volume
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0)

        // Use a dummy sound for prototype (R.raw.emergency_siren)
        // In a real app, the siren sound file needs to be in res/raw/
        mediaPlayer = MediaPlayer.create(this, Settings.System.DEFAULT_RINGTONE_URI).apply {
            setAudioStreamType(AudioManager.STREAM_ALARM)
            isLooping = true
            start()
            Log.i(TAG, "Siren started on STREAM_ALARM (Volume: $maxVolume).")
        }
    }

    private fun startStrongHaptics() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Escalating, powerful haptic pattern (short-short-LONG-repeat)
            val timings = longArrayOf(0, 100, 50, 100, 50, 500)
            val amplitudes = intArrayOf(0, 128, 0, 192, 0, 255) // Max amplitude is 255

            if (vibrator.hasAmplitudeControl()) {
                val effect = VibrationEffect.createWaveform(timings, amplitudes, 3) // Repeat the last pulse
                vibrator.vibrate(effect)
                Log.i(TAG, "Strong haptic waveform started.")
            } else {
                vibrator.vibrate(2000) // Fallback for basic devices
            }
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(2000) // Deprecated call for API < 26
        }
    }

    private fun showFullscreenAlert(dangerType: String) {
        val intent = Intent(this, EmergencyAlertActivity::class.java).apply {
            putExtra("DANGER_TYPE", dangerType)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) // Must be called from a service
        }
        startActivity(intent)
        Log.i(TAG, "Full-screen Emergency Alert activity launched.")
    }

    private fun sendSOSAndServerSignal(dangerType: String, location: Location?) {
        val locationInfo = if (location != null)
            "Lat: ${location.latitude}, Lon: ${location.longitude}"
        else
            "Location unknown."

        val message = "EMERGENCY: Detected $dangerType. Current location: $locationInfo"

        // 1. Prototype SOS (SMS)
        try {
            // Requires SEND_SMS permission
            SmsManager.getDefault().sendTextMessage(sosContactNumber, null, message, null, null)
            Log.w(TAG, "SMS SOS signal sent to $sosContactNumber.")
        } catch (e: Exception) {
            Log.e(TAG, "SMS failure: ${e.message}. Check SEND_SMS permission.")
        }

        // 2. Server Distress Signal (Over Mobile Data/WiFi)
        val distressData = mapOf(
            "userId" to Random.nextLong(10000).toString(), // Mock User ID
            "dangerType" to dangerType,
            "location" to locationInfo,
            "timestamp" to System.currentTimeMillis()
        )

        // This simulates the data being sent. The server side must handle
        // the dynamic radius calculation (e.g., 50km for Earthquake, 10m for Car Crash).
        Log.e(TAG, "Distress Signal sent to server: $distressData")
    }

    // --- LOCATION UTILITIES ---

    /**
     * Overrides user choice by forcing a location update during an emergency.
     * Uses FusedLocationProviderClient.
     */
    private fun getCurrentLocation(callback: (Location?) -> Unit) {
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // Location permission is denied. Cannot override choice.
            Log.e(TAG, "Location permission denied. Cannot get accurate location.")
            callback(null)
            return
        }

        // This is a single, last known location fetch for speed in an emergency.
        locationClient.lastLocation.addOnSuccessListener { location: Location? ->
            if (location != null) {
                Log.d(TAG, "Location successfully retrieved: ${location.latitude}, ${location.longitude}")
                callback(location)
            } else {
                Log.w(TAG, "Last known location is null. Trying for a quick update...")
                // In a real app, this would trigger a LocationRequest for a rapid, short-term update
                callback(null)
            }
        }.addOnFailureListener {
            Log.e(TAG, "Failed to get location: ${it.message}")
            callback(null)
        }
    }

    // --- SERVICE LIFECYCLE ---

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "EchoSense Monitoring"
            val descriptionText = "24/7 Audio Intake and Safety Monitoring."
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent: PendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("EchoSense is Active")
            .setContentText("Listening for distress using Gemini AI...")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        locationClient = LocationServices.getFusedLocationProviderClient(this)
        Log.d(TAG, "AudioMonitorService created.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()

        // Determine service types (Android 14+ requirement)
        val fgsTypeMicrophone = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }

        // Start the service in the foreground
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, fgsTypeMicrophone)
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }

        // In a real app, you would fetch the Gemini token here after checking user auth.
        // For prototype, we set a mock token and connect.
        geminiAuthToken = "MOCK_GEMINI_API_KEY_FROM_USER_AUTH"
        connectToGeminiLiveApi()

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isListening = false
        audioRecord?.stop()
        audioRecord?.release()
        serviceJob.cancel()
        vibrator.cancel()
        mediaPlayer?.stop()
        mediaPlayer?.release()

        // Restore volume
        if (this::audioManager.isInitialized) {
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, originalAlarmVolume, 0)
        }

        Log.d(TAG, "AudioMonitorService destroyed. Monitoring stopped.")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}


// --- 4. Main Activity (UI and Permission Handler) ---

class MainActivity : android.app.Activity() {
    private val REQUEST_CODE_PERMISSIONS = 100
    private lateinit var statusTextView: android.widget.TextView
    private lateinit var toggleButton: android.widget.Button

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)

        // Simple Vertical Layout for UI
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(40, 40, 40, 40)
        }

        val titleTextView = android.widget.TextView(this).apply {
            text = "EchoSense: AI Safety Monitor"
            textSize = 28f
            setPadding(0, 0, 0, 80)
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        }
        layout.addView(titleTextView)

        statusTextView = android.widget.TextView(this).apply {
            text = "Service Status: Idle"
            textSize = 18f
            setPadding(0, 0, 0, 40)
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        }
        layout.addView(statusTextView)

        toggleButton = android.widget.Button(this).apply {
            text = "START MONITORING"
            setOnClickListener { toggleMonitoring() }
        }
        layout.addView(toggleButton)

        setContentView(layout)

        checkAndRequestPermissions()
        // Ensure user is signed in (Google/Firebase Auth) to get the Gemini API token
        if (!isUserAuthenticated()) {
            startGoogleSignInFlow()
        }
    }

    // --- Authentication Placeholder ---
    private fun isUserAuthenticated(): Boolean {
        // In a real app, check Firebase Auth state
        return true // Assume authenticated for prototype
    }

    private fun startGoogleSignInFlow() {
        // This function initiates Google Sign-In and uses the resulting ID token
        // to authenticate with Firebase and fetch the Gemini API token securely.
        Log.w(TAG, "Start Google Sign-In Flow to get Gemini API access.")
    }
    // ----------------------------------

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), REQUEST_CODE_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(this, "All critical permissions granted.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Critical permissions required for 24/7 monitoring were denied.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun isServiceRunning(): Boolean {
        // A proper check would iterate through running services, but for simplicity:
        return toggleButton.text == "STOP MONITORING"
    }

    private fun toggleMonitoring() {
        val intent = Intent(this, AudioMonitorService::class.java)

        if (isServiceRunning()) {
            stopService(intent)
            statusTextView.text = "Service Status: Idle"
            toggleButton.text = "START MONITORING"
            toggleButton.setBackgroundColor(android.graphics.Color.GREEN)
        } else {
            // Check essential permissions before starting
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Please grant Microphone and Location permissions first.", Toast.LENGTH_LONG).show()
                checkAndRequestPermissions()
                return
            }

            // Start the Foreground Service (Must use startForegroundService since API 26)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }

            statusTextView.text = "Service Status: Running (24/7)"
            toggleButton.text = "STOP MONITORING"
            toggleButton.setBackgroundColor(android.graphics.Color.RED)
            Toast.makeText(this, "EchoSense Service Started.", Toast.LENGTH_SHORT).show()
        }
    }
}
