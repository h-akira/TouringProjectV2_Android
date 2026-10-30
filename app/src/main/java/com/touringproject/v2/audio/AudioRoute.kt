package com.touringproject.v2.audio

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

// Routes the intercom microphone (Bluetooth SCO).
//
// Intercom button: the headset sends "start voice recognition" (AT+BVRA) and waits about 5 s for the phone.
// We answer with BluetoothHeadset.startVoiceRecognition(), which makes the Bluetooth stack bring up SCO itself.
// setCommunicationDevice() must NOT be called then: AudioService would remember the external SCO and re-open it
// as a virtual call after the headset drops it. The second press reaches us only as SCO going down, so a remote
// SCO loss during a session is reported as onVoiceRecognitionEnded. Another HFP device dropping is ignored.
//
// Screen button: there is no request from the headset, so SCO is opened as a virtual call by naming the SCO
// *output* (sink) in setCommunicationDevice(); the return value is checked because a false is otherwise silent.
//
// A setup (startVoiceRecognition, acquireSco) that is cancelled is undone on the main thread before the call returns:
// whatever it had begun or brought up is let go, and nothing it left waiting (a listener, a timeout, a headset
// callback) acts later on the route of whoever holds it by then (Android R1-03).
//
// All Bluetooth and AudioManager work runs on the main thread.
@SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is requested at runtime by the app
class AudioRoute(private val context: Context) {
  private val audioManager = context.getSystemService(AudioManager::class.java)
  private val main = Handler(Looper.getMainLooper())

  /** Called on the main thread when the intercom drops SCO during a voice-recognition session (the second press). */
  @Volatile var onVoiceRecognitionEnded: (() -> Unit)? = null

  private var headset: BluetoothHeadset? = null
  private var proxyRequested = false
  private val proxyWaiters = mutableListOf<(BluetoothHeadset?) -> Unit>()
  private var receiver: BroadcastReceiver? = null

  // Voice-recognition session; the counter invalidates callbacks of earlier sessions.
  private var vrDevice: BluetoothDevice? = null
  private var vrSession = 0
  private var vrPending: ((Boolean, String) -> Unit)? = null

  suspend fun startVoiceRecognition(timeoutMs: Long): RouteResult = onMain { done -> startVoiceRecognition(timeoutMs, done) }

  suspend fun stopVoiceRecognition(reason: String) = withContext(Dispatchers.Main) {
    vrPending?.invoke(false, "stopped: $reason")
    headset?.let { endSession(it, reason) }
    audioManager.mode = AudioManager.MODE_NORMAL
  }

  suspend fun acquireSco(timeoutMs: Long): RouteResult = onMain { done -> acquireSco(timeoutMs, done) }

  suspend fun releaseSco() = withContext(Dispatchers.Main) { release() }

  private fun release() {
    runCatching { audioManager.clearCommunicationDevice() }
    audioManager.mode = AudioManager.MODE_NORMAL
  }

  // Answers and cues play on the media stream; the intercom follows the phone's media volume. While media still
  // routes to the intercom's call link (SCO, which lingers after an ask), STREAM_MUSIC reports that link's volume
  // instead, so the reading is withheld (null).
  fun mediaVolume(): MediaVolume? {
    if (mediaOnCallRoute()) return null
    return MediaVolume(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC), audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
  }

  /** The microphone this app is recording from right now (only our own recordings are visible to us). */
  fun activeInputDevice(): InputDevice? {
    val device = audioManager.activeRecordingConfigurations.firstNotNullOfOrNull { it.audioDevice } ?: return null
    val type = when (device.type) {
      AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> InputDevice.Type.BLUETOOTH
      AudioDeviceInfo.TYPE_BUILTIN_MIC -> InputDevice.Type.BUILTIN
      else -> InputDevice.Type.OTHER
    }
    return InputDevice(type, device.productName.toString())
  }

  /** block starts a setup and gives back what undoes it, however far it has come. */
  private suspend fun onMain(block: (done: (Boolean, String) -> Unit) -> (() -> Unit)): RouteResult = withContext(Dispatchers.Main) {
    var undo: () -> Unit = {}
    try {
      suspendCancellableCoroutine { cont ->
        var resumed = false
        undo = block { ok, note ->
          if (!resumed) {
            resumed = true
            cont.resume(RouteResult(ok, note))
          }
        }
      }
    } catch (e: CancellationException) {
      undo()
      throw e
    }
  }

  /** One startVoiceRecognition: a headset callback that comes after the cancel does nothing. */
  private class VrAttempt {
    var cancelled = false
    var session = 0
  }

