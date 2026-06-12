# Data Format Converter

Data Format Converter is an IntelliJ IDEA plugin that converts data between multiple structured formats inside a tool window, with syntax-highlighted editors, format-aware pretty-printing, and schema/code generation for selected targets. It supports JSON, XML, YAML, CSV, TOML, Protobuf, and Java POJO generation from structured input, all from a single conversion panel. [file:3][file:7][file:4][file:1]

## Overview

The plugin adds a custom IntelliJ tool window and renders a two-pane editor UI for input and output, with toolbar actions for convert, format, swap, copy, and clear. The panel normalizes most inputs through JSON as an internal pivot format, which keeps the individual converters smaller and makes cross-format conversion paths consistent. [file:1][file:3]

The UI is built around `RSyntaxTextArea` editors, dark-theme styling, input/output format badges, and dynamic output-format constraints based on the selected source format. The current implementation allows JSON, XML, YAML, CSV, TOML, and Protobuf as input formats, while Java POJO is available as a generated output target rather than an input format. [file:3]

## Supported conversions

The plugin accepts these input formats: JSON, XML, YAML, CSV, TOML, and Protobuf. Available outputs include JSON, XML, YAML, CSV, TOML, Protobuf, and Java POJO, with the exact destination list filtered from a `VALID_OUTPUTS` map in the UI layer. [file:3]

| Input | Supported outputs |
|---|---|
| JSON | XML, YAML, CSV, TOML, Protobuf, Java POJO [file:3] |
| XML | JSON, YAML, CSV, TOML, Protobuf, Java POJO [file:3] |
| YAML | JSON, XML, CSV, TOML, Protobuf, Java POJO [file:3] |
| CSV | JSON, XML, YAML, TOML, Protobuf, Java POJO [file:3] |
| TOML | JSON, XML, YAML, CSV, Protobuf, Java POJO [file:3] |
| Protobuf | JSON, XML, YAML, CSV, TOML, Java POJO [file:3] |

Most conversions follow a two-step dispatcher flow: input is first normalized to JSON, then JSON is rendered to the requested target format. The dispatcher applies JSON auto-closing only for JSON input before handing the normalized structure to downstream converters. [file:3]

## Features

### Interactive tool window

The plugin is registered through `ConverterToolWindowFactory`, which creates a `ConverterPanel` and mounts it as IntelliJ tool-window content. This keeps the experience embedded inside the IDE rather than opening a separate dialog or editor tab. [file:1]

The panel contains split editors, format selectors, status feedback, syntax highlighting, and one-click actions for conversion and formatting. Output editor syntax mode is updated automatically based on the selected target format. [file:3]

### Format-aware formatting

The **Format** action pretty-prints or canonicalizes the current input for supported formats such as JSON, XML, YAML, and TOML. JSON formatting also uses the panel’s lenient auto-close logic before parsing, which helps recover from truncated input during interactive editing. [file:3]

### Conversion-specific options bar

Whenever the selected output format has extra settings, a dedicated options bar appears below the main toolbar. For CSV output it shows a labeled mode selector with a live hint describing the selected expansion mode; for Java POJO output it shows a Lombok toggle. The bar hides itself entirely when the current conversion has no extra settings. [file:3]

### CSV export modes

CSV generation supports two expansion modes when the output format is CSV: `FLAT_FIRST` and `CROSS_JOIN`. The mode is user-selectable from the options bar whenever CSV is the chosen output, regardless of the input format, so that any source that can be normalized to JSON can share the same CSV flattening rules. [file:3]

Before a `CROSS_JOIN` conversion runs, the panel estimates the number of rows the Cartesian product would produce (without materializing them). If the estimate exceeds 1,000 rows, a warning dialog asks for confirmation before converting, and the status bar reports the row count after every CSV conversion. [file:3][file:8]

`FLAT_FIRST` expands only the first array-of-objects into rows and serializes later object arrays into a single JSON string cell, which is safer for wide or deeply nested documents. `CROSS_JOIN` performs a full Cartesian product across object arrays, producing every row combination and making it better suited for fully denormalized tabular exports. [file:8]

### Java POJO generation

Java POJO output is generated from JSON structure and emits field-only class skeletons, including `@JsonProperty` annotations where the original source key differs from the generated camelCase Java field name. Arrays of objects become `List<...>` fields, nested objects become nested class types, and number handling distinguishes common numeric categories such as `Integer`, `Long`, `Float`, `Double`, and `BigDecimal`. [file:7]

