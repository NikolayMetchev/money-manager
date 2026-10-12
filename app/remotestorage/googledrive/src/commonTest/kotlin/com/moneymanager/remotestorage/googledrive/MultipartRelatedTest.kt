package com.moneymanager.remotestorage.googledrive

import kotlin.test.Test
import kotlin.test.assertEquals

class MultipartRelatedTest {
    @Test
    fun buildsACrlfSeparatedMetadataAndMediaBody() {
        val body = multipartRelated("b0undary", """{"name":"db"}""", byteArrayOf(0, 1, 2))

        val expected =
            "--b0undary\r\n" +
                "Content-Type: application/json; charset=UTF-8\r\n" +
                "\r\n" +
                "{\"name\":\"db\"}\r\n" +
                "--b0undary\r\n" +
                "Content-Type: application/octet-stream\r\n" +
                "\r\n" +
                "\u0000\u0001\u0002\r\n" +
                "--b0undary--\r\n"
        assertEquals(expected, body.decodeToString())
    }
}