  private fun mediaOnCallRoute(): Boolean {
    val am = audioManager
    if (am.mode == AudioManager.MODE_IN_COMMUNICATION || am.mode == AudioManager.MODE_IN_CALL) return true
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
    val media = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
    return am.getAudioDevicesForAttributes(media).any { it.type == SCO_TYPE }
  }

  // ---------------- intercom button: voice recognition ----------------

  private fun startVoiceRecognition(timeoutMs: Long, done: (Boolean, String) -> Unit): () -> Unit {
    if (vrPending != null) {
      done(false, "already starting")
      return {}
    }
    val attempt = VrAttempt()
    ensureReceiver()
    withHeadset { h ->
      if (attempt.cancelled) return@withHeadset
      if (h == null) {
        done(false, "no headset proxy")
        return@withHeadset
      }
      val device = try {
        pickDevice(h)
      } catch (e: SecurityException) {
        done(false, "no Bluetooth permission")
        return@withHeadset
      }
      if (device == null) {
        done(false, "no HFP device connected")
        return@withHeadset
      }
      if (vrDevice != null) endSession(h, "superseded")
      waitAudioOff(h, device, attempt) { begin(h, device, timeoutMs, done, attempt) }
    }
    return {
      attempt.cancelled = true
      // Only a setup that had begun owns the session; one still waiting for the headset has brought nothing up.
      if (attempt.session != 0 && attempt.session == vrSession) {
        vrPending?.invoke(false, "cancelled")
        headset?.let { endSession(it, "cancelled") }
        audioManager.mode = AudioManager.MODE_NORMAL
      }
    }
  }

  private fun begin(h: BluetoothHeadset, device: BluetoothDevice, timeoutMs: Long, done: (Boolean, String) -> Unit, attempt: VrAttempt) {
    val session = ++vrSession
    attempt.session = session
    vrDevice = device
    val onTimeout = Runnable { if (vrSession == session) vrPending?.invoke(false, "SCO timeout") }
    // Wait for SCO by broadcast and by polling, whichever comes first.
    val poll = object : Runnable {
      override fun run() {
        if (vrSession != session || vrPending == null) return
        val d = vrDevice ?: return
        if (runCatching { h.isAudioConnected(d) }.getOrDefault(false)) vrPending?.invoke(true, "connected (poll)")
        else main.postDelayed(this, POLL_MS)
      }
    }
    vrPending = { ok, note ->
      vrPending = null
      main.removeCallbacks(onTimeout)
      main.removeCallbacks(poll)
      if (ok && vrSession == session) {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        startSessionMonitor(h, session)
      } else if (vrSession == session) {
        endSession(h, "start failed: $note")
      }
      done(ok && vrSession == session, note)
    }
    main.postDelayed(onTimeout, timeoutMs)
    val accepted = runCatching { h.startVoiceRecognition(device) }
    if (accepted.getOrDefault(false) != true) {
      vrPending?.invoke(false, "startVoiceRecognition returned ${accepted.exceptionOrNull()?.javaClass?.simpleName ?: "false"}")
      return
    }
    main.post(poll)
  }

  private fun startSessionMonitor(h: BluetoothHeadset, session: Int) {
    val monitor = object : Runnable {
      override fun run() {
        if (vrSession != session) return
        val d = vrDevice ?: return
        if (!runCatching { h.isAudioConnected(d) }.getOrDefault(false)) onScoLost(session)
        else main.postDelayed(this, POLL_MS)
      }
    }
    main.postDelayed(monitor, POLL_MS)
  }

  // SCO went down during a session we did not end ourselves: the intercom's second press.
  private fun onScoLost(session: Int) {
    if (vrSession != session || vrDevice == null) return
    vrDevice = null
    vrSession++
    runCatching { audioManager.mode = AudioManager.MODE_NORMAL }
    onVoiceRecognitionEnded?.invoke()
  }

  private fun endSession(h: BluetoothHeadset, reason: String) {
    val device = vrDevice
    vrSession++
    vrDevice = null
    if (device != null) {
      val r = runCatching { h.stopVoiceRecognition(device) }
      Log.i(TAG, "stopVoiceRecognition -> ${r.getOrNull()} ($reason)")
    }
  }

  private fun waitAudioOff(h: BluetoothHeadset, device: BluetoothDevice, attempt: VrAttempt, then: () -> Unit) {
    val deadline = System.currentTimeMillis() + AUDIO_OFF_WAIT_MS
    val check = object : Runnable {
      override fun run() {
        if (attempt.cancelled) return
        val on = runCatching { h.isAudioConnected(device) }.getOrDefault(false)
        if (!on || System.currentTimeMillis() >= deadline) then() else main.postDelayed(this, POLL_MS)
      }
    }
    check.run()
  }

