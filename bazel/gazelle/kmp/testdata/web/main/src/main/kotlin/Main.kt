package com.example.main

import com.example.app.names
import kotlinx.browser.document

fun main() {
    document.title = names().toString()
}
