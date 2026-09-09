package com.jjw.easygallery.core.data.remote.smb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import timber.log.Timber
import java.util.ArrayDeque

/** mDNS 로 찾은 서비스 하나 — 폼의 "네트워크에서 찾기" 목록 */
data class DiscoveredHost(val name: String, val host: String, val port: Int)

/** LAN 서비스 검색. 구현은 Android `NsdManager`(mDNS/DNS-SD), 테스트는 가짜로 대체 */
fun interface HostDiscovery {
    /** [serviceType] 예 `_smb._tcp.` — 찾는 대로 누적 목록을 방출하고, 수집이 끝나면 검색을 멈춘다 */
    fun discover(serviceType: String): Flow<List<DiscoveredHost>>
}

/**
 * `NsdManager` 래퍼. 발견한 서비스는 하나씩 순서대로 resolve 한다(NsdManager 는 동시 resolve 를 거부한다).
 * `docs/NAS_STORAGE.md` §5 — SMB 는 mDNS 광고가 NAS 마다 달라 "있으면 편의, 없으면 직접 입력" 수준으로 둔다.
 */
class NsdHostDiscovery(context: Context) : HostDiscovery {

    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    @Suppress("DEPRECATION") // resolveService 는 API 34 에서 deprecated 지만 minSdk 가 낮아 그대로 쓴다
    override fun discover(serviceType: String): Flow<List<DiscoveredHost>> = callbackFlow {
        val found = LinkedHashMap<String, DiscoveredHost>()
        val pending = ArrayDeque<NsdServiceInfo>()
        var resolving = false

        fun resolveNext() {
            if (resolving) return
            val next = pending.pollFirst() ?: return
            resolving = true
            nsd.resolveService(
                next,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        Timber.d("nsd resolve failed %s (%d)", serviceInfo.serviceName, errorCode)
                        resolving = false
                        resolveNext()
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val address = serviceInfo.host?.hostAddress
                        if (address != null) {
                            found[serviceInfo.serviceName] =
                                DiscoveredHost(serviceInfo.serviceName, address, serviceInfo.port)
                            trySend(found.values.toList())
                        }
                        resolving = false
                        resolveNext()
                    }
                },
            )
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Timber.w("nsd discovery start failed (%d)", errorCode)
                close()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                pending.addLast(serviceInfo)
                resolveNext()
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                if (found.remove(serviceInfo.serviceName) != null) trySend(found.values.toList())
            }
        }
        nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose { runCatching { nsd.stopServiceDiscovery(listener) } }
    }

    companion object {
        const val SMB_SERVICE = "_smb._tcp."
        const val SFTP_SERVICE = "_sftp-ssh._tcp."
    }
}
