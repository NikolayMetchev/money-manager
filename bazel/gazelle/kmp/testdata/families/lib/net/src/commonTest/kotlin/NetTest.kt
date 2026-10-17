package com.example.net

import com.example.fixture.fakeClient
import kotlin.test.Test

class NetTest {
    @Test
    fun builds() {
        ids(fakeClient())
    }
}
