# `mm` JSON output

`mm` prints JSON when stdout is not a terminal, or with `--format json`. This document is the contract for
that JSON. Scripts can rely on it. Prompts, warnings, errors and progress go to stderr, so stdout is only ever
the result.

## Envelope

Every command prints one JSON object:

```json
{
    "schemaVersion": 1,
    "data": …,
    "nextCursor": "…"
}
```

- `data` is the command's result: an object for `version` and `status`, an array for the query commands.
- `nextCursor` appears only when another page follows (only `transactions` pages).
- **Versioning.** `schemaVersion` changes only when a field is renamed or removed, or its meaning changes.
  New fields can appear at any version, so ignore fields you don't know.

Conventions:
- **Amounts** are exact decimal strings, e.g. `"-12.5"` or `"0.00012345"`. They are never JSON numbers, which
  would round. Trailing zeros are dropped.
- **Times** are ISO-8601 instants in UTC, e.g. `"2024-03-01T10:00:00Z"`.
- **Ids.** Accounts, people and transactions have integer ids. Strategies and import directories have UUID
  strings. A strategy's id is different in every database, so use its `name` to tell strategies apart
  across databases.
- **Shadow accounts** are a reconciliation source's copy of an account, such as Koinly's. They hold no real
  money and are left out unless you pass `--include-shadow`.

## Exit codes

`0` ok, `1` error, `2` usage error, `3` partial failure or needs attention, `130` interrupted.

Unknown or ambiguous names are usage errors (`2`). The message lists the candidates with their ids, for example
`Several people are named 'Sam Lee': Sam Lee (id 3), Sam Lee (id 4)`. Pass an id instead.

## Commands

### `version`

`data`: `{ "version": "1.2.3" }`

### `status`

`data`:

| Field | |
|---|---|
| `version` | app version |
| `database` | path of the open database |
| `cloud` | `null`, or `{ remoteName, provider, status, localChanges }` for a cloud-backed database |
| `vault` | `{ state: "unbound" \| "no_file" \| "locked" \| "unlocked", path }` |

### `accounts [--owner NAME|ID]… [--account REGEX] [--include-shadow]`

`data`: an array of accounts:

| Field | |
|---|---|
| `id`, `name` | |
| `openingDate` | instant |
| `categoryId` | |
| `shadow` | `true` for a reconciliation source's shadow account |
| `owners` | `[{ id, name }]` |
| `balances` | `[{ asset, kind: "currency" \| "crypto", amount }]` |

### `balances [--owner NAME|ID]… [--account REGEX] [--asset CODE]… [--include-shadow] [--non-zero]`

`data`: one row per account and asset: `[{ accountId, account, asset, kind, amount }]`. `--asset` keeps only
those assets. `--non-zero` drops zero balances.

Both commands share the same filters:
- `--owner` matches a person's full name, ignoring case, or takes an id. Repeat it to match any of several people.
- `--account` is a regular expression, ignoring case, searched anywhere in the account name.
- Different filters combine with AND. Repeated values of one filter combine with OR.

Balances are brought up to date (the materialized views are refreshed) before they are read.

### `transactions --account REGEX|ID [--from DATE] [--to DATE] [--limit N] [--cursor C] [--include-shadow]`

One account's transactions, newest first, with the running balance after each one.

`--account` must name exactly one account: an id, or a regular expression matching one account's name. When
the pattern matches several accounts and exactly one of them has that exact name, that account wins
(`Current` picks "Current" over "Current Saver"). An id reaches a shadow account without `--include-shadow`.

`data`: an array of transactions:

| Field | |
|---|---|
| `id` | |
| `kind` | `"transfer"` or `"trade"` |
| `timestamp`, `description` | |
| `asset`, `assetKind` | |
| `amount` | signed from the account's side; negative means money left the account |
| `runningBalance` | the account's balance in `asset` after this transaction |
| `counterpartyAccountId`, `counterpartyAccount` | the other side of the transfer |
| `excluded`, `reconciled` | |

Paging:
- `--limit` sets the page size. The default is 100.
- When more transactions remain in the range, the envelope carries `nextCursor`. Pass it back with `--cursor`
  and the same `--account` and `--from` to get the next page.
- The cursor is opaque. Don't build or edit one.

Dates:
- `--from` and `--to` take a date (`YYYY-MM-DD`, in the local time zone) or an ISO-8601 instant.
- `--from` is inclusive.
- `--to` includes that whole day when given a date. Given an instant, it is exclusive.

### `people`

`data`: `[{ id, firstName, middleName, lastName, fullName }]`. `fullName` is what `--owner` matches.

### `strategies [--kind csv|api]`

`data`: the installed import strategies, CSV first, then API, each group sorted by name:
`[{ id, name, kind: "csv" | "api", updatedAt }]`.

### `import-dirs`

`data`: the configured import directories. Each top-level folder is followed by its discovered subfolders.

