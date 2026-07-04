package com.moneymanager.ui.navigation

import androidx.navigation3.runtime.NavKey
import androidx.savedstate.serialization.SavedStateConfiguration
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclassesOfSealed

/**
 * Saved-state serialization for the navigation back stack: `rememberNavBackStack` serializes the
 * stack polymorphically through [NavKey], so every [Screen] subtype must be registered.
 * `subclassesOfSealed` picks up new subtypes automatically.
 */
@OptIn(ExperimentalSerializationApi::class)
val ScreenSavedStateConfiguration: SavedStateConfiguration =
    SavedStateConfiguration {
        serializersModule =
            SerializersModule {
                polymorphic(NavKey::class) {
                    subclassesOfSealed<Screen>()
                }
            }
    }
