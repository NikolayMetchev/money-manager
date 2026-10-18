package com.example.net

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.getenv

@OptIn(ExperimentalForeignApi::class)
fun home() = getenv("HOME")
