package com.moneymanager.session

import com.moneymanager.database.di.AppServices
import com.moneymanager.query.ObservationReads
import com.moneymanager.query.TransactionDetailQuery

/** The transaction detail query over a session's read services, the one every front end uses. */
fun AppServices.transactionDetailQuery(): TransactionDetailQuery =
    TransactionDetailQuery(
        transactionRepository = transactions.transactionRepository,
        tradeRepository = transactions.tradeRepository,
        relationshipRepository = transactions.relationshipRepository,
        sourceRepository = transactions.transferSourceRepository,
        observationReads =
            ObservationReads(
                observationRepository = transactions.observationRepository,
                personOverrideRepository = transactions.personOverrideRepository,
                importRecordRepository = imports.importRecordRepository,
                csvImportRepository = imports.csvImportRepository,
                attributeTypeRepository = transactions.attributeTypeRepository,
                currencyRepository = accounts.currencyRepository,
                cryptoRepository = accounts.cryptoRepository,
            ),
    )
