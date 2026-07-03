# Strategy Library

The central catalog of import strategies and pass-through definitions for Money Manager. Every
`*.json` file in this folder is one installable artifact; the file name suffix says what it is:

| Suffix | Artifact |
|--------|----------|
| `Name.csv.json` | CSV import strategy |
| `Name.qif.json` | QIF import strategy |
| `Name.api.json` | API import strategy (bank REST API) |
| `Name.passthrough.json` | Pass-through (conduit) account definition |

CI publishes this folder (plus a generated `index.json` manifest) to GitHub Pages at
`https://nikolaymetchev.github.io/money-manager/strategy-library/`, where the app's
**Strategy catalog** screen lets users browse and install entries into their database.

## Contributing a strategy

1. Export your strategy from the app (strategies screens → export), or hand-write the JSON.
2. Name the file `<strategy name>.<kind>.json` — the `name` embedded in the JSON must match the
   file name stem exactly, and names must be unique per kind.
3. Keep the `version` field `""` (library artifacts aren't tied to an app version).
4. Open a PR adding the file. CI validates every artifact (decodable, name matches file name).

Do not add `global-account-mappings.json` — global account mappings are personal data.

## Built-in strategies

The artifacts for the built-in strategies (Monzo/Wise/Starling/QIF/Santander/Curve/PayPal) are
generated from Kotlin definitions in `test/app/strategies` — edit those and run
`./gradlew :tools:strategy-catalog:exportStrategyLibrary` rather than editing their JSON directly
(a test fails if the two drift apart).
