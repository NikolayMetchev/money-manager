package com.moneymanager.domain.model

/**
 * Implemented by import-time entities so that every auditable/importable entity built in an import
 * batch carries its own provenance [Source]. The import engine reads [source] to record where each
 * created entity came from, removing the need for any out-of-band, order-dependent source bookkeeping.
 */
interface Auditable {
    val source: Source
}
