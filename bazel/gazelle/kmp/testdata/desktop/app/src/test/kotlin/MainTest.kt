package com.example.app

import com.example.ui.hello
import kotlin.test.Test
import kotlin.test.assertEquals

class MainTest {
    @Test
    fun usesUi() = assertEquals("hello", hello())
}
