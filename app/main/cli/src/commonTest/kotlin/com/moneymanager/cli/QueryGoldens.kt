package com.moneymanager.cli

// Golden stdout of the query commands over [seedQueryFixture]. Native has no classpath resources, so they live here.
// A change to one is a change to the documented JSON contract (docs/cli-json.md): bump the schema version if it
// renames or removes a field.

internal val ACCOUNTS_GOLDEN =
    """
    {
        "schemaVersion": 1,
        "data": [
            {
                "id": 6,
                "name": "Corner Shop",
                "openingDate": "2024-01-01T00:00:00Z",
                "categoryId": -1,
                "shadow": false,
                "owners": [],
                "balances": [
                    {
                        "asset": "GBP",
                        "kind": "currency",
                        "amount": "25.5"
                    }
                ]
            },
            {
                "id": 1,
                "name": "Current",
                "openingDate": "2024-01-01T00:00:00Z",
                "categoryId": -1,
                "shadow": false,
                "owners": [
                    {
                        "id": 1,
                        "name": "Alice Smith"
                    }
                ],
                "balances": [
                    {
                        "asset": "GBP",
                        "kind": "currency",
                        "amount": "874.5"
                    }
                ]
            },
            {
                "id": 2,
                "name": "Current Saver",
                "openingDate": "2024-01-01T00:00:00Z",
                "categoryId": -1,
                "shadow": false,
                "owners": [
                    {
                        "id": 1,
                        "name": "Alice Smith"
                    }
                ],
                "balances": [
                    {
                        "asset": "GBP",
                        "kind": "currency",
                        "amount": "0"
                    }
                ]
            },
            {
                "id": 5,
                "name": "Employer",
                "openingDate": "2024-01-01T00:00:00Z",
                "categoryId": -1,
                "shadow": false,
                "owners": [],
                "balances": [
                    {
                        "asset": "BTC",
                        "kind": "crypto",
                        "amount": "-0.0002469"
                    },
                    {
                        "asset": "GBP",
                        "kind": "currency",
                        "amount": "-1000"
                    }
                ]
            },
            {
                "id": 3,
                "name": "Wallet",
                "openingDate": "2024-01-01T00:00:00Z",
                "categoryId": -1,
                "shadow": false,
                "owners": [
                    {
                        "id": 1,
                        "name": "Alice Smith"
                    },
                    {
                        "id": 2,
                        "name": "Bob"
                    }
                ],
                "balances": [
                    {
                        "asset": "BTC",
                        "kind": "crypto",
                        "amount": "0.00012345"
                    },
                    {
                        "asset": "GBP",
                        "kind": "currency",
                        "amount": "100"
                    }
                ]
            }
        ]
    }
    """.trimIndent()

internal val PEOPLE_GOLDEN =
    """
    {
        "schemaVersion": 1,
        "data": [
            {
                "id": 1,
                "firstName": "Alice",
                "middleName": null,
                "lastName": "Smith",
                "fullName": "Alice Smith"
            },
            {
                "id": 2,
                "firstName": "Bob",
                "middleName": null,
                "lastName": null,
                "fullName": "Bob"
            },
            {
                "id": 3,
                "firstName": "Sam",
                "middleName": null,
                "lastName": "Lee",
                "fullName": "Sam Lee"
            },
            {
                "id": 4,
                "firstName": "Sam",
                "middleName": null,
                "lastName": "Lee",
                "fullName": "Sam Lee"
            }
        ]
    }
    """.trimIndent()

internal val TRANSACTIONS_GOLDEN =
    """
    {
        "schemaVersion": 1,
        "data": [
            {
                "id": 5,
                "kind": "transfer",
                "timestamp": "2024-03-04T08:00:00Z",
                "description": "Unsave",
                "asset": "GBP",
                "assetKind": "currency",
                "amount": "10",
                "runningBalance": "874.5",
                "counterpartyAccountId": 2,
                "counterpartyAccount": "Current Saver",
                "excluded": false,
                "reconciled": false
            },
            {
                "id": 4,
                "kind": "transfer",
                "timestamp": "2024-03-03T08:00:00Z",
                "description": "Save",
                "asset": "GBP",
                "assetKind": "currency",
                "amount": "-10",
                "runningBalance": "864.5",
                "counterpartyAccountId": 2,
                "counterpartyAccount": "Current Saver",
                "excluded": false,
                "reconciled": false
            },
            {
                "id": 3,
                "kind": "transfer",
                "timestamp": "2024-03-02T12:30:00Z",
                "description": "Milk",
                "asset": "GBP",
                "assetKind": "currency",
                "amount": "-25.5",
                "runningBalance": "874.5",
                "counterpartyAccountId": 6,
                "counterpartyAccount": "Corner Shop",
                "excluded": false,
                "reconciled": false
            },
            {
                "id": 2,
                "kind": "transfer",
                "timestamp": "2024-03-01T10:00:00Z",
                "description": "To wallet",
                "asset": "GBP",
                "assetKind": "currency",
                "amount": "-100",
                "runningBalance": "900",
                "counterpartyAccountId": 3,
                "counterpartyAccount": "Wallet",
                "excluded": false,
                "reconciled": false
            },
            {
                "id": 1,
                "kind": "transfer",
                "timestamp": "2024-02-28T09:00:00Z",
                "description": "Salary",
                "asset": "GBP",
                "assetKind": "currency",
                "amount": "1000",
                "runningBalance": "1000",
                "counterpartyAccountId": 5,
                "counterpartyAccount": "Employer",
                "excluded": false,
                "reconciled": false
            }
        ]
    }
    """.trimIndent()
