package com.moneymanager.cli

import kotlinx.coroutines.runBlocking

/** Runs `mm` with [args] and returns its exit code; each platform's `main` exits with it. */
internal fun runMm(args: Array<String>): Int = runBlocking { MmCommand().execute(args.asList()) }
