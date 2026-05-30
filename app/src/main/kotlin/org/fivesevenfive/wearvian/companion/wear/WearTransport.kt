package org.fivesevenfive.wearvian.companion.wear

import android.content.Context
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

/**
 * Sends the enrollment result back to the watch over the Data Layer. Uses
 * `MessageClient` as the primary transport (see PROTOCOL.md). For payloads that
 * approach the ~100 KB message limit a `DataClient` fallback can be added; the
 * current result payload is well under that.
 */
class WearTransport(private val context: Context) {

    suspend fun sendResult(nodeId: String, payload: ByteArray) {
        Wearable.getMessageClient(context)
            .sendMessage(nodeId, EnrollmentContract.PATH_RESULT, payload)
            .await()
    }
}
