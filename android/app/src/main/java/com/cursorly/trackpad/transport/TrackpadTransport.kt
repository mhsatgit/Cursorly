package com.cursorly.trackpad.transport

import com.cursorly.trackpad.model.ConnectionStatus
import com.cursorly.trackpad.model.ConnectionTarget
import com.cursorly.trackpad.model.HostStateSync
import kotlinx.coroutines.flow.StateFlow

interface TrackpadTransport {
    val status: StateFlow<ConnectionStatus>
    val rttMs: StateFlow<Int?>
    val remoteState: StateFlow<HostStateSync?>

    fun connect(target: ConnectionTarget)
    fun disconnect()
    fun sendTouch(eventType: Int, pointerId: Int, x: Float, y: Float, timestampSec: Double)
    fun sendControl(jsonPayload: String)
    fun sendExplicitButton(maskBit: Int, pressed: Boolean) {}
}
