package com.jjw.easygallery.core.data.upload

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/** 업로드 제약과 맞물리는 기기 상태. WorkManager 가 보는 것과 같은 조건을 화면에도 보여주기 위해 따로 읽는다. */
data class DeviceConditions(val isUnmetered: Boolean, val isCharging: Boolean)

@Singleton
class DeviceConditionsMonitor @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    fun observe(): Flow<DeviceConditions> =
        combine(unmetered(), charging()) { u, c -> DeviceConditions(u, c) }.distinctUntilChanged()

    private fun unmetered(): Flow<Boolean> = callbackFlow {
        val cm = context.getSystemService<ConnectivityManager>()
        if (cm == null) {
            trySend(true)
            awaitClose { }
            return@callbackFlow
        }
        fun current(): Boolean {
            val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
            }

            override fun onLost(network: Network) = Unit.also { trySend(current()) }
        }
        trySend(current())
        cm.registerDefaultNetworkCallback(callback)
        awaitClose { cm.unregisterNetworkCallback(callback) }
    }

    private fun charging(): Flow<Boolean> = callbackFlow {
        fun current(): Boolean {
            val bm = context.getSystemService<BatteryManager>() ?: return false
            return bm.isCharging
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                trySend(current())
            }
        }
        trySend(current())
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        awaitClose { context.unregisterReceiver(receiver) }
    }
}
