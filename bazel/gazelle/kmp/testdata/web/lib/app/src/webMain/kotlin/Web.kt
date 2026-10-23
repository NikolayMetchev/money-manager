package com.example.app

import io.ktor.client.engine.js.Js
import kotlinx.browser.window
import org.jetbrains.skiko.SkiaLayer
import org.w3c.dom.Element

fun engine() = Js.also { window.location }

fun layer(element: Element) = SkiaLayer()
