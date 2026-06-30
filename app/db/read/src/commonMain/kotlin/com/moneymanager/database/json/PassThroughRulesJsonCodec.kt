package com.moneymanager.database.json

import com.moneymanager.domain.model.passthrough.PassThroughRule
import kotlinx.serialization.json.Json

/** Codec for the `rules_json` column of `pass_through_account` (a list of [PassThroughRule]). */
object PassThroughRulesJsonCodec {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    fun encode(rules: List<PassThroughRule>): String = json.encodeToString(rules)

    fun decode(jsonString: String): List<PassThroughRule> = json.decodeFromString(jsonString)
}
