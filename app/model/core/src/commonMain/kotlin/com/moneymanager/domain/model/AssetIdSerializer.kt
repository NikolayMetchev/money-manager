package com.moneymanager.domain.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Saves an [AssetId] with its kind (`currency:12`, `crypto:34`), so reading it back gives the same
 * typed id it was written from. [AssetId] is an interface, so it has no serializer of its own;
 * a view that remembers which asset it shows (a filter, a navigation argument) uses this one.
 */
object AssetIdSerializer : KSerializer<AssetId> {
    override val descriptor = PrimitiveSerialDescriptor("com.moneymanager.domain.model.AssetId", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: AssetId,
    ) {
        val kind =
            when (value) {
                is CurrencyId -> CURRENCY
                is CryptoId -> CRYPTO
                else -> throw SerializationException("Cannot save an asset id of ${value::class.simpleName}")
            }
        encoder.encodeString("$kind:${value.id}")
    }

    override fun deserialize(decoder: Decoder): AssetId {
        val text = decoder.decodeString()
        val id = text.substringAfter(':').toLongOrNull() ?: throw SerializationException("Not an asset id: $text")
        return when (text.substringBefore(':')) {
            CURRENCY -> CurrencyId(id)
            CRYPTO -> CryptoId(id)
            else -> throw SerializationException("Not an asset id: $text")
        }
    }

    private const val CURRENCY = "currency"
    private const val CRYPTO = "crypto"
}