| Field | |
|---|---|
| `id`, `name` | |
| `provider` | `"local"` or `"gdrive"` |
| `path` | the folder as shown in the app |
| `topLevel`, `parentId` | |
| `excluded` | "download all" skips excluded folders |
| `accountId` | the account the folder's files import into, if set |
| `onThisDevice` | `false` for a local folder on another device |
| `trackedFiles`, `importedFiles` | files downloaded so far, and how many of those were imported |
| `lastImportedAt` | instant of the latest import, or `null` |

### `sql [SQL] [--limit N]`

Runs one read-only statement (`SELECT`, `WITH … SELECT` or `VALUES`); with no `SQL`, or `-`, it is read from stdin.
Anything else, a second statement, or SQL that SQLite rejects exits 2 with SQLite's message.

`data`: `{ columns, rows, truncated }`.

| Field | |
|---|---|
| `columns` | the result's column names, in order; a repeated name gets a suffix (`a`, `a:1`) |
| `rows` | one array per row, values in column order: integers as exact JSON numbers, reals as numbers, text as strings, blobs as upper-case hex strings, `NULL` as `null` |
| `truncated` | `true` when there were more than `--limit` rows (default 1000; `--limit 0` returns them all) |

The values are the database's own, so amounts are scaled integers (every asset uses a scale factor of 10^18) and
times are what the schema stores. The schema isn't part of this contract: it can change with any release.

### `import dirs [--dir NAME]… [--download-only] [--redownload]`, `import apis [--strategy NAME]… [--force-full] [--download-only]`, `import all [--force-full] [--download-only]`

These commands download and import. `import dirs` downloads every included import directory (local and Google
Drive), or only the `--dir` folders and their subfolders, and then imports every new CSV and QIF file.
`--redownload` first re-stages the files that are already staged. `import apis` makes sure every installed API
strategy with secrets in the vault has a connection, downloads every connection in parallel (or only the
`--strategy` ones), and imports the new sessions. `import all` runs both at once. Live progress goes to stderr,
and the materialized views are refreshed once at the end.

`data` is one object. A section the command didn't run is left out.

| Field | |
|---|---|
| `directories` | `{ folders, foldersCreated, redownload?, files, failures }` (see below) |
| `apis` | `{ connections, sessions }` (see below) |
| `needsAttention` | `[{ kind, subject, reason }]`: everything a person must resolve |
| `errors` | `import all` only: a side that failed as a whole, while the other side carried on |

`directories`:
- `folders`: `[{ id, name, downloaded, unchanged, skipped, failed, failures }]`, one entry per folder downloaded.
  `failures` are `"file: reason"` strings.
- `foldersCreated`: how many subfolders were discovered and added as directories.
- `redownload`, with `--redownload` only: `{ replaced, unchanged, blocked, reimportSuggested, failures }`, each a
  list of file names.
- `files`: `[{ id, file, format: "csv" | "qif", status, strategy, transfers, duplicates, error }]`. `status` is
  `imported`, `nothingToImport`, `skipped` or `failed`. `error` is present only when the file failed.
- `failures`: folders that couldn't be downloaded or searched at all.

`apis`:
- `connections`: `[{ id, strategy, status, sessionId, message }]`. `status` is one of:
  - `downloaded`;
  - `notDownloaded`: no strategy, no secrets in the vault, or blocked by region;
  - `failed`;
  - `busy`: already downloading elsewhere.
- `sessions`: `[{ id, connectionId, strategy, status, accounts, transactions, people, duplicates, errors, error }]`.
  These are the never-imported sessions that were queued, including ones left over from earlier downloads.
  `status` is `imported`, `needsAttention`, `skipped` (its strategy is gone) or `failed`.

`needsAttention.kind` is one of the following. mm never guesses any of them.

| `kind` | `subject` | Resolve it by |
|---|---|---|
| `noStrategy` | file name | installing or writing a strategy that matches it |
| `needsSourceAccount` | file name | setting an account on its import folder |
| `redownloadBlocked` | file name | re-downloading with "unimport" in the app (its imported rows are gone from the new copy) |
| `reimportSuggested` | file name | re-importing it (its columns changed) |
| `unassignedCard` | card reference | assigning it to an account (Imports → Reconciliation → Card Last-4) |
| `counterparties` | `"<strategy> session #<id>"` | confirming the account names when importing the session in the app |
| `vaultLocked` | `"APIs"` | `import all` only: unlocking the vault (`--vault-password-stdin` or `MM_VAULT_PASSWORD`) |

The exit code is `3` when anything needs attention or any folder, file, connection or session failed. The summary
is printed either way.

A Google Drive folder with no sign-in in the vault opens the Google consent page. With `--no-browser`, mm prints
the URL instead and waits for the redirect on a loopback port. Over SSH, forward that port
(`ssh -L <port>:localhost:<port> host`) and open the URL locally. On a cloud-backed database, writes are refused
until the local copy is current (exit `1`, rerun with `--hydrate`). On exit you're asked whether to upload the
changes (or pass `--push`).
