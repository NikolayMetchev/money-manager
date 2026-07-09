package com.moneymanager.domain.model.csvstrategy.export

import com.moneymanager.domain.model.csvstrategy.AttributeColumnMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.model.serialization.SortedListSerializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Serializes the field-mappings map with its keys in [TransferField] name order. */
object SortedFieldMappingsSerializer : KSerializer<Map<TransferField, FieldMappingExport>> {
    private val delegate = MapSerializer(TransferField.serializer(), FieldMappingExport.serializer())
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(
        encoder: Encoder,
        value: Map<TransferField, FieldMappingExport>,
    ) = delegate.serialize(encoder, sortedByField(value))

    override fun deserialize(decoder: Decoder): Map<TransferField, FieldMappingExport> = sortedByField(delegate.deserialize(decoder))

    private fun sortedByField(value: Map<TransferField, FieldMappingExport>): Map<TransferField, FieldMappingExport> =
        value.entries.sortedBy { it.key.name }.associate { it.key to it.value }
}

/** Serializes attribute-column mappings sorted by [AttributeColumnMapping]'s natural order. */
object SortedAttributeMappingListSerializer : SortedListSerializer<AttributeColumnMapping>(AttributeColumnMapping.serializer())
