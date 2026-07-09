package com.moneymanager.apiimporter

import kotlin.test.Test
import kotlin.test.assertEquals

class ExchangeNonceTest {
    @Test
    fun `nonce tracks current time yet stays strictly increasing`() {
        // First request: the current epoch-ms.
        assertEquals(1_700_000_000_000L, nextExchangeNonce(lastNonce = 0L, nowMillis = 1_700_000_000_000L))
        // A later request whose clock advanced: the new current time.
        assertEquals(1_700_000_001_500L, nextExchangeNonce(lastNonce = 1_700_000_000_000L, nowMillis = 1_700_000_001_500L))
        // Two requests within the same millisecond: forced strictly increasing.
        assertEquals(1_700_000_000_001L, nextExchangeNonce(lastNonce = 1_700_000_000_000L, nowMillis = 1_700_000_000_000L))
        // A clock that went backwards (NTP adjustment): still strictly increasing.
        assertEquals(1_700_000_000_001L, nextExchangeNonce(lastNonce = 1_700_000_000_000L, nowMillis = 1_699_999_999_000L))
    }
}