The generator unwraps a top-level array by using the first element as the representative schema and rejects empty arrays with a descriptive error. Constructors and accessors are not emitted by default; enabling the **Lombok annotations** option in the options bar annotates every generated class with `@Data`, `@NoArgsConstructor`, and `@AllArgsConstructor` and adds the matching `lombok.*` imports. [file:7][file:3]

### Protobuf schema generation

The Protobuf converter supports both directions at a structural level without invoking `protoc`. `protoToJson` parses proto3-style message blocks into a JSON representation with typed defaults, while `jsonToProto` walks a JSON tree and emits a proto3 schema, including nested message collection and repeated fields for arrays. [file:4]

Malformed Protobuf input now fails with targeted validation messages instead of being silently skipped: empty input, unbalanced braces (with open/close counts), malformed field statements (reported with the enclosing message name and the offending statement), and duplicate field numbers within a message all produce descriptive errors. Block comments, `option`/`reserved`/`import` statements, dotted types such as `google.protobuf.Timestamp`, `map<k, v>` fields, and `oneof`/nested-message blocks remain accepted. [file:4]

## CSV modes explained

When exporting nested JSON-like data to CSV, the hardest problem is deciding how arrays of objects should become rows. The plugin’s CSV mode concept addresses this explicitly so users can choose between conservative flattening and full denormalization. [file:8]

### `FLAT_FIRST`

Use `FLAT_FIRST` when one array is the main record list and all other nested arrays should remain attached to each row as compact JSON payloads. In this mode, nested objects are flattened using dot notation, primitive arrays are joined into comma-separated cells, and only the first object-array field contributes extra rows. [file:8]

Example input:

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

Representative output shape:

```csv
customer,orders.id,orders.amount,tags
Alice,O1,100,"[{\"name\":\"vip\"},{\"name\":\"priority\"}]"
Alice,O2,150,"[{\"name\":\"vip\"},{\"name\":\"priority\"}]"
```

This mode avoids row explosion and is usually the safer default for interactive CSV exports from arbitrary nested documents. [file:8]

### `CROSS_JOIN`

Use `CROSS_JOIN` when every array-of-objects should participate in the final row set. The converter recursively expands object arrays and uses a Cartesian product merge so that arrays of sizes `s1`, `s2`, …, `sN` produce `s1 × s2 × … × sN` rows. [file:8]

Example input:

```json
{
  "env": "prod",
  "databases": [{"host": "db1"}, {"host": "db2"}],
  "tenants": [{"name": "alpha"}, {"name": "beta"}]
}
```

Representative output shape:

```csv
env,databases.host,tenants.name
prod,db1,alpha
prod,db1,beta
prod,db2,alpha
prod,db2,beta
```

This mode is useful for analytics-oriented exports, but it can grow very quickly when several arrays are present. [file:8]

## Architecture

### Tool window and UI

`ConverterToolWindowFactory` is the IntelliJ entry point and simply instantiates `ConverterPanel` into the tool window content manager. Most application behavior lives in `ConverterPanel`, including the toolbar, editor construction, syntax highlighting, conversion dispatch, formatting actions, clipboard support, and input/output swapping. [file:1][file:3]

`ConverterPanel` also controls which output formats are allowed for each input format through a central `VALID_OUTPUTS` map. That makes the UI logic explicit and avoids presenting unsupported combinations to the user. [file:3]

### Central conversion flow

The panel’s `dispatch(...)` method acts as the orchestration layer. It first repairs incomplete JSON input via `autoClose(...)` when the source format is JSON, then converts the input into a JSON string, and finally emits the selected target format through the appropriate converter class. [file:3]

This design means XML, YAML, CSV, TOML, and Protobuf converters do not all need pairwise knowledge of each other. Instead, each converter only needs to know how to translate to and from JSON or generate its specialized target representation. [file:3][file:4][file:7][file:8]

### Core classes

| Class | Responsibility |
|---|---|
| `ConverterToolWindowFactory` | Registers and mounts the tool-window content. [file:1] |
| `ConverterPanel` | UI, toolbar actions, dispatch, formatting, syntax handling, status updates, auto-close logic. [file:3] |
| `JsonXmlConverter` | JSON  XML conversion. [file:2] |
| `JsonYamlConverter` | JSON  YAML conversion. [file:6] |
| `CsvConverter` | CSV  JSON conversion and CSV export flattening logic. [file:8] |
| `TomlConverter` | TOML  JSON conversion. [file:5] |
| `ProtoConverter` | Protobuf schema  JSON structural conversion. [file:4] |
| `JavaPojoGenerator` | Java class generation from structured JSON or XML-derived JSON. [file:7] |

