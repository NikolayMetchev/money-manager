package com.moneymanager.domain.port

import com.moneymanager.domain.model.SourceRecorder
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.JsonPath
import com.moneymanager.domain.model.csv.CsvImportId

interface TransferSourcePort {
    fun manualRecorder(): SourceRecorder

    fun sampleGeneratorRecorder(): SourceRecorder

    fun csvImportRecorder(
        csvImportId: CsvImportId,
        rowIndexForTransfer: (TransferId) -> Long,
    ): SourceRecorder

    fun apiImportRecorder(
        sessionId: ApiSessionId,
        requestId: ApiRequestId,
        jsonPath: JsonPath,
    ): SourceRecorder
}
