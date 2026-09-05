<div align="center">
  <img src="docs/logo.png" alt="Be Water Converter logo" width="220"/>

  # Be Water Converter

  *Be water, my friend — let your data flow between formats.*

  An IntelliJ IDEA plugin that converts data between JSON, XML, YAML, CSV, TOML and
  Protobuf, and generates Java POJOs, Kotlin data classes and JSON Schema — all inside a
  syntax-highlighted tool window.

  [![Build](https://github.com/nomikosi/be-water-converter/actions/workflows/build.yml/badge.svg)](https://github.com/nomikosi/be-water-converter/actions/workflows/build.yml)
  [![Java 21](https://img.shields.io/badge/Java-21-blue)](https://openjdk.org/projects/jdk/21/)
  [![IntelliJ 2025.1+](https://img.shields.io/badge/IntelliJ-2025.1%2B-purple)](https://plugins.jetbrains.com/)
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
| JSON | XML, YAML, CSV, TOML, Protobuf, Java POJO, Kotlin, JSON Schema |
| XML | JSON, YAML, CSV, TOML, Protobuf, Java POJO, Kotlin, JSON Schema |
| YAML | JSON, XML, CSV, TOML, Protobuf, Java POJO, Kotlin, JSON Schema |
| CSV | JSON, XML, YAML, TOML, Protobuf, Java POJO, Kotlin, JSON Schema |
| TOML | JSON, XML, YAML, CSV, Protobuf, Java POJO, Kotlin, JSON Schema |
| Protobuf | JSON, XML, YAML, CSV, TOML, Java POJO, Kotlin, JSON Schema |

`Java POJO`, `Kotlin` and `JSON Schema` are output-only: none is accepted as an input
format, so **Swap** refuses to move them to the input side.

Most conversions follow a two-step flow: input is first normalized to JSON, then JSON is
rendered to the requested target format. JSON input is parsed leniently — comments,
trailing commas, single quotes, and unquoted field names are accepted — and additionally
passes through an auto-close step that repairs unclosed `{` / `[` brackets and
unterminated strings before parsing. Multi-document YAML (`---`-separated, e.g.
Kubernetes manifests) converts to a JSON array with one element per document. YAML is read
by the 1.2 core schema: `yes`, `no`, `on` and `off` are text (so a GitHub Actions `on:` key
stays `on`), `12:30:00` and `0777` are text, floats keep the digits they were written with,
and anchors and merge keys are expanded in place. A document whose aliases would expand to
more than two million values, or whose anchors form a cycle, is refused rather than
converted. JSON keys that are not valid XML element names or Protobuf identifiers (spaces,
kebab-case, leading digits) are sanitized when rendering to those formats, so the output is
always well-formed.

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
file extension (`.json`, `.xml`, `.yaml`/`.yml`, `.csv`, `.toml`, `.proto`). If the file is
open in an editor with unsaved changes, the editor's text is loaded rather than the stale
copy on disk. **Save** writes the current output to disk using the appropriate format
extension and refreshes the file in the IDE's virtual file system, so a file saved into the
project shows up straight away.

You can also **drag and drop** a file directly onto the input editor. The file is loaded
and the source format is auto-detected from the extension, just like the Open action.

### Format-aware formatting

The **Format** action pretty-prints or canonicalizes the current input for JSON, XML,
YAML, TOML and CSV. JSON formatting also applies the lenient auto-close logic, which helps
recover truncated input during interactive editing.

Format is a layout action and is held to that. CSV is rewritten row by row without ever
being parsed into objects, so headers, ragged rows and cell text come back exactly as
written. YAML keeps one document per document, including a `---`-prefixed single document.
Numbers keep the digits they were written with: `1.10` stays `1.10` in JSON, YAML and TOML
alike. If the input is edited while Format is still running, the result is discarded rather
than written over the newer text.

Where a re-layout could only be done by changing what the document says, Format refuses and
leaves the editor alone: TOML dates, hex/octal/binary and underscore-separated numbers and
`inf`/`nan` (the JSON step in between cannot spell them); YAML `.inf`/`.nan`; and XML elements
that mix text with child elements, which the indenter cannot pretty-print without inserting
whitespace into the text. Formats that pass through the JSON tree keep only the data, so
before YAML, TOML or JSON-with-comments is rewritten, Format says how many comments (and YAML
anchors) would be dropped and asks first.

### Conversion-specific options bar

An options bar sits below the toolbar. **Sort keys** applies to every conversion and is
always shown; the format-specific groups appear only when they are relevant. CSV output
shows a mode selector with a live hint; CSV on either side shows a **Delimiter** selector;
CSV or XML input shows an **Infer types** toggle; Java POJO output shows Lombok and date
toggles. The row-warning threshold applies to both CSV modes. All option values (CSV mode, delimiter, row-warning threshold, Lombok, type
inference, date detection, sort keys, split orientation) are persisted across IDE restarts.

### CSV delimiter

Comma, semicolon, and tab are supported for both reading and writing. Semicolon-separated
CSV is the norm across much of Europe, where the comma is the decimal separator; before
this option such a file parsed as a single column. The delimiter applies to whichever side
of the conversion is CSV.

### Automatic format detection

Pasting or dropping content into the input editor sets the input format automatically:
JSON, XML, YAML, TOML, Protobuf, and CSV are recognized from the content itself. A leading
`[` is genuinely ambiguous — it opens both a JSON array and a TOML `[table]` header — so
detection looks for a following `key = value` line before deciding. Detection only ever
fires on a paste-sized insertion, never on typing, and stays silent when it cannot tell,
so it will not fight a format you selected yourself.

For CSV the delimiter is detected as well — comma, semicolon or tab — and the **Delimiter**
option is switched to match, both on paste and when a `.csv` file is opened. Context-menu
conversions sniff the delimiter the same way, so a semicolon file is never read as one wide
column because the option still said comma.

### Sort keys

**Sort keys** orders object keys alphabetically throughout the document, leaving array
order untouched. Two documents carrying the same data in different key orders canonicalize
to the same output, which makes conversions diffable across runs and across sources. It is
applied to the internal JSON pivot, so every *conversion* target inherits the ordering.

The **Format** action sorts JSON, YAML and TOML, each of which already passes through the
JSON tree, and leaves XML and CSV in document order rather than round-tripping them through
JSON to reorder them.

### Using it from the editor and Project view

Right-click a file in the Project view, or a selection in any editor:

- **Open in Be Water Converter** loads the selection, the editor's contents, or the file into
  the tool window, with the format resolved from the extension where there is one and from
  the content otherwise.
- **Convert with Be Water → <format>** converts straight to the chosen format and opens the
  result as a scratch file, so it lands in a real IDE editor rather than the plugin's pane.

Both entries are hidden when the context is not something the plugin can read. Files are
read and converted on a background task with a cancellable progress indicator, so a large
file never blocks the UI.

### Subtree filter

The **Filter** box converts only part of a document. Paths are
[RFC 6901](https://datatracker.ietf.org/doc/html/rfc6901) JSON Pointers, with a dotted
convenience syntax on top — `/users/0/name` and `users[0].name` select the same node, and a
leading `$` (as JSONPath spells the root) is accepted so paths copied from other tools work.
Bracket-quoting reaches keys that contain dots or slashes: `['odd.key'].a`. A path that
matches nothing is reported as an error rather than silently converting an empty document.
This is deliberately selection only, not a query language, and adds no dependency.

### Open in editor

The output pane is a lightweight embedded editor, not an IDE editor. The **Open in editor**
toolbar button writes the current output to a scratch file and opens it, which gives you the
IDE's own highlighting, folding, keymap and Save As without leaving the workflow. Context-menu
conversions (*Convert with Be Water*) already land there directly.

### Compare

The **Compare** toolbar button opens the IDE's diff viewer on the two editors. Each side is
first normalised to canonical JSON — converted to the pivot format and key-sorted — so two
documents carrying the same data in different formats or different key orders compare as
identical, and only genuine differences appear. The status bar says so explicitly when the
two sides are equivalent.

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
  threshold can be adjusted via the **Row warning** spinner in the options bar, which is
  shown for both CSV modes since the warning applies to both.

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
Imports are emitted only when actually used. The optional **Lombok
annotations** mode annotates every generated class with `@Data`, `@NoArgsConstructor`,
and `@AllArgsConstructor`.

All classes are emitted into a single block that pastes into one `.java` file, so only
the root class is declared `public` — Java permits at most one public top-level type per
file. Two nested objects that would claim the same class name each get their own class
(`User`, `User2`) rather than sharing the first one's fields.

Arrays are typed from every element, not just the first: objects in an array contribute the
union of their keys to one class, numbers widen to the widest kind seen (`[1, 2.5]` is a
`List<Double>`), two dates of the same kind stay that kind, and elements of genuinely
different kinds fall back to `List<Object>`. The test suite compiles the generated Java
with `javac` rather than only checking for substrings.

### JSON Schema generation

`JSON Schema` output infers a [draft 2020-12](https://json-schema.org/draft/2020-12/schema)
schema from the input document. Every key that appears in the example is listed as
`required` — an example can only show what *is* present, never what is optional. Array
elements are merged rather than sampled, so a heterogeneous array yields an `anyOf` of the
distinct element schemas instead of silently adopting the first element's shape; an empty
array places no constraint on its items. Integral numbers map to `integer` and other
numbers to `number`.

### Kotlin data class generation

`Kotlin` output emits `data class` declarations with `val` properties. Structure discovery
is shared with the Java generator, so nested objects, arrays of objects and name collisions
behave identically — two differently-shaped objects that would claim the same name get
`User` and `User2` in both languages.

Kotlin differs in three ways that the output reflects. Every class is top-level and public,
because Kotlin allows several top-level declarations per file. An object with no properties
becomes a plain `class` rather than a `data class`, since a data class must declare at least
one parameter. And a value that appeared as `null` in the example is typed `Any?` — the one
case where an example positively demonstrates nullability; everything else is non-null,
because an example can only show what *was* present. Arrays of objects are merged the same
way as for Java, and here the merge can say more: a key that some element lacked, or held
as `null`, is typed nullable (`Int?`), an array with a `null` element becomes
`List<Int?>`, and a key present in every element stays non-null. An object with more
properties than a primary constructor can take is emitted with a note saying so.

Hard keywords (`when`, `class`, `is`, `fun`, …) are renamed with a `Value` suffix and mapped
back with `@JsonProperty`; soft keywords such as `data`, `value` and `sealed` are legal
property names and are left alone. The **Detect dates** toggle applies here too.

Deserializing the result needs [`jackson-module-kotlin`](https://github.com/FasterXML/jackson-module-kotlin),
the way the Lombok mode needs Lombok on the classpath. A data class has no no-argument
constructor, and `@JsonProperty` on a constructor `val` binds to the constructor
*parameter* — only that module reads either, so plain `jackson-databind` cannot construct
the generated classes whatever the annotation placement.

### Protobuf schema generation

The Protobuf converter works structurally in both directions without invoking `protoc`:

- **`protoToJson`** parses proto3-style schemas using brace-depth tracking, so nested
  `message` definitions, `oneof` blocks, and `enum` blocks are handled correctly.
  Fields whose type matches a known message name are resolved to nested JSON objects
  with that message's default structure, rather than producing empty placeholders.
  Fields typed with a known `enum` resolve to the enum's first declared value (the
  proto3 default). `oneof` fields are included alongside regular fields with their
  typed defaults, and duplicate field numbers are rejected across the whole message,
  including `oneof` blocks. Type names are resolved the way `protoc` resolves them: a
  message's own nested types first, then its enclosing messages', then the file's top
  level, so two messages can each declare their own `Inner` or `Status`; dotted references
  such as `Outer.Inner`, with or without a package prefix, descend the same way.
- **`jsonToProto`** walks a JSON tree and emits a proto3 schema with inline nested
  messages and repeated fields. A repeated message is typed from every element of the
  array. A key the field name cannot spell (`first-name`, `1st`, or two keys that sanitize
  to the same name) keeps its original key through a `json_name` option, which is how
  proto3's JSON mapping reads it back.

Malformed Protobuf input fails with targeted validation messages (unbalanced braces,
malformed field statements, duplicate field numbers, field numbers outside protoc's rules:
`0`, `19000`–`19999`, above `536870911`) instead of being silently skipped.

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
| `ConverterWidgets` | Custom-painted toolbar controls (buttons, combos, format badges). |
| `ConversionPipeline` | UI-independent conversion dispatch: normalize to JSON, render to output, per-format formatting and its refusals, autoClose repair, XML pretty-printing, format and delimiter detection. |
| `ConversionOptions` | Immutable per-conversion settings: CSV mode and delimiter, Lombok, date detection, type inference, key sorting, subtree filter. |
| `PivotJson` | Reader settings that carry every number through the JSON pivot unchanged. |
| `JsonPathFilter` | JSON Pointer and dotted-path subtree selection. |
| `ConversionHistory` | Bounded in-memory history of successful conversions. |
| `ConversionFileNames` | Extension and file-name rules for conversion results. |
| `ConverterActions` | Keymap-visible IDE actions (Convert, Format, Copy, Open, Save). |
| `ConverterContextActions` | Editor and Project-view context menu: open in the tool window, or convert straight to a scratch file. |
| `ConverterScratchFiles` | Opens results as scratch files in a real IDE editor. |
| `ConverterDiff` | Opens the IDE diff viewer on two canonical-JSON documents (Compare). |
| `ConverterFileOps` | Native IDE file open/save dialogs, async loading, drag-and-drop, VFS refresh. |
| `FindBar` | Ctrl+F search bar for the editors. |
| `OpenConverterAction` | Menu action (**Tools → Be Water Converter**) that activates the tool window. |
| `ConverterTheme` | Theme-aware color palette for the UI. |
| `WrapLayout` | Responsive multi-row wrapping for the toolbar and options bar. |
| `JsonXmlConverter` | JSON ↔ XML conversion, element-name sanitization, optional type inference. |
| `JsonYamlConverter` | JSON ↔ YAML conversion, multi-document support, exact floats, resolver-aware quoting. |
| `CsvConverter` | CSV ↔ JSON conversion, positional re-layout, flattening logic, row estimation. |
| `TomlConverter` | TOML ↔ JSON conversion. |
| `ProtoConverter` | Protobuf schema ↔ JSON structural conversion with scoped type resolution and identifier sanitization. |
| `JavaPojoGenerator` | Java class generation from structured JSON, with date detection. |
| `KotlinDataClassGenerator` | Kotlin data class generation from structured JSON. |
| `JsonSchemaGenerator` | JSON Schema (draft 2020-12) inference. |
| `StructureModel` / `SourceConventions` | Type discovery and identifier rules shared by the Java and Kotlin generators. |
| `ArrayShapes` | The merged shape of an array's elements, for the generators and the Protobuf writer. |
| `ScalarInference` | Shared string→typed-value inference for CSV and XML input. |

## Development

### Requirements

- Java 21
- IntelliJ Platform Gradle Plugin 2.x (targets IntelliJ IDEA Community 2025.1)

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

The `check` task runs the full platform-aware `test` task, a superset of these. The test
suite covers all converter classes with 650+ test cases, including CSV flattening edge cases
(empty arrays, nulls, missing fields, header ordering), type inference, Protobuf validation,
scoping and sanitization, POJO generation variants, numeric and format fidelity, XXE
hardening, and end-to-end cross-format pipeline tests.

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
| Plugin version | 1.5.0 |
| Minimum IDE build | 251 (IntelliJ IDEA 2025.1) |
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
- JSON has no infinity or NaN, so YAML `.inf`/`.nan` and TOML `inf`/`nan` convert to the
  strings `"Infinity"` and `"NaN"`; Format refuses to write those back.
- Formats that pass through the JSON tree (YAML, TOML, JSON with comments) keep only the
  data: Format drops comments, and expands YAML anchors and merge keys in place. It asks
  before doing so.
- XML Format writes attributes in alphabetical order, which XML treats as insignificant
  but which does change the text of an element that listed them differently.

## Roadmap ideas

- Persist conversion history across IDE restarts.
- JSON Schema validation.
- Batch conversion of multiple files.

## License

[Apache License 2.0](LICENSE) — Copyright 2026 Nomikosi Consulting.
