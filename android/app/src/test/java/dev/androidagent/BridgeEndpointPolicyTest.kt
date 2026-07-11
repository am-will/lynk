package dev.androidagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BridgeEndpointPolicyTest {
    @Test
    fun requiresTlsForOrdinaryNetworkEndpoints() {
        assertEquals(
            "wss://bridge.example:8788/phone",
            BridgeEndpointPolicy.normalize("WSS://Bridge.Example:8788")?.url
        )
        assertNull(BridgeEndpointPolicy.normalize("ws://192.168.1.20:8788/phone"))
        assertNull(BridgeEndpointPolicy.normalize("ws://bridge.example:8788/phone"))
    }

    @Test
    fun permitsOnlyBoundedCleartextDevelopmentEndpoints() {
        assertEquals(
            BridgeEndpointSecurity.LocalDevelopment,
            BridgeEndpointPolicy.normalize("ws://127.0.0.1:8788")?.security
        )
        assertNull(BridgeEndpointPolicy.normalize("ws://100.88.12.34:8788/phone"))
        assertEquals(
            BridgeEndpointSecurity.TrustedOverlayDevelopment,
            BridgeEndpointPolicy.normalize("ws://100.88.12.34:8788/phone", allowInsecureTrustedOverlay = true)?.security
        )
        assertNull(BridgeEndpointPolicy.normalize("ws://192.168.1.20:8788/phone", allowInsecureTrustedOverlay = true))
    }
}
