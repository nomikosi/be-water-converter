<div align="center">
  <img src="docs/logo.png" alt="Be Water Converter logo" width="220"/>

  # Be Water Converter

  *Be water, my friend — let your data flow between formats.*

  An IntelliJ IDEA plugin that converts data between JSON, XML, YAML, CSV, TOML and
  Protobuf, and generates Java POJOs — all inside a syntax-highlighted tool window.

  [![Build](https://github.com/nomikosi/be-water-converter/actions/workflows/build.yml/badge.svg)](https://github.com/nomikosi/be-water-converter/actions/workflows/build.yml)
  [![Java 21](https://img.shields.io/badge/Java-21-blue)](https://openjdk.org/projects/jdk/21/)
  [![IntelliJ 2024.3+](https://img.shields.io/badge/IntelliJ-2024.3%2B-purple)](https://plugins.jetbrains.com/)
  [![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-green)](LICENSE)
</div>

---

## Overview

Be Water Converter adds a tool window (anchored on the right) with a two-pane editor UI
for input and output, toolbar actions for convert, format, swap, copy, clear, open, and
save, and a context-sensitive options bar for conversion-specific settings. Inputs are
normalized through JSON as an internal pivot format, which keeps the individual converters
small and makes every cross-format conversion path consistent.

The UI is built around `RSyntaxTextArea` editors with dark-theme styling, input/output
format badges, and dynamic output-format constraints based on the selected source format.
The toolbar wraps responsively onto multiple rows when the tool window is narrow.

## Installation

1. In IntelliJ IDEA, go to **Settings → Plugins → Marketplace**.
2. Search for **Be Water Converter**.
3. Click **Install** and restart the IDE.

Or install from disk: download the ZIP from the
[releases page](https://github.com/nomikosi/be-water-converter/releases), then
**Settings → Plugins → ⚙ → Install Plugin from Disk…**.

Once installed, open the **Be Water** tool window from the right side bar, or via
**Tools → Be Water Converter**.

## Supported conversions

| Input | Supported outputs |
|---|---|
| JSON | XML, YAML, CSV, TOML, Protobuf, Java POJO |
| XML | JSON, YAML, CSV, TOML, Protobuf, Java POJO |
| YAML | JSON, XML, CSV, TOML, Protobuf, Java POJO |
| CSV | JSON, XML, YAML, TOML, Protobuf, Java POJO |
| TOML | JSON, XML, YAML, CSV, Protobuf, Java POJO |
| Protobuf | JSON, XML, YAML, CSV, TOML, Java POJO |

Most conversions follow a two-step flow: input is first normalized to JSON, then JSON is
rendered to the requested target format. JSON input is parsed leniently — comments,
trailing commas, single quotes, and unquoted field names are accepted — and additionally
passes through an auto-close step that repairs unclosed `{` / `[` brackets and
unterminated strings before parsing. Multi-document YAML (`---`-separated, e.g.
Kubernetes manifests) converts to a JSON array with one element per document. JSON keys
that are not valid XML element names or Protobuf identifiers (spaces, kebab-case,
leading digits) are sanitized when rendering to those formats, so the output is always
well-formed.

## Features

### Interactive tool window

The plugin is registered through `ConverterToolWindowFactory`, which mounts a
`ConverterPanel` as tool-window content. The panel contains split editors, format
selectors, a swap button between the From/To selectors, status feedback, and one-click
actions for conversion, formatting, file open/save, and more. The output editor's syntax
mode and format badge update automatically after each successful conversion. Conversions
run in the background and can be cancelled — the Convert button turns into **Cancel**
while one is running. Multi-line validation errors are delivered as IDE notification
balloons (the status bar shows the first line). Very large outputs are rendered with
syntax highlighting disabled to keep the editor responsive.

A **history** toolbar button lists the last 20 successful conversions of the session
(time, formats, output size); selecting an entry restores both editors and format
selections. Conversions over ~1 MB of combined text are not recorded, so history never
holds large payloads in memory. Swap is
available when the current output format is also a supported input format; generated
Java POJO output is intentionally output-only.

### Keyboard shortcut

| Shortcut | Action |
|---|---|
| <kbd>Ctrl</kbd>+<kbd>Enter</kbd> | Convert input to selected output format |
| <kbd>Ctrl</kbd>+<kbd>F</kbd> | Find in the focused editor (Enter = next, Shift+Enter = previous, Esc = close) |

This shortcut is active while focus is inside the Be Water tool window. Other actions are
available from the toolbar buttons. The main operations (Convert, Format Input, Copy
Output, Open File, Save Output) are also registered as IDE actions, so you can find them
via **Find Action** and assign your own shortcuts in **Settings → Keymap** (search for
"Be Water").

### File import and export

**Open** loads a file into the input editor and auto-detects the source format from the
file extension (`.json`, `.xml`, `.yaml`/`.yml`, `.csv`, `.toml`, `.proto`). **Save**
writes the current output to disk using the appropriate format extension.

You can also **drag and drop** a file directly onto the input editor. The file is loaded
and the source format is auto-detected from the extension, just like the Open action.

### Format-aware formatting

The **Format** action pretty-prints or canonicalizes the current input for JSON, XML,
YAML, and TOML. JSON formatting also applies the lenient auto-close logic, which helps
recover truncated input during interactive editing.

### Conversion-specific options bar

When the selected input or output format has extra settings, a dedicated options bar
appears below the toolbar. CSV output shows a mode selector with a live hint; CSV input
shows an **Infer types** toggle; Java POJO output shows a Lombok toggle. The bar hides
itself when the current conversion has no extra settings. All option values (CSV mode,
row-warning threshold, Lombok, type inference, split orientation) are persisted across
IDE restarts.

### CSV / XML type inference

When CSV or XML is the input format, values that look like integers, decimals, booleans,
or `null` are converted into typed JSON values by default, so `age,30` (or
`<age>30</age>`) becomes `"age": 30` instead of `"age": "30"`. Values with leading zeros
(`007`, `01234`) and integers too large for 64 bits stay strings, so identifiers are
never mangled. Disable the **Infer types** checkbox to keep every value a string.

### CSV export modes

CSV generation supports two expansion modes:

- **`FLAT_FIRST`** — expands only the first container array into rows (every element of
  that array becomes a row: objects are flattened, primitives and nested arrays become
  single-cell rows); later object arrays are serialized into a single JSON string cell.
  Nested objects are flattened with dot notation and primitive arrays are joined into
  comma-separated cells. This is the safe default for nested documents.
- **`CROSS_JOIN`** — performs a full Cartesian product across all object arrays, so
  arrays of sizes s1 × s2 × … × sN produce that many rows. Useful for fully denormalized
  tabular exports, but row counts can explode; conversions estimated to exceed the
  configurable **row warning threshold** (default 1,000) ask for confirmation first. The
  threshold can be adjusted via the **Row warning** spinner that appears in the options
  bar when `CROSS_JOIN` mode is selected.

#### `FLAT_FIRST` example

```json
{
  "customer": "Alice",
  "orders": [
    {"id": "O1", "amount": 100},
    {"id": "O2", "amount": 150}
  ],
  "tags": [
    {"name": "vip"},
    {"name": "priority"}
  ]
}
```

```csv
customer,orders.id,orders.amount,tags
Alice,O1,100,"[{\"name\":\"vip\"},{\"name\":\"priority\"}]"
Alice,O2,150,"[{\"name\":\"vip\"},{\"name\":\"priority\"}]"
```

#### `CROSS_JOIN` example

```json
{
  "env": "prod",
  "databases": [{"host": "db1"}, {"host": "db2"}],
  "tenants": [{"name": "alpha"}, {"name": "beta"}]
}
```

```csv
env,databases.host,tenants.name
prod,db1,alpha
prod,db1,beta
prod,db2,alpha
prod,db2,beta
```

### Java POJO generation

Java POJO output is generated from JSON structure and emits field-only class skeletons,
including `@JsonProperty` annotations where the source key differs from the generated
camelCase field name. Arrays of objects become `List<...>` fields, nested objects become
nested class types, and numbers are mapped to `Integer`, `Long`, `BigInteger`, `Float`,
`Double`, or `BigDecimal` as appropriate. String values in ISO-8601 form are typed as
`LocalDate`, `LocalDateTime`, or `OffsetDateTime` (validated with a real `java.time`
parse, so `2025-13-99` stays a `String`); disable this via the **Detect dates** toggle.
`java.time` imports are emitted only when actually used. The optional **Lombok
annotations** mode annotates every generated class with `@Data`, `@NoArgsConstructor`,
and `@AllArgsConstructor`.

### Protobuf schema generation

The Protobuf converter works structurally in both directions without invoking `protoc`:

- **`protoToJson`** parses proto3-style schemas using brace-depth tracking, so nested
  `message` definitions, `oneof` blocks, and `enum` blocks are handled correctly.
  Fields whose type matches a known message name are resolved to nested JSON objects
  with that message's default structure, rather than producing empty placeholders.
  Fields typed with a known `enum` resolve to the enum's first declared value (the
  proto3 default). `oneof` fields are included alongside regular fields with their
  typed defaults, and duplicate field numbers are rejected across the whole message,
  including `oneof` blocks.
- **`jsonToProto`** walks a JSON tree and emits a proto3 schema with inline nested
  messages and repeated fields.

Malformed Protobuf input fails with targeted validation messages (unbalanced braces,
malformed field statements, duplicate field numbers) instead of being silently skipped.

#### Nested message example

```protobuf
message Outer {
  string name = 1;
  message Inner {
    int32 value = 1;
  }
  Inner inner = 2;
}
```

Produces:

```json
{
  "Outer": {
    "name": "",
    "inner": { "value": 0 }
  }
}
```

#### Oneof example

```protobuf
message Payment {
  string currency = 1;
  oneof payment_method {
    string card_number = 2;
    string bank_account = 3;
  }
}
```

Produces:

```json
{
  "Payment": {
    "currency": "",
    "card_number": "",
    "bank_account": ""
  }
}
```

## Architecture

| Class | Responsibility |
|---|---|
| `ConverterToolWindowFactory` | Registers and mounts the tool-window content. |
| `ConverterPanel` | UI: toolbar, editors, options, find bar, status updates, file I/O. |
| `ConversionPipeline` | UI-independent conversion dispatch: normalize to JSON, render to output, per-format formatting, autoClose repair, XML pretty-printing. |
| `ConversionHistory` | Bounded in-memory history of successful conversions. |
| `ConverterActions` | Keymap-visible IDE actions (Convert, Format, Copy, Open, Save). |
| `ConverterFileOps` | Native IDE file open/save dialogs, async loading, drag-and-drop. |
| `FindBar` | Ctrl+F search bar for the editors. |
| `OpenConverterAction` | Menu action (**Tools → Be Water Converter**) that activates the tool window. |
| `ConverterTheme` | Theme-aware color palette for the UI. |
| `WrapLayout` | Responsive multi-row wrapping for the toolbar and options bar. |
| `JsonXmlConverter` | JSON ↔ XML conversion, element-name sanitization, optional type inference. |
| `JsonYamlConverter` | JSON ↔ YAML conversion, multi-document support. |
| `CsvConverter` | CSV ↔ JSON conversion, flattening logic, row estimation. |
| `TomlConverter` | TOML ↔ JSON conversion. |
| `ProtoConverter` | Protobuf schema ↔ JSON structural conversion with identifier sanitization. |
| `JavaPojoGenerator` | Java class generation from structured JSON, with date detection. |
| `ScalarInference` | Shared string→typed-value inference for CSV and XML input. |

## Development

### Requirements

- Java 21
- IntelliJ Platform Gradle Plugin 2.x (targets IntelliJ IDEA Community 2024.3)

### Running locally

1. Open the project in IntelliJ IDEA.
2. Run `./gradlew runIde` to launch a sandbox IDE.
3. Open the **Be Water** tool window on the right side of the sandbox IDE.
4. Paste sample input, choose source and destination formats, and convert.

### Testing

Run pure JVM unit tests (no IDE sandbox required) with:

```bash
./gradlew unitTest
```

The `check` task runs them automatically. The test suite covers all converter classes with
420+ test cases, including CSV flattening edge cases (empty arrays, nulls, missing fields,
header ordering), type inference, Protobuf validation and sanitization, POJO generation
variants, XXE hardening, and end-to-end cross-format pipeline tests.

### Building a distribution

```bash
./gradlew buildPlugin
```

The installable ZIP is produced in `build/distributions/`. To validate compatibility
against a range of IDE builds before publishing, run `./gradlew verifyPlugin`.

### Continuous integration

Every push and pull request to `master` runs `./gradlew check buildPlugin` on GitHub
Actions ([build.yml](.github/workflows/build.yml)) and uploads the plugin ZIP as a build
artifact.

### Releasing

Pushing a `v*` tag (e.g. `git tag v1.4.0 && git push --tags`) triggers
[release.yml](.github/workflows/release.yml), which runs the tests, verifies IDE
compatibility, publishes the plugin to JetBrains Marketplace, and attaches the ZIP to a
GitHub release. Publishing requires a `PUBLISH_TOKEN` repository secret containing a
[JetBrains Marketplace token](https://plugins.jetbrains.com/author/me/tokens).

## Compatibility

| Property | Value |
|---|---|
| Plugin version | 1.4.0 |
| Minimum IDE build | 243 (IntelliJ IDEA 2024.3) |
| Maximum IDE build | Open-ended |
| Java | 21 |

## Limitations

- Converters are structural rather than semantic: generated Protobuf and Java output is a
  starting point, not a finalized contract or domain model.
- TOML has no null type: JSON `null` values become empty strings (`''`) in TOML output.
  Top-level arrays and scalars are wrapped under an `items` / `value` key, since a TOML
  document must be a table.
- JSON auto-close is intentionally lenient and may repair malformed JSON into a parseable
  shape that differs from the original intent.
- `CROSS_JOIN` CSV exports can grow very quickly with multiple nested arrays; prefer
  `FLAT_FIRST` for general use.

## Roadmap ideas

- Persist conversion history across IDE restarts.
- JSON Schema generation and validation.
- Batch conversion of multiple files.

## License

[Apache License 2.0](LICENSE) — Copyright 2026 Nomikosi Consulting.