  // Prefer the HFP device that is the current SCO sink when several are connected.
  private fun pickDevice(h: BluetoothHeadset): BluetoothDevice? {
    val devices = h.connectedDevices
    val activeAddress = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      audioManager.availableCommunicationDevices.firstOrNull { it.type == SCO_TYPE }?.address
    } else null
    return devices.firstOrNull { it.address == activeAddress } ?: devices.firstOrNull()
  }

  private fun withHeadset(block: (BluetoothHeadset?) -> Unit) {
    headset?.let { block(it); return }
    var done = false
    val once: (BluetoothHeadset?) -> Unit = { h -> if (!done) { done = true; block(h) } }
    proxyWaiters.add(once)
    main.postDelayed({ if (!done) { proxyWaiters.remove(once); once(null) } }, PROXY_TIMEOUT_MS)
    if (proxyRequested) return
    proxyRequested = true
    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    val requested = adapter?.getProfileProxy(
      context,
      object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
          main.post {
            headset = proxy as BluetoothHeadset
            val waiters = proxyWaiters.toList()
            proxyWaiters.clear()
            waiters.forEach { it(headset) }
          }
        }

        override fun onServiceDisconnected(profile: Int) {
          main.post {
            headset = null
            proxyRequested = false
          }
        }
      },
      BluetoothProfile.HEADSET,
    ) ?: false
    if (!requested) proxyRequested = false
  }

  private fun ensureReceiver() {
    if (receiver != null) return
    val r = object : BroadcastReceiver() {
      override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED) return
        val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
          @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
        val current = vrDevice ?: return
        if (state == BluetoothHeadset.STATE_AUDIO_CONNECTED && vrPending != null) {
          if (device != null && device != current) vrDevice = device
          vrPending?.invoke(true, "connected (broadcast)")
        } else if (state == BluetoothHeadset.STATE_AUDIO_DISCONNECTED && vrPending == null) {
          if (device != null && device != current) return // another HFP device, not our second press
          onScoLost(vrSession)
        }
      }
    }
    val filter = IntentFilter(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED)
    // EXPORTED: the broadcast comes from the Bluetooth app (another uid); it is a protected broadcast.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      context.registerReceiver(r, filter, Context.RECEIVER_EXPORTED)
    } else {
      context.registerReceiver(r, filter)
    }
    receiver = r
  }

  // ---------------- screen button: virtual call ----------------

  private fun acquireSco(timeoutMs: Long, done: (Boolean, String) -> Unit): () -> Unit {
    // The communication-device API is Android 12+; before that the screen button records from the phone's microphone.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
      done(false, "needs Android 12")
      return {}
    }
    val am = audioManager
    am.communicationDevice?.let { if (it.type == SCO_TYPE) { done(true, "already SCO"); return { release() } } }
    am.mode = AudioManager.MODE_IN_COMMUNICATION
    val sink = am.availableCommunicationDevices.firstOrNull { it.type == SCO_TYPE }
    if (sink == null) {
      am.mode = AudioManager.MODE_NORMAL
      done(false, "no SCO output")
      return {}
    }
    var settled = false
    lateinit var listener: AudioManager.OnCommunicationDeviceChangedListener
    lateinit var onTimeout: Runnable
    fun finish(ok: Boolean, note: String) {
      if (settled) return
      settled = true
      main.removeCallbacks(onTimeout)
      runCatching { am.removeOnCommunicationDeviceChangedListener(listener) }
      if (!ok) runCatching { am.clearCommunicationDevice(); am.mode = AudioManager.MODE_NORMAL }
      done(ok, note)
    }
    listener = AudioManager.OnCommunicationDeviceChangedListener { device -> if (device?.type == SCO_TYPE) finish(true, "connected") }
    // On the main thread like the timeout, so finish() is never entered from two threads at once.
    am.addOnCommunicationDeviceChangedListener(context.mainExecutor, listener)
    onTimeout = Runnable { finish(false, "SCO timeout") }
    main.postDelayed(onTimeout, timeoutMs)
    val accepted = runCatching { am.setCommunicationDevice(sink) }.getOrDefault(false)
    if (!accepted) {
      finish(false, "setCommunicationDevice returned false")
      return {}
    }
    am.communicationDevice?.let { if (it.type == SCO_TYPE) finish(true, "connected (immediate)") }
    // Called off while pending: settle as a failure (clears the route, drops the listener and the timeout). Called off
    // after it connected: the route is up and the caller never got it, so let it go.
    return { if (settled) release() else finish(false, "cancelled") }
  }

  private companion object {
    const val TAG = "trv2.route"
    const val SCO_TYPE = AudioDeviceInfo.TYPE_BLUETOOTH_SCO
    const val PROXY_TIMEOUT_MS = 1_500L
    const val POLL_MS = 100L
    const val AUDIO_OFF_WAIT_MS = 1_000L
  }
}
