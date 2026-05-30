package org.fivesevenfive.wearvian.companion.wear

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * In-memory bridge between the [WearEnrollmentListenerService] (which receives a
 * watch enrollment request in the background) and the UI (which performs the
 * interactive Rivian login and then completes enrollment). A single pending
 * request is held at a time; this is sufficient for the one-at-a-time enrollment
 * flow.
 */
object PendingEnrollment {
    private val _request = MutableStateFlow<EnrollmentContract.Request?>(null)

    /** The source node id (watch) the result should be sent back to. */
    @Volatile
    var sourceNodeId: String? = null
        private set

    val request: StateFlow<EnrollmentContract.Request?> = _request

    fun submit(request: EnrollmentContract.Request, nodeId: String) {
        sourceNodeId = nodeId
        _request.value = request
    }

    fun clear() {
        _request.value = null
        sourceNodeId = null
    }
}
