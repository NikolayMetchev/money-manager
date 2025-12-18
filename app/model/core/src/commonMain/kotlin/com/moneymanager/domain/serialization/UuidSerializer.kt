@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.domain.serialization

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.SerializersModule
import kotlin.uuid.Uuid

object UuidSerializer : KSerializer<Uuid> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Uuid", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: Uuid,
    ) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): Uuid = Uuid.parse(decoder.decodeString())
}

val UuidSerializersModule =
    SerializersModule {
        contextual(Uuid::class, UuidSerializer)
    }
