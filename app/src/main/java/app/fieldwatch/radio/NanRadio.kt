package app.fieldwatch.radio

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareSession
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import app.fieldwatch.domain.NanRemoteId
import app.fieldwatch.domain.Observation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Antidron: Remote ID over Wi-Fi NAN (Wi-Fi Aware). Subscribes to the ASTM service and
 * hands each frame to [onObservation] as a Wi-Fi Remote ID observation.
 *
 * Only phones with Wi-Fi Aware hardware can do this. Wi-Fi Aware may pause while the
 * phone runs a hotspot or Wi-Fi Direct; the state receiver re-attaches when it returns.
 */
class NanRadio(
    context: Context,
    private val onObservation: (Observation) -> Unit,
) {
    enum class State { OFF, UNSUPPORTED, UNAVAILABLE, STARTING, RUNNING, FAILED }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val manager: WifiAwareManager? =
        if (app.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)) {
            app.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager
        } else {
            null
        }

    @Volatile private var wanted = false
    private var session: WifiAwareSession? = null
    private var subscribe: SubscribeDiscoverySession? = null
    private var receiverOn = false

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!wanted) return
            if (manager?.isAvailable == true) {
                attach()
            } else {
                closeSession()
                set(State.UNAVAILABLE)
            }
        }
    }

    fun start() {
        main.post {
            if (wanted) return@post
            wanted = true
            val mgr = manager
            if (mgr == null) {
                set(State.UNSUPPORTED)
                return@post
            }
            if (!receiverOn) {
                val filter = IntentFilter(WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED)
                ContextCompat.registerReceiver(app, stateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
                receiverOn = true
            }
            if (mgr.isAvailable) attach() else set(State.UNAVAILABLE)
        }
    }

    fun stop() {
        main.post {
            wanted = false
            if (receiverOn) {
                runCatching { app.unregisterReceiver(stateReceiver) }
                receiverOn = false
            }
            closeSession()
            set(if (manager == null) State.UNSUPPORTED else State.OFF)
        }
    }

    @SuppressLint("MissingPermission")
    private fun attach() {
        val mgr = manager ?: return
        if (session != null) return
        set(State.STARTING)
        try {
            mgr.attach(object : AttachCallback() {
                override fun onAttached(s: WifiAwareSession) {
                    if (!wanted) {
                        s.close()
                        return
                    }
                    session = s
                    subscribe(s)
                }

                override fun onAttachFailed() {
                    session = null
                    set(State.FAILED)
                }
            }, main)
        } catch (e: SecurityException) {
            Log.w(TAG, "attach refused", e)
            set(State.FAILED)
        }
    }

    @SuppressLint("MissingPermission")
    private fun subscribe(s: WifiAwareSession) {
        val config = SubscribeConfig.Builder()
            .setServiceName(NanRemoteId.SERVICE_NAME)
            .build()
        try {
            s.subscribe(config, object : DiscoverySessionCallback() {
                override fun onSubscribeStarted(d: SubscribeDiscoverySession) {
                    subscribe = d
                    set(State.RUNNING)
                }

                override fun onServiceDiscovered(
                    peer: PeerHandle,
                    serviceSpecificInfo: ByteArray?,
                    matchFilter: List<ByteArray>?,
                ) {
                    val info = serviceSpecificInfo ?: return
                    val obs = NanRemoteId.observation(info, peer.hashCode(), System.currentTimeMillis()) ?: return
                    runCatching { onObservation(obs) }.onFailure { Log.e(TAG, "observation failed", it) }
                }

                override fun onSessionTerminated() {
                    subscribe = null
                    if (wanted) set(State.FAILED)
                }
            }, main)
        } catch (e: SecurityException) {
            Log.w(TAG, "subscribe refused", e)
            set(State.FAILED)
        }
    }

    private fun closeSession() {
        runCatching { subscribe?.close() }
        subscribe = null
        runCatching { session?.close() }
        session = null
    }

    private fun set(s: State) {
        _state.value = s
    }

    companion object {
        private const val TAG = "AntidronNan"
        private val _state = MutableStateFlow(State.OFF)

        /** Read by the settings screen. One NAN radio per process, owned by [ScanService]. */
        val state: StateFlow<State> = _state.asStateFlow()

        fun supported(context: Context): Boolean =
            Build.VERSION.SDK_INT >= 26 &&
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)
    }
}
