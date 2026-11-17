package com.moneymanager.test.reimportoracle

/**
 * What a fresh import of each [OracleCorpus] scenario wrote before the engine handed its fee, pass-through and
 * conversion expansion to `ObservationExpander` (#1422), captured from the engine as it was. [ExpansionGoldenTest]
 * holds the expander to it: every transfer, trade, relationship and attribute must come out exactly as before. Since
 * staged files match on import (#1428), two records of one event are one transaction carrying both, where the
 * deduper booked two and excluded one as reconciled; balances are the same.
 */
internal val FRESH_IMPORT_GOLDENS: Map<String, String> =
    mapOf(
        "Monzo personal + joint" to
            """
            == accounts (4)
            Landlord
            Monzo
            Monzo Joint
            Tesco
            == transfers (3)
            2023-11-19T21:15:00Z | Monzo -> Monzo Joint | 1000 GBP | From personal [Monzo Category=Transfers, Monzo Transaction ID=tx_joint_1, Type=Faster payment]
            2023-11-20T08:01:00Z | Monzo -> Tesco | 12.4 GBP | TESCO STORES [Monzo Category=Transfers, Monzo Transaction ID=tx_personal_2, Type=Faster payment]
            2023-11-21T18:30:00Z | Monzo Joint -> Landlord | 900 GBP | RENT [Monzo Category=Transfers, Monzo Transaction ID=tx_joint_2, Type=Faster payment]
            == trades (0)
            == relationships (0)
            == daily balances (6)
            Landlord | 2023-11-21 | GBP | 900
            Monzo Joint | 2023-11-19 | GBP | 1000
            Monzo Joint | 2023-11-21 | GBP | 100
            Monzo | 2023-11-19 | GBP | -1000
            Monzo | 2023-11-20 | GBP | -1012.4
            Tesco | 2023-11-20 | GBP | 12.4
            """.trimIndent(),
        "Freetrade ISA + Monzo (placeholder other side)" to
            """
            == accounts (6)
            Freetrade ISA
            Freetrade Interest
            Freetrade Limited
            Freetrade Top Ups
            Landlord
            Monzo
            == transfers (3)
            2026-02-16T00:00:00Z | Freetrade Interest -> Freetrade ISA | 0.02 GBP | Interest [freetrade-type=INTEREST_FROM_CASH]
            2026-02-28T09:00:00Z | Monzo -> Landlord | 1000 GBP | RENT [Monzo Category=Transfers, Monzo Transaction ID=tx_ft_2, Type=Faster payment]
            2026-03-01T09:00:00Z | Monzo -> Freetrade ISA | 1000 GBP | FREETRADE [Monzo Category=Transfers, Monzo Transaction ID=tx_ft_1, Type=Faster payment, freetrade-type=TOP_UP]
            == trades (1)
            2026-03-02T08:49:09.042Z | Freetrade ISA 998.64 GBP -> Freetrade ISA 202 EXFD | Example Fund
            == relationships (0)
            == daily balances (8)
            Freetrade ISA | 2026-02-16 | GBP | 0.02
            Freetrade ISA | 2026-03-01 | GBP | 1000.02
            Freetrade ISA | 2026-03-02 | EXFD | 202
            Freetrade ISA | 2026-03-02 | GBP | 1.38
            Freetrade Interest | 2026-02-16 | GBP | -0.02
            Landlord | 2026-02-28 | GBP | 1000
            Monzo | 2026-02-28 | GBP | -1000
            Monzo | 2026-03-01 | GBP | -2000
            """.trimIndent(),
        "American Express QIF + Santander QIF" to
            """
            == accounts (4)
            AMERICAN EXPRESS
            PAYMENT RECEIVED - THANK YOU
            SAINSBURY'S
            Santander
            == transfers (5)
            2020-02-04T12:00:00Z | Santander -> AMERICAN EXPRESS | 1239.68 GBP | DIRECT DEBIT PAYMENT TO AMERICAN EXPRESS REF 3746-935125-04002, MANDATE NO 0013 [amex-process-date=02/02/2020, amex-reference=9936180, santander-mandate=0013, santander-reference=3746-935125-04002, santander-transaction-type=DIRECT_DEBIT]
            2020-03-04T12:00:00Z | Santander -> AMERICAN EXPRESS | 738.42 GBP | DIRECT DEBIT PAYMENT TO AMERICAN EXPRESS REF 3746-935125-04002, MANDATE NO 0013 [amex-process-date=04/03/2020, amex-reference=9936181, santander-mandate=0013, santander-reference=3746-935125-04002, santander-transaction-type=DIRECT_DEBIT]
            2020-03-19T12:00:00Z | AMERICAN EXPRESS -> SAINSBURY'S | 16.1 GBP | SAINSBURY'S [amex-merchant-type=GOODS, amex-process-date=19/03/2020, amex-reference=9936189]
            2020-04-02T12:00:00Z | Santander -> AMERICAN EXPRESS | 1574.62 GBP | DIRECT DEBIT PAYMENT TO AMERICAN EXPRESS REF 3746-935125-04002, MANDATE NO 0013 [amex-process-date=02/04/2020, amex-reference=9936182, santander-mandate=0013, santander-reference=3746-935125-04002, santander-transaction-type=DIRECT_DEBIT]
            2020-05-06T12:00:00Z | Santander -> AMERICAN EXPRESS | 1801.13 GBP | DIRECT DEBIT PAYMENT TO AMERICAN EXPRESS REF 3746-935125-04002, MANDATE NO 0013 [amex-process-date=03/05/2020, amex-reference=9936183, santander-mandate=0013, santander-reference=3746-935125-04002, santander-transaction-type=DIRECT_DEBIT]
            == trades (0)
            == relationships (0)
            == daily balances (10)
            AMERICAN EXPRESS | 2020-02-04 | GBP | 1239.68
            AMERICAN EXPRESS | 2020-03-04 | GBP | 1978.1
            AMERICAN EXPRESS | 2020-03-19 | GBP | 1962
            AMERICAN EXPRESS | 2020-04-02 | GBP | 3536.62
            AMERICAN EXPRESS | 2020-05-06 | GBP | 5337.75
            SAINSBURY'S | 2020-03-19 | GBP | 16.1
            Santander | 2020-02-04 | GBP | -1239.68
            Santander | 2020-03-04 | GBP | -1978.1
            Santander | 2020-04-02 | GBP | -3552.72
            Santander | 2020-05-06 | GBP | -5353.85
            """.trimIndent(),
        "Revolut EUR + USD statements (acrossFiles)" to
            """
            == accounts (4)
            ATM
            Deichmann
            Revolut
            Revolut Fees [fee-account-for=Revolut]
            == transfers (3)
            2020-05-30T15:23:24Z | Revolut -> Deichmann | 22.35 EUR | Deichmann [revolut-row-id=Current 2020-05-30 15:23:24 Card Payment Deichmann EUR -22.35 977.65, revolut-type=Card Payment]
            2020-06-02T10:00:00Z | Revolut -> ATM | 50 USD | Cash withdrawal at Atm [revolut-row-id=Current 2020-06-02 10:00:00 ATM Cash withdrawal at Atm USD -50.00 180.00, revolut-type=ATM]
            2020-06-02T10:00:00Z | Revolut -> Revolut Fees | 1.5 USD | Fee
            == trades (1)
            2020-06-01T09:07:19Z | Revolut 200 EUR -> Revolut 230 USD | Exchange EUR to USD
            == relationships (1)
            fee: PRIMARY {2020-06-02T10:00:00Z | Revolut -> ATM | 50 USD | Cash withdrawal at Atm [revolut-row-id=Current 2020-06-02 10:00:00 ATM Cash withdrawal at Atm USD -50.00 180.00, revolut-type=ATM]} & RELATED {2020-06-02T10:00:00Z | Revolut -> Revolut Fees | 1.5 USD | Fee}
            == daily balances (7)
            ATM | 2020-06-02 | USD | 50
            Deichmann | 2020-05-30 | EUR | 22.35
            Revolut Fees | 2020-06-02 | USD | 1.5
            Revolut | 2020-05-30 | EUR | -22.35
            Revolut | 2020-06-01 | EUR | -222.35
            Revolut | 2020-06-01 | USD | 230
            Revolut | 2020-06-02 | USD | 178.5
            """.trimIndent(),
        "Crypto.com card + PayPal CSV (conduit)" to
            """
            == accounts (12)
            Crypto.com Card
            Curve
            Frank Frumento
            PayPal
            PayPal Bank Transfers
            PayPal Card Funding
            Pret-a-Portrait Ltd
            Pretportrai
            UBER PAYMENTS UK LIMITED
            Uber 40293
            Uber Technologies, Inc
            Ubertrip 3
            == transfers (16)
            2025-10-10T06:34:03Z | Frank Frumento -> PayPal | 8 GBP | Frank Frumento [paypal-status=Completed, paypal-transaction-id=9, paypal-type=Mobile Payment]
            2025-10-10T07:32:34Z | PayPal -> PayPal Bank Transfers | 8 GBP | General Withdrawal [paypal-status=Processing, paypal-transaction-id=10, paypal-type=General Withdrawal, unidentified-counterparty=target]
            2025-10-17T08:57:28Z | Crypto.com Card -> PayPal | 17.39 GBP | Paypal *Pretportrai [cryptocom-txn-amount=-17.39, cryptocom-txn-currency=GBP, cryptocom-usd-amount=0.0]
            2025-10-17T08:57:28Z | PayPal -> Pretportrai | 17.39 GBP | Pretportrai [excluded=reconciled]
            2025-10-17T08:57:32Z | PayPal -> Pret-a-Portrait Ltd | 17.39 GBP | Pret-a-Portrait Ltd [paypal-status=Completed, paypal-transaction-id=7, paypal-type=Express Checkout Payment]
            2025-10-17T08:57:32Z | PayPal Card Funding -> PayPal | 17.39 GBP | General Credit Card Deposit [excluded=reconciled, paypal-status=Completed, paypal-transaction-id=8, paypal-type=General Credit Card Deposit, unidentified-counterparty=source]
            2025-10-20T20:15:01Z | PayPal -> Uber Technologies, Inc | 4.04 USD | Uber Technologies, Inc [paypal-status=Completed, paypal-transaction-id=3, paypal-type=Pre-approved Payment Bill User Payment]
            2025-10-20T20:15:01Z | PayPal Card Funding -> PayPal | 3.14 GBP | General Credit Card Deposit [excluded=reconciled, paypal-status=Completed, paypal-transaction-id=4, paypal-type=General Credit Card Deposit, unidentified-counterparty=source]
            2025-10-20T20:15:04Z | Crypto.com Card -> Curve | 3.14 GBP | Crv*Paypal *Uber 40293 [cryptocom-txn-amount=-3.14, cryptocom-txn-currency=GBP, cryptocom-usd-amount=0.0]
            2025-10-20T20:15:04Z | Curve -> PayPal | 3.14 GBP | Paypal *Uber 40293
            2025-10-20T20:15:04Z | PayPal -> Uber 40293 | 3.14 GBP | Uber 40293 [excluded=reconciled]
            2025-11-02T11:02:33Z | Crypto.com Card -> Curve | 21.92 GBP | Crv*Paypal *Ubertrip 3 [cryptocom-txn-amount=-21.92, cryptocom-txn-currency=GBP, cryptocom-usd-amount=0.0]
            2025-11-02T11:02:33Z | Curve -> PayPal | 21.92 GBP | Paypal *Ubertrip 3
            2025-11-02T11:02:33Z | PayPal -> Ubertrip 3 | 21.92 GBP | Ubertrip 3 [excluded=reconciled]
            2025-11-02T11:55:15Z | PayPal -> UBER PAYMENTS UK LIMITED | 21.92 GBP | UBER PAYMENTS UK LIMITED [paypal-status=Completed, paypal-transaction-id=1, paypal-type=Pre-approved Payment Bill User Payment]
            2025-11-02T11:55:15Z | PayPal Card Funding -> PayPal | 21.92 GBP | General Credit Card Deposit [excluded=reconciled, paypal-status=Completed, paypal-transaction-id=2, paypal-type=General Credit Card Deposit, unidentified-counterparty=source]
            == trades (1)
            2025-10-20T20:15:01Z | PayPal 3.14 GBP -> PayPal 4.04 USD | Convert GBP to USD
            == relationships (13)
            pass-through: PRIMARY {2025-10-17T08:57:28Z | Crypto.com Card -> PayPal | 17.39 GBP | Paypal *Pretportrai [cryptocom-txn-amount=-17.39, cryptocom-txn-currency=GBP, cryptocom-usd-amount=0.0]} & RELATED {2025-10-17T08:57:28Z | PayPal -> Pretportrai | 17.39 GBP | Pretportrai [excluded=reconciled]}
            pass-through: PRIMARY {2025-10-17T08:57:32Z | PayPal Card Funding -> PayPal | 17.39 GBP | General Credit Card Deposit [excluded=reconciled, paypal-status=Completed, paypal-transaction-id=8, paypal-type=General Credit Card Deposit, unidentified-counterparty=source]} & RELATED {2025-10-17T08:57:32Z | PayPal -> Pret-a-Portrait Ltd | 17.39 GBP | Pret-a-Portrait Ltd [paypal-status=Completed, paypal-transaction-id=7, paypal-type=Express Checkout Payment]}
            pass-through: PRIMARY {2025-10-20T20:15:04Z | Crypto.com Card -> Curve | 3.14 GBP | Crv*Paypal *Uber 40293 [cryptocom-txn-amount=-3.14, cryptocom-txn-currency=GBP, cryptocom-usd-amount=0.0]} & RELATED {2025-10-20T20:15:04Z | Curve -> PayPal | 3.14 GBP | Paypal *Uber 40293}
            pass-through: PRIMARY {2025-10-20T20:15:04Z | Curve -> PayPal | 3.14 GBP | Paypal *Uber 40293} & RELATED {2025-10-20T20:15:04Z | PayPal -> Uber 40293 | 3.14 GBP | Uber 40293 [excluded=reconciled]}
            pass-through: PRIMARY {2025-11-02T11:02:33Z | Crypto.com Card -> Curve | 21.92 GBP | Crv*Paypal *Ubertrip 3 [cryptocom-txn-amount=-21.92, cryptocom-txn-currency=GBP, cryptocom-usd-amount=0.0]} & RELATED {2025-11-02T11:02:33Z | Curve -> PayPal | 21.92 GBP | Paypal *Ubertrip 3}
            pass-through: PRIMARY {2025-11-02T11:02:33Z | Curve -> PayPal | 21.92 GBP | Paypal *Ubertrip 3} & RELATED {2025-11-02T11:02:33Z | PayPal -> Ubertrip 3 | 21.92 GBP | Ubertrip 3 [excluded=reconciled]}
            pass-through: PRIMARY {2025-11-02T11:55:15Z | PayPal Card Funding -> PayPal | 21.92 GBP | General Credit Card Deposit [excluded=reconciled, paypal-status=Completed, paypal-transaction-id=2, paypal-type=General Credit Card Deposit, unidentified-counterparty=source]} & RELATED {2025-11-02T11:55:15Z | PayPal -> UBER PAYMENTS UK LIMITED | 21.92 GBP | UBER PAYMENTS UK LIMITED [paypal-status=Completed, paypal-transaction-id=1, paypal-type=Pre-approved Payment Bill User Payment]}
            reconciled: PRIMARY {2025-10-17T08:57:32Z | PayPal Card Funding -> PayPal | 17.39 GBP | General Credit Card Deposit [excluded=reconciled, paypal-status=Completed, paypal-transaction-id=8, paypal-type=General Credit Card Deposit, unidentified-counterparty=source]} & RELATED {2025-10-17T08:57:28Z | Crypto.com Card -> PayPal | 17.39 GBP | Paypal *Pretportrai [cryptocom-txn-amount=-17.39, cryptocom-txn-currency=GBP, cryptocom-usd-amount=0.0]}
            reconciled: PRIMARY {2025-10-17T08:57:32Z | PayPal Card Funding -> PayPal | 17.39 GBP | General Credit Card Deposit [excluded=reconciled, paypal-status=Completed, paypal-transaction-id=8, paypal-type=General Credit Card Deposit, unidentified-counterparty=source]} & RELATED {2025-10-17T08:57:28Z | PayPal -> Pretportrai | 17.39 GBP | Pretportrai [excluded=reconciled]}
            reconciled: PRIMARY {2025-10-20T20:15:01Z | PayPal Card Funding -> PayPal | 3.14 GBP | General Credit Card Deposit [excluded=reconciled, paypal-status=Completed, paypal-transaction-id=4, paypal-type=General Credit Card Deposit, unidentified-counterparty=source]} & RELATED {2025-10-20T20:15:04Z | Curve -> PayPal | 3.14 GBP | Paypal *Uber 40293}
            reconciled: PRIMARY {2025-10-20T20:15:01Z | PayPal Card Funding -> PayPal | 3.14 GBP | General Credit Card Deposit [excluded=reconciled, paypal-status=Completed, paypal-transaction-id=4, paypal-type=General Credit Card Deposit, unidentified-counterparty=source]} & RELATED {2025-10-20T20:15:04Z | PayPal -> Uber 40293 | 3.14 GBP | Uber 40293 [excluded=reconciled]}
            reconciled: PRIMARY {2025-11-02T11:55:15Z | PayPal Card Funding -> PayPal | 21.92 GBP | General Credit Card Deposit [excluded=reconciled, paypal-status=Completed, paypal-transaction-id=2, paypal-type=General Credit Card Deposit, unidentified-counterparty=source]} & RELATED {2025-11-02T11:02:33Z | Curve -> PayPal | 21.92 GBP | Paypal *Ubertrip 3}
            reconciled: PRIMARY {2025-11-02T11:55:15Z | PayPal Card Funding -> PayPal | 21.92 GBP | General Credit Card Deposit [excluded=reconciled, paypal-status=Completed, paypal-transaction-id=2, paypal-type=General Credit Card Deposit, unidentified-counterparty=source]} & RELATED {2025-11-02T11:02:33Z | PayPal -> Ubertrip 3 | 21.92 GBP | Ubertrip 3 [excluded=reconciled]}
            == daily balances (21)
            Crypto.com Card | 2025-10-17 | GBP | -17.39
            Crypto.com Card | 2025-10-20 | GBP | -20.53
            Crypto.com Card | 2025-11-02 | GBP | -42.45
            Curve | 2025-10-20 | GBP | 0
            Curve | 2025-11-02 | GBP | 0
            Frank Frumento | 2025-10-10 | GBP | -8
            PayPal Bank Transfers | 2025-10-10 | GBP | 8
            PayPal Card Funding | 2025-10-17 | GBP | 0
            PayPal Card Funding | 2025-10-20 | GBP | 0
            PayPal Card Funding | 2025-11-02 | GBP | 0
            PayPal | 2025-10-10 | GBP | 0
            PayPal | 2025-10-17 | GBP | 0
            PayPal | 2025-10-20 | GBP | 0
            PayPal | 2025-10-20 | USD | 0
            PayPal | 2025-11-02 | GBP | 0
            Pret-a-Portrait Ltd | 2025-10-17 | GBP | 17.39
            Pretportrai | 2025-10-17 | GBP | 0
            UBER PAYMENTS UK LIMITED | 2025-11-02 | GBP | 21.92
            Uber 40293 | 2025-10-20 | GBP | 0
            Uber Technologies, Inc | 2025-10-20 | USD | 4.04
            Ubertrip 3 | 2025-11-02 | GBP | 0
            """.trimIndent(),
        "Crypto.com card + Curve export" to
            """
            == accounts (6)
            AMZN Mktp UK
            Amazon
            Crypto.com Card [card-last4=7721]
            Curve
            Curve card 7721
            Mavroni
            == transfers (6)
            2023-11-18T12:00:00Z | Curve -> Mavroni | 50 EUR | Mavroni
            2023-11-18T12:00:00Z | Curve card 7721 -> Curve | 50 EUR | Mavroni [curve-funding-card=7721, curve-mcc=5999, unidentified-counterparty=source]
            2023-11-19T12:00:00Z | Curve -> AMZN Mktp UK | 12.99 GBP | AMZN Mktp UK
            2023-11-19T12:00:00Z | Curve card 7721 -> Curve | 12.99 GBP | AMZN Mktp UK [curve-funding-card=7721, curve-mcc=5999, excluded=reconciled, unidentified-counterparty=source]
            2023-11-19T21:15:00Z | Crypto.com Card -> Curve | 12.99 GBP | Crv*Amazon [cryptocom-txn-amount=-12.99, cryptocom-txn-currency=GBP, cryptocom-usd-amount=0.0]
            2023-11-19T21:15:00Z | Curve -> Amazon | 12.99 GBP | Amazon [excluded=reconciled]
            == trades (0)
            == relationships (5)
            pass-through: PRIMARY {2023-11-18T12:00:00Z | Curve card 7721 -> Curve | 50 EUR | Mavroni [curve-funding-card=7721, curve-mcc=5999, unidentified-counterparty=source]} & RELATED {2023-11-18T12:00:00Z | Curve -> Mavroni | 50 EUR | Mavroni}
            pass-through: PRIMARY {2023-11-19T12:00:00Z | Curve card 7721 -> Curve | 12.99 GBP | AMZN Mktp UK [curve-funding-card=7721, curve-mcc=5999, excluded=reconciled, unidentified-counterparty=source]} & RELATED {2023-11-19T12:00:00Z | Curve -> AMZN Mktp UK | 12.99 GBP | AMZN Mktp UK}
            pass-through: PRIMARY {2023-11-19T21:15:00Z | Crypto.com Card -> Curve | 12.99 GBP | Crv*Amazon [cryptocom-txn-amount=-12.99, cryptocom-txn-currency=GBP, cryptocom-usd-amount=0.0]} & RELATED {2023-11-19T21:15:00Z | Curve -> Amazon | 12.99 GBP | Amazon [excluded=reconciled]}
            reconciled: PRIMARY {2023-11-19T12:00:00Z | Curve card 7721 -> Curve | 12.99 GBP | AMZN Mktp UK [curve-funding-card=7721, curve-mcc=5999, excluded=reconciled, unidentified-counterparty=source]} & RELATED {2023-11-19T21:15:00Z | Crypto.com Card -> Curve | 12.99 GBP | Crv*Amazon [cryptocom-txn-amount=-12.99, cryptocom-txn-currency=GBP, cryptocom-usd-amount=0.0]}
            reconciled: PRIMARY {2023-11-19T12:00:00Z | Curve card 7721 -> Curve | 12.99 GBP | AMZN Mktp UK [curve-funding-card=7721, curve-mcc=5999, excluded=reconciled, unidentified-counterparty=source]} & RELATED {2023-11-19T21:15:00Z | Curve -> Amazon | 12.99 GBP | Amazon [excluded=reconciled]}
            == daily balances (8)
            AMZN Mktp UK | 2023-11-19 | GBP | 12.99
            Amazon | 2023-11-19 | GBP | 0
            Crypto.com Card | 2023-11-19 | GBP | -12.99
            Curve card 7721 | 2023-11-18 | EUR | -50
            Curve card 7721 | 2023-11-19 | GBP | 0
            Curve | 2023-11-18 | EUR | 0
            Curve | 2023-11-19 | GBP | 0
            Mavroni | 2023-11-18 | EUR | 50
            """.trimIndent(),
        "Binance CSV + Koinly (reconciliation source)" to
            """
            == accounts (6)
            Binance
            Binance Funding
            Koinly · Binance Fees [fee-account-for=Koinly · Binance, reconciliation-source=Koinly]
            Koinly · Binance [reconciliation-source=Koinly]
            Koinly · Nexo [reconciliation-source=Koinly]
            Koinly: External [reconciliation-source=Koinly]
            == transfers (5)
            2021-05-20T10:00:00Z | Binance Funding -> Binance | 0.5 BTC | Deposit [binance-operation=Deposit, binance-user-id=53064551, unidentified-counterparty=source]
            2021-05-20T10:00:00Z | Koinly: External -> Koinly · Binance | 0.5 BTC | deposit [koinly-id=K0, koinly-type=deposit]
            2021-05-26T19:48:36Z | Koinly · Binance -> Koinly · Binance Fees | 0.00055 BTC | Fee
            2021-05-26T19:48:36Z | Koinly · Binance -> Koinly · Nexo | 0.27931608 BTC | transfer [koinly-id=K1, koinly-type=transfer]
            2021-05-26T19:49:05Z | Binance -> Binance Funding | 0.27986608 BTC | Withdraw [binance-operation=Withdraw, binance-remark=Withdraw fee is included, binance-user-id=53064551, manual-entry=Withdrawal fee, unidentified-counterparty=target]
            == trades (0)
            == relationships (1)
            fee: PRIMARY {2021-05-26T19:48:36Z | Koinly · Binance -> Koinly · Nexo | 0.27931608 BTC | transfer [koinly-id=K1, koinly-type=transfer]} & RELATED {2021-05-26T19:48:36Z | Koinly · Binance -> Koinly · Binance Fees | 0.00055 BTC | Fee}
            == daily balances (9)
            Binance Funding | 2021-05-20 | BTC | -0.5
            Binance Funding | 2021-05-26 | BTC | -0.22013392
            Binance | 2021-05-20 | BTC | 0.5
            Binance | 2021-05-26 | BTC | 0.22013392
            Koinly · Binance Fees | 2021-05-26 | BTC | 0.00055
            Koinly · Binance | 2021-05-20 | BTC | 0.5
            Koinly · Binance | 2021-05-26 | BTC | 0.22013392
            Koinly · Nexo | 2021-05-26 | BTC | 0.27931608
            Koinly: External | 2021-05-20 | BTC | -0.5
            """.trimIndent(),
        "Bybit CSV + overlapping API sessions" to
            """
            == accounts (4)
            BTC:bc1qwithdrawaddr [blockchain-wallet-address=bc1qwithdrawaddr]
            Bybit Fees [fee-account-for=Bybit]
            Bybit Funding
            Bybit [exchange-account-external-id=bybit]
            == transfers (5)
            2022-01-02T14:36:12Z | Bybit Funding -> Bybit | 1346 USDT | Deposit USDT [blockchain-txid=0xdeposit, bybit-type=userDeposit, bybit-uid=12345678, exchange.txn_id=3562579, timestamp-approximate=1643327999999]
            2022-01-05T13:18:18Z | Bybit -> Bybit Fees | 0.4928 KASTA | tradingFee [bybit-type=tradingFee, bybit-uid=12345678]
            2023-11-13T09:18:16Z | Bybit -> BTC:bc1qwithdrawaddr | 0.00031164 BTC | Withdraw BTC [blockchain-txid=withdrawtx, exchange.txn_id=26711021]
            2023-11-13T09:18:16Z | Bybit -> Bybit Fees | 0.000085 BTC | BTC fee [exchange.txn_id=26711021-fee]
            2023-11-13T09:18:18Z | Bybit -> Bybit Funding | 0.00039664 BTC | Withdraw [bybit-description=Withdrawal, bybit-type=Withdraw, bybit-uid=12345678, excluded=reconciled, unidentified-counterparty=target]
            == trades (1)
            2022-01-05T13:18:18Z | Bybit 684.982144 USDT -> Bybit 492.8 KASTA | Buy KASTA/USDT
            == relationships (1)
            reconciled: PRIMARY {2023-11-13T09:18:18Z | Bybit -> Bybit Funding | 0.00039664 BTC | Withdraw [bybit-description=Withdrawal, bybit-type=Withdraw, bybit-uid=12345678, excluded=reconciled, unidentified-counterparty=target]} & RELATED {2023-11-13T09:18:16Z | Bybit -> BTC:bc1qwithdrawaddr | 0.00031164 BTC | Withdraw BTC [blockchain-txid=withdrawtx, exchange.txn_id=26711021]}
            == daily balances (9)
            BTC:bc1qwithdrawaddr | 2023-11-13 | BTC | 0.00031164
            Bybit Fees | 2022-01-05 | KASTA | 0.4928
            Bybit Fees | 2023-11-13 | BTC | 0.000085
            Bybit Funding | 2022-01-02 | USDT | -1346
            Bybit Funding | 2023-11-13 | BTC | 0
            Bybit | 2022-01-02 | USDT | 1346
            Bybit | 2022-01-05 | KASTA | 492.3072
            Bybit | 2022-01-05 | USDT | 661.017856
            Bybit | 2023-11-13 | BTC | -0.00039664
            """.trimIndent(),
        "Binance CSV + API session" to
            """
            == accounts (7)
            BTC:bc1qdepositaddr [blockchain-wallet-address=bc1qdepositaddr]
            Binance Bank
            Binance Earn
            Binance Fees [fee-account-for=Binance]
            Binance Funding
            Binance [exchange-account-external-id=binance]
            ETH:0xwithdrawaddr [blockchain-wallet-address=0xwithdrawaddr]
            == transfers (12)
            2023-11-14T22:13:21.450Z | BTC:bc1qdepositaddr -> Binance | 0.01 BTC | Deposit BTC [binance-operation=Deposit, binance-user-id=53064551, blockchain-txid=onchain-dep-1, exchange.txn_id=d1]
            2023-11-14T22:13:25.775Z | Binance -> Binance Fees | 4 USDT | Buy BTC/USDT fee [exchange.txn_id=BTCUSDT-28457-fee]
            2023-11-14T22:13:25.775Z | Binance -> Binance Fees | 8 USDT | Buy BTC/USDT fee [exchange.txn_id=BTCUSDT-28458-fee]
            2023-11-14T22:13:25Z | Binance -> Binance Fees | 4 USDT | Transaction Fee [binance-operation=Transaction Fee, binance-user-id=53064551, excluded=reconciled]
            2023-11-14T22:13:25Z | Binance -> Binance Fees | 8 USDT | Transaction Fee [binance-operation=Transaction Fee, binance-user-id=53064551, excluded=reconciled]
            2023-11-14T22:13:26Z | Binance -> Binance Earn | 5.81 XMR | Simple Earn Flexible Subscription [binance-operation=Simple Earn Flexible Subscription, binance-remark=Binance Earn, binance-user-id=53064551, exchange.txn_id=26055]
            2023-11-14T22:13:32Z | Binance -> Binance Bank | 1133.33 GBP | Withdraw GBP [exchange.txn_id=fw1]
            2023-11-14T22:13:32Z | Binance -> Binance Fees | 1.5 GBP | GBP fee [exchange.txn_id=fw1-fee]
            2023-11-14T22:19:00Z | Binance -> Binance Funding | 82.358 LINK | Withdraw [binance-operation=Withdraw, binance-remark=Withdraw fee is included, binance-user-id=53064551, excluded=reconciled, manual-entry=Withdrawal fee, unidentified-counterparty=target]
            2023-11-14T22:19:30Z | Binance -> Binance Fees | 1.25 LINK | LINK fee [exchange.txn_id=w1-fee]
            2023-11-14T22:19:30Z | Binance -> ETH:0xwithdrawaddr | 81.108 LINK | Withdraw LINK [blockchain-txid=onchain-wd-1, exchange.txn_id=w1]
            2023-11-14T22:20:00Z | Binance -> Binance Bank | 1134.83 GBP | Fiat Withdrawal [binance-operation=Fiat Withdrawal, binance-user-id=53064551, excluded=reconciled, unidentified-counterparty=target]
            == trades (2)
            2023-11-14T22:13:25.775Z | Binance 4000 USDT -> Binance 0.1 BTC | Buy BTC/USDT
            2023-11-14T22:13:25.775Z | Binance 8000 USDT -> Binance 0.2 BTC | Buy BTC/USDT
            == relationships (4)
            reconciled: PRIMARY {2023-11-14T22:13:25Z | Binance -> Binance Fees | 4 USDT | Transaction Fee [binance-operation=Transaction Fee, binance-user-id=53064551, excluded=reconciled]} & RELATED {2023-11-14T22:13:25.775Z | Binance -> Binance Fees | 4 USDT | Buy BTC/USDT fee [exchange.txn_id=BTCUSDT-28457-fee]}
            reconciled: PRIMARY {2023-11-14T22:13:25Z | Binance -> Binance Fees | 8 USDT | Transaction Fee [binance-operation=Transaction Fee, binance-user-id=53064551, excluded=reconciled]} & RELATED {2023-11-14T22:13:25.775Z | Binance -> Binance Fees | 8 USDT | Buy BTC/USDT fee [exchange.txn_id=BTCUSDT-28458-fee]}
            reconciled: PRIMARY {2023-11-14T22:19:00Z | Binance -> Binance Funding | 82.358 LINK | Withdraw [binance-operation=Withdraw, binance-remark=Withdraw fee is included, binance-user-id=53064551, excluded=reconciled, manual-entry=Withdrawal fee, unidentified-counterparty=target]} & RELATED {2023-11-14T22:19:30Z | Binance -> ETH:0xwithdrawaddr | 81.108 LINK | Withdraw LINK [blockchain-txid=onchain-wd-1, exchange.txn_id=w1]}
            reconciled: PRIMARY {2023-11-14T22:20:00Z | Binance -> Binance Bank | 1134.83 GBP | Fiat Withdrawal [binance-operation=Fiat Withdrawal, binance-user-id=53064551, excluded=reconciled, unidentified-counterparty=target]} & RELATED {2023-11-14T22:13:32Z | Binance -> Binance Bank | 1133.33 GBP | Withdraw GBP [exchange.txn_id=fw1]}
            == daily balances (13)
            BTC:bc1qdepositaddr | 2023-11-14 | BTC | -0.01
            Binance Bank | 2023-11-14 | GBP | 1133.33
            Binance Earn | 2023-11-14 | XMR | 5.81
            Binance Fees | 2023-11-14 | GBP | 1.5
            Binance Fees | 2023-11-14 | LINK | 1.25
            Binance Fees | 2023-11-14 | USDT | 12
            Binance Funding | 2023-11-14 | LINK | 0
            Binance | 2023-11-14 | BTC | 0.31
            Binance | 2023-11-14 | GBP | -1134.83
            Binance | 2023-11-14 | LINK | -82.358
            Binance | 2023-11-14 | USDT | -12012
            Binance | 2023-11-14 | XMR | -5.81
            ETH:0xwithdrawaddr | 2023-11-14 | LINK | 81.108
            """.trimIndent(),
        "Monzo CSV + overlapping API sessions" to
            """
            == accounts (4)
            Landlord [counterparty-name-key=landlord]
            Monzo [account-external-id=acc_00009ORACLE00000000001]
            Pret A Manger [counterparty-name-key=pret a manger]
            Tesco [counterparty-name-key=tesco]
            == transfers (3)
            2023-11-20T08:01:00Z | Monzo -> Tesco | 12.4 GBP | TESCO STORES [Monzo Category=Transfers, Monzo Transaction ID=tx_api_1, Type=Faster payment]
            2023-11-21T18:30:00Z | Monzo -> Landlord | 900 GBP | RENT [Monzo Category=Transfers, Monzo Transaction ID=tx_api_2, Type=Faster payment]
            2023-11-22T12:00:00Z | Monzo -> Pret A Manger | 3.5 GBP | PRET A MANGER
            == trades (0)
            == relationships (0)
            == daily balances (6)
            Landlord | 2023-11-21 | GBP | 900
            Monzo | 2023-11-20 | GBP | -12.4
            Monzo | 2023-11-21 | GBP | -912.4
            Monzo | 2023-11-22 | GBP | -915.9
            Pret A Manger | 2023-11-22 | GBP | 3.5
            Tesco | 2023-11-20 | GBP | 12.4
            """.trimIndent(),
    )
