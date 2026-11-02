package com.moneymanager.tools.repo

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FilesTest {
    @Test
    fun `a relative path is resolved against the working directory when Bazel names no workspace`() {
        assertTrue(workspaceFile("MODULE.bazel").path.endsWith("MODULE.bazel"))
        assertEquals("/abs/file", workspaceFile("/abs/file").path)
    }

    @Test
    fun `sha256Hex is the lowercase digest`() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256Hex("abc".toByteArray()))
    }

    @Test
    fun `a file is only rewritten when it changed`() {
        val file = Files.createTempFile("f", ".txt").toFile().also { it.writeText("a") }
        assertFalse(writeIfChanged(file, "a", "a"))
        assertTrue(writeIfChanged(file, "a", "b"))
        assertEquals("b", file.readText())
    }

    @Test
    fun `replaceCounting counts the matches`() {
        val (text, count) = Regex("a").replaceCounting("banana") { "o" }
        assertEquals("bonono", text)
        assertEquals(3, count)
    }

    @Test
    fun `httpGet follows a redirect and returns the body`() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val serving =
            thread {
                repeat(2) {
                    server.accept().use { socket ->
                        val path =
                            socket
                                .getInputStream()
                                .bufferedReader()
                                .readLine()
                                .split(" ")[1]
                        val response =
                            if (path == "/start") {
                                "HTTP/1.1 302 Found\r\nLocation: /final\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                            } else {
                                "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok"
                            }
                        socket.getOutputStream().write(response.toByteArray())
                    }
                }
            }
        try {
            assertContentEquals("ok".toByteArray(), httpGet("http://127.0.0.1:${server.localPort}/start"))
        } finally {
            serving.join()
            server.close()
        }
    }

    @Test
    fun `a missing file is not written`() {
        assertFalse(File("/nonexistent/x").exists())
    }
}
