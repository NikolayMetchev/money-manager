# Built-in strategies

The Kotlin definitions of every built-in import strategy and pass-through definition — the source of
the **strategy catalog** the app installs from:

- `BuiltInCsvStrategies` — CSV/QIF import strategies (Monzo CSV, Wise CSV, QIF, Santander QIF)
- `BuiltInApiStrategies` — bank REST API strategies (Monzo, Wise, Starling)
- `BuiltInPassThroughs` — pass-through (conduit) definitions (Curve, PayPal)

Nothing here is seeded into databases and no JSON is checked in. On every Pages deploy,
`:tools:strategy-catalog:generateCatalogSite` renders these definitions to portable JSON artifacts in
`webpage/strategy-library/` (gitignored) with an `index.json` manifest, published at
`https://nikolaymetchev.github.io/money-manager/strategy-library/`. The in-app **Strategy catalog**
screen fetches that manifest and installs selected entries via the import engine.

## Contributing a strategy

Add a Kotlin definition here (and register it in the relevant `builtIn*()` list) — the catalog site
and its validation tests pick it up automatically. Strategy names must be unique per kind; keep
definitions DB-free (names/codes instead of entity ids — the export mappers enforce the rest).