## Installation and development

### Build requirements

The Gradle configuration targets the IntelliJ Platform Gradle Plugin 2.x line and IntelliJ IDEA Community 2024.3.5 for local platform resolution, which is a valid target notation in that plugin DSL. The project is configured for Java 17, which matches modern IntelliJ Platform plugin requirements for the 2024.3 generation. [web:45][web:42]

If the build file still mixes Jackson `2.21.1` and `2.18.2` artifacts, those dependencies should be aligned to a single Jackson version or managed through `jackson-bom` to avoid runtime incompatibilities between Jackson modules. Using the IntelliJ Platform Gradle Plugin version `2.16.0` instead of `2.2.1` is also advisable because newer 2.x releases include important fixes and compatibility improvements. [web:49][web:41][web:30]

### Running locally

Typical local development flow:

1. Open the project in IntelliJ IDEA.
2. Run the Gradle `runIde` task to launch a sandbox IDE instance.
3. Open the Data Format Converter tool window in the sandbox IDE.
4. Paste sample input, choose source and destination formats, and run conversions.

The project also defines a dedicated `unitTest` source set and task for pure JVM tests that do not require a full IDE sandbox. This is useful for converter logic such as CSV expansion, schema generation, and normalization behavior. [file:3]

## Testing strategy

The converter code benefits from two complementary test layers:

- Pure unit tests for converter classes, especially `CsvConverter`, `ProtoConverter`, and `JavaPojoGenerator` behavior.
- Plugin/UI tests for toolbar interactions, selector visibility, and end-to-end conversion behavior inside the IntelliJ environment.

For CSV mode coverage in particular, useful test categories include flat scalar input, single object-array expansion, multiple object-array Cartesian products, empty arrays, null handling, missing fields, mixed nested objects, and deterministic header ordering. Those scenarios are especially important because CSV flattening semantics are where users are most likely to notice edge-case regressions. [file:8]

## Usage examples

### JSON to YAML

Select **From: JSON** and **To: YAML**, paste JSON into the left editor, then click **Convert**. The dispatcher keeps JSON as the intermediate format and forwards it to the YAML converter for output rendering. [file:3][file:6]

### XML to Java POJO

Select **From: XML** and **To: Java POJO**, then convert. The XML is first normalized to JSON and then passed into the Java POJO generator, which produces field declarations and nested classes based on the inferred structure. [file:3][file:7]

### JSON to CSV with nested arrays

Select **From: JSON** and **To: CSV**, choose either `FLAT_FIRST` or `CROSS_JOIN`, and then convert nested content. `FLAT_FIRST` is better for controlled row growth, while `CROSS_JOIN` is better when every combination of nested records must become a distinct CSV row. [file:8][file:3]

## Limitations

The current converters are structural rather than semantic, so generated Protobuf and Java output should be treated as a starting point instead of a finalized contract or domain model. For example, `jsonToProto` infers field types from representative JSON values, and `JavaPojoGenerator` emits only field declarations without methods or validation logic. [file:4][file:7]

JSON auto-close is intentionally lenient and is only applied to raw JSON input in the UI dispatcher. That is helpful for interactive editing, but it also means malformed JSON may sometimes be repaired into a parseable shape that differs from the user’s original intent. [file:3]

CSV flattening can also cause very large outputs under `CROSS_JOIN`, especially when multiple nested arrays are present. In practice, `FLAT_FIRST` is the safer default for general-purpose IDE usage, while `CROSS_JOIN` should be used when full denormalization is specifically desired. [file:8]

## Roadmap ideas

The previous roadmap items are now implemented: richer CSV mode controls live in a dedicated options bar with a per-mode hint, `CROSS_JOIN` conversions show a preview warning when the estimated row count exceeds 1,000, malformed Protobuf input fails with precise validation messages, Java POJO generation has an optional Lombok mode, and conversion-specific settings have explicit UI affordances that appear only when relevant. [file:3][file:4][file:7][file:8]

Potential next improvements include exporting conversion results directly to a file, remembering option selections across IDE restarts, configurable row-warning thresholds, and Protobuf parsing that fully understands nested message and `oneof` blocks instead of treating them leniently. [file:3][file:4]
