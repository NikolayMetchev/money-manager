/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

// Kotlin's own regex engine, adapted as little as possible (see README.md), so it is not linted.
@file:Suppress("ktlint", "detekt:all")

package com.moneymanager.regex.engine

internal class NamedGroup(val name: String) : SpecialToken() {

    override fun toString() = "NamedGroup(name=$name)"

    override val type: Type = SpecialToken.Type.NAMED_GROUP
}