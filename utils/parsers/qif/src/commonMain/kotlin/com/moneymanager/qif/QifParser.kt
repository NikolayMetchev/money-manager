package com.moneymanager.qif

/**
 * Parser for Quicken Interchange Format (QIF) files.
 *
 * QIF is a line-oriented format: a line beginning with `!` is a control/header line that
 * selects the current section (e.g. `!Type:Bank`, `!Account`); every other line begins
 * with a single-letter field code followed by its value; a `^` line terminates the
 * current record.
 *
 * See https://en.wikipedia.org/wiki/Quicken_Interchange_Format.
 */
class QifParser {
    /**
     * Parses QIF [content] into [sections][QifSection] of [records][QifRecord].
     */
    fun parse(
        content: String,
        options: QifParseOptions = QifParseOptions(),
    ): QifParseResult {
        val state = ParseState(options)
        for (rawLine in content.split('\n')) {
            val line = rawLine.removeSuffix("\r")
            when {
                line.isBlank() -> continue
                line.startsWith('!') -> state.startSection(line)
                line.startsWith('^') -> state.endRecord(line)
                else -> state.field(line)
            }
        }
        state.finish()
        return QifParseResult(
            sections = state.sections,
            unsupportedRecordCount = state.sections.sumOf { section -> section.records.count { !it.supported } },
        )
    }

    private class ParseState(
        private val options: QifParseOptions,
    ) {
        val sections = mutableListOf<QifSection>()

        private var sectionType: QifSectionType? = null
        private var sectionAccountName: String? = null
        private var pendingAccountName: String? = null
        private var sectionRecords = mutableListOf<QifRecord>()
        private var recordIndex = 0

        private var builder = RecordBuilder()

        fun startSection(headerLine: String) {
            flushRecord()
            closeSection()
            val type = QifSectionType.fromHeader(headerLine)
            sectionType = type
            sectionAccountName = if (type == QifSectionType.ACCOUNT_LIST) null else pendingAccountName
            sectionRecords = mutableListOf()
        }

        fun field(line: String) {
            builder.add(line, options.trimValues)
        }

        fun endRecord(terminatorLine: String) {
            builder.rawLines.add(terminatorLine)
            flushRecord()
        }

        fun finish() {
            flushRecord()
            closeSection()
        }

        private fun flushRecord() {
            if (builder.isEmpty()) {
                builder = RecordBuilder()
                return
            }
            val type = sectionType ?: QifSectionType.UNKNOWN
            if (type == QifSectionType.ACCOUNT_LIST) {
                // An !Account block names the account that following transaction sections belong to.
                pendingAccountName = builder.fields.accountName ?: pendingAccountName
            }
            sectionRecords.add(
                QifRecord(
                    recordIndex = recordIndex++,
                    sectionType = type,
                    accountName = sectionAccountName,
                    supported = type.isBankingTransaction,
                    rawLines = builder.rawLines.toList(),
                    fields = builder.fields.build(type),
                ),
            )
            builder = RecordBuilder()
        }

        private fun closeSection() {
            val type = sectionType ?: return
            sections.add(QifSection(type = type, accountName = sectionAccountName, records = sectionRecords.toList()))
            sectionType = null
        }
    }

    private class RecordBuilder {
        val rawLines = mutableListOf<String>()
        val fields = FieldBuilder()

        fun isEmpty(): Boolean = rawLines.isEmpty()

        fun add(
            line: String,
            trimValues: Boolean,
        ) {
            rawLines.add(line)
            val code = line[0]
            val value = line.substring(1).let { if (trimValues) it.trim() else it }
            fields.put(code, value)
        }
    }

    private class FieldBuilder {
        var date: String? = null
        var amount: String? = null
        var payee: String? = null
        var memo: String? = null
        var category: String? = null
        var transferAccount: String? = null
        var clearedStatus: String? = null
        var numberField: String? = null
        val address = mutableListOf<String>()
        val splits = mutableListOf<QifSplit>()
        val unknownFields = mutableListOf<Pair<Char, String>>()

        /**
         * Account name from an `!Account` block record. The name is the `N` field, falling
         * back to `P` for tolerance of non-standard exports.
         */
        val accountName: String? get() = numberField ?: payee

        fun put(
            code: Char,
            value: String,
        ) {
            when (code) {
                'D' -> date = value
                'T', 'U' -> if (amount == null || code == 'T') amount = value
                'P' -> payee = value
                'M' -> memo = value
                'L' -> applyCategoryOrTransfer(value)
                // `N` is context-dependent: account name in !Account, action in investments,
                // check/reference number otherwise. Resolved in build().
                'N' -> numberField = value
                'C' -> clearedStatus = value
                'A' -> address.add(value)
                'S' -> splits.add(QifSplit().withCategoryOrTransfer(value))
                'E' -> updateLastSplit { it.copy(memo = value) }
                '$' -> updateLastSplit { it.copy(amount = value) }
                else -> unknownFields.add(code to value)
            }
        }

        fun build(type: QifSectionType): QifFields {
            val isInvestment = type == QifSectionType.INVESTMENT
            return QifFields(
                date = date,
                amount = amount,
                payee = payee,
                memo = memo,
                category = category,
                transferAccount = transferAccount,
                checkNumber = if (isInvestment) null else numberField,
                clearedStatus = clearedStatus,
                investmentAction = if (isInvestment) numberField else null,
                address = address.toList(),
                splits = splits.toList(),
                unknownFields = unknownFields.toList(),
            )
        }

        private fun applyCategoryOrTransfer(value: String) {
            val transfer = bracketedAccount(value)
            if (transfer != null) {
                transferAccount = transfer
            } else {
                category = value
            }
        }

        private fun QifSplit.withCategoryOrTransfer(value: String): QifSplit {
            val transfer = bracketedAccount(value)
            return if (transfer != null) copy(transferAccount = transfer) else copy(category = value)
        }

        private inline fun updateLastSplit(update: (QifSplit) -> QifSplit) {
            if (splits.isEmpty()) {
                splits.add(update(QifSplit()))
            } else {
                splits[splits.lastIndex] = update(splits.last())
            }
        }

        private fun bracketedAccount(value: String): String? {
            if (!value.startsWith('[')) return null
            val close = value.indexOf(']')
            return if (close > 1) value.substring(1, close) else null
        }
    }
}
