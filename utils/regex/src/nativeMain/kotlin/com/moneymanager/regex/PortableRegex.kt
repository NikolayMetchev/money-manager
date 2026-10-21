package com.moneymanager.regex

actual typealias PortableRegex = Regex

actual fun PortableRegex.find(input: CharSequence): MatchResult? = find(input, 0)
