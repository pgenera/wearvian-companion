package org.fivesevenfive.wearvian.companion.wear

import android.content.Context
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.fivesevenfive.wearvian.companion.util.logi
import org.fivesevenfive.wearvian.companion.util.logw
import java.util.UUID

/**
 * Phone side of the HA-key import: pushes the imported key + resolved enrollment to
 * the watch over the Data Layer and awaits the watch's ack. The watch receives it
 * via its manifest-registered `WearImportListenerService` (so the watch app need
 * not be open).
 */
class WearImportClient(private val context: Context) {

    class NoWatchException :
        Exception("No connected watch found. Run this on the phone paired with your watch (Wear OS app installed and the watch connected).")

    suspend fun pushKey(
        privateKeyPemBase64: String,
        publicKeyHex: String,
        userId: String,
        asWatch: Boolean,
        vehicles: List<EnrollmentContract.VehicleResult>,
        timeoutMs: Long = 60_000L,
    ): ImportContract.Ack {
        val messageClient = Wearable.getMessageClient(context)
        val requestId = UUID.randomUUID().toString()
        logi("pushKey: requestId=$requestId vehicles=${vehicles.size} asWatch=$asWatch")
        val deferred = CompletableDeferred<ImportContract.Ack>()

        val listener = MessageClient.OnMessageReceivedListener { event ->
            if (event.path != ImportContract.PATH_RESULT) return@OnMessageReceivedListener
            runCatching { ImportContract.parseAck(event.data) }
                .onSuccess {
                    logi("pushKey: ack status=${it.status} requestId=${it.requestId}")
                    if ((it.requestId == requestId || it.requestId.isEmpty()) && !deferred.isCompleted) {
                        deferred.complete(it)
                    }
                }
                .onFailure { logw("pushKey: failed to parse ack", it) }
        }
        messageClient.addListener(listener).await()
        try {
            val nodes = resolveWatchNodes()
            if (nodes.isEmpty()) throw NoWatchException()
            val payload = ImportContract.buildKey(
                requestId, privateKeyPemBase64, publicKeyHex, userId, asWatch, vehicles,
            )
            nodes.forEach { node ->
                logi("pushKey: sending to node=$node bytes=${payload.size}")
                runCatching { messageClient.sendMessage(node, ImportContract.PATH_KEY, payload).await() }
                    .onFailure { logw("pushKey: send to $node failed", it) }
            }
            return withTimeout(timeoutMs) { deferred.await() }
        } finally {
            messageClient.removeListener(listener)
        }
    }

    /** Prefer the node advertising the watch capability; fall back to all connected nodes
     *  (capability sync can lag right after install). */
    private suspend fun resolveWatchNodes(): List<String> {
        runCatching {
            val info = Wearable.getCapabilityClient(context)
                .getCapability(EnrollmentContract.CAP_WATCH, CapabilityClient.FILTER_REACHABLE).await()
            val ids = info.nodes.mapNotNull { it.id }
            if (ids.isNotEmpty()) {
                logi("pushKey: capability nodes=$ids")
                return ids
            }
        }.onFailure { logw("pushKey: capability lookup failed", it) }
        // connectedNodes throws ApiException(API_UNAVAILABLE) when the Wearable Data Layer isn't
        // available on this phone (no Wear OS companion app / no paired watch). Treat that as "no
        // watch" so the caller shows a clean message instead of a raw GMS stack trace.
        return runCatching { Wearable.getNodeClient(context).connectedNodes.await().map { it.id } }
            .onFailure { logw("pushKey: node lookup failed — Wearable API unavailable on this phone?", it) }
            .getOrDefault(emptyList())
            .also { logi("pushKey: connected nodes=$it") }
    }
}
