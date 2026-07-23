package com.moneymanager.domain.model

import kotlin.time.Instant

/**
 * The unified read-side provenance of an audit entry, for both entities
 * (account/person/currency/ownership/category/api-strategy) and transfers.
 *
 * Reconstructs the device-free [Source] sealed type (which carries the origin-specific detail such as
 * import ids / row indexes / session+request+jsonPath) from a persisted source row, and attaches the
 * read-only metadata that does not live on [Source]: the source row [id], the [deviceInfo] that wrote
 * it, the join-derived import [fileName] (CSV/QIF only), and [createdAt].
 *
 * For transfer sources [entityType] is [EntityType.TRANSFER] and [entityId] is the transfer id.
 *
 * @property id Unique identifier of the persisted source row.
 * @property entityType The kind of entity this source describes ([EntityType.TRANSFER] for transfers).
 * @property entityId The entity's id (= transfer id for transfer sources).
 * @property revisionId The entity/transfer revision this source was recorded for.
 * @property source The reconstructed origin (Manual/SampleGenerator/System/Merge/Unmerge/Csv/Qif/Api).
 * @property deviceId Database id of the device that recorded the change (e.g. to flag "this device").
 * @property deviceInfo The device that recorded the change (null when device metadata is absent).
 * @property fileName The originating CSV/QIF import file name when known (null otherwise).
 * @property createdAt When this source record was created.
 */
data class SourceRecord(
    val id: Long,
    val entityType: EntityType,
    val entityId: Long,
    val revisionId: Long,
    val source: Source,
    val deviceId: Long,
    val deviceInfo: DeviceInfo?,
    val fileName: String?,
    val createdAt: Instant,
)
