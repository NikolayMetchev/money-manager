package com.moneymanager.domain.model.serialization

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Serializers that keep order-insensitive export collections in one canonical (sorted) order on both
 * encode and decode, so the same data always serializes to identical bytes on every device. `commonMain`
 * has no sorted collection types (`java.util.TreeSet`/`TreeMap` are JVM-only, and kotlinx.serialization
 * would deserialize into `LinkedHashSet`/`LinkedHashMap` regardless), so the sorting invariant lives in
 * the serializer instead of the collection type.
 *
 * Only use these where element order carries no meaning — first-match-wins rule lists must keep their
 * declared type's default (insertion-order) serialization, because reordering them is a semantic change.
 */
open class SortedListSerializer<T : Comparable<T>>(
    elementSerializer: KSerializer<T>,
) : KSerializer<List<T>> {
    private val delegate = ListSerializer(elementSerializer)
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(
        encoder: Encoder,
        value: List<T>,
    ) = delegate.serialize(encoder, value.sorted())

    override fun deserialize(decoder: Decoder): List<T> = delegate.deserialize(decoder).sorted()
}

/** A `Set<String>` that serializes (and deserializes) in natural string order. */
object SortedStringSetSerializer : KSerializer<Set<String>> {
    private val delegate = SetSerializer(String.serializer())
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(
        encoder: Encoder,
        value: Set<String>,
    ) = delegate.serialize(encoder, value.sorted().toSet())

    override fun deserialize(decoder: Decoder): Set<String> = delegate.deserialize(decoder).sorted().toSet()
}
