package com.moneymanager.regex

actual typealias PortableRegex = Regex

actual fun PortableRegex.firstMatch(input: CharSequence): MatchResult? = find(input, 0)
