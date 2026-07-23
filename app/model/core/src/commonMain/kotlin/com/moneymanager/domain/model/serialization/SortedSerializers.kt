package com.moneymanager.domain.model.serialization

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
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
    ) {
        delegate.serialize(encoder, value.sorted())
    }

    override fun deserialize(decoder: Decoder): List<T> = delegate.deserialize(decoder).sorted()
}

/**
 * Like [SortedListSerializer], but for element types with no natural [Comparable] order (or several
 * plausible ones) — the caller supplies the canonical [comparator] instead.
 */
open class SortedByListSerializer<T>(
    elementSerializer: KSerializer<T>,
    private val comparator: Comparator<T>,
) : KSerializer<List<T>> {
    private val delegate = ListSerializer(elementSerializer)
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(
        encoder: Encoder,
        value: List<T>,
    ) {
        delegate.serialize(encoder, value.sortedWith(comparator))
    }

    override fun deserialize(decoder: Decoder): List<T> = delegate.deserialize(decoder).sortedWith(comparator)
}

open class SortedSetSerializer<T : Comparable<T>>(
    elementSerializer: KSerializer<T>,
) : KSerializer<Set<T>> {
    private val delegate = SetSerializer(elementSerializer)
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(
        encoder: Encoder,
        value: Set<T>,
    ) {
        delegate.serialize(encoder, value.toSortedSet())
    }

    override fun deserialize(decoder: Decoder): Set<T> = delegate.deserialize(decoder).toSortedSet()

    private fun Set<T>.toSortedSet(): Set<T> = sorted().toSet()
}

/** A `Set<String>` that serializes (and deserializes) in natural string order. */
object SortedStringSetSerializer : SortedSetSerializer<String>(String.serializer())

/** A `List<String>` that serializes (and deserializes) in natural string order — only for lists whose element order carries no meaning. */
object SortedStringListSerializer : SortedListSerializer<String>(String.serializer())

/**
 * A `Map<K, V>` that serializes (and deserializes) with its entries in ascending key order, so the
 * same data always produces identical bytes regardless of insertion order (e.g. rows typed into a UI
 * editor on different devices). Only for maps whose entry order carries no meaning.
 */
open class SortedMapSerializer<K : Comparable<K>, V>(
    keySerializer: KSerializer<K>,
    valueSerializer: KSerializer<V>,
) : KSerializer<Map<K, V>> {
    private val delegate = MapSerializer(keySerializer, valueSerializer)
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(
        encoder: Encoder,
        value: Map<K, V>,
    ) {
        delegate.serialize(encoder, value.toSortedMap())
    }

    override fun deserialize(decoder: Decoder): Map<K, V> = delegate.deserialize(decoder).toSortedMap()

    private fun Map<K, V>.toSortedMap(): Map<K, V> = entries.sortedBy { it.key }.associate { it.key to it.value }
}

/** A `Map<String, String>` that serializes (and deserializes) in ascending key order. */
object SortedStringToStringMapSerializer : SortedMapSerializer<String, String>(String.serializer(), String.serializer())

/** A `Map<String, Long>` that serializes (and deserializes) in ascending key order. */
object SortedStringToLongMapSerializer : SortedMapSerializer<String, Long>(String.serializer(), Long.serializer())
