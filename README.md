# Data Format Converter

A lightweight **IntelliJ IDEA plugin** that provides bidirectional conversion between the most common data serialisation formats, all inside a dedicated Tool Window with syntax-highlighted editors.

---

## Features

- **7 formats supported** — convert between any combination of:

  | Format | Read | Write |
  |---|:---:|:---:|
  | JSON | ✅ | ✅ |
  | XML | ✅ | ✅ |
  | YAML | ✅ | ✅ |
  | CSV | ✅ | ✅ |
  | TOML | ✅ | ✅ |
  | Protobuf (`.proto`) | ✅ | ✅ |
  | Java POJO | — | ✅ |

- **Syntax highlighting** — both input and output editors use RSyntaxTextArea with a dark theme and language-aware colouring.
- **Format button** — pretty-prints / normalises the input in place.
- **Swap button** — exchanges input and output content and format in one click.
- **Copy button** — copies the output to clipboard.
- **Clear button** — resets both editors to a clean state.
- **autoClose** — automatically repairs truncated JSON input (unclosed `{` or `[` brackets) before conversion.
- **Status bar** — shows a green ✓ on success or a red ✗ with the error message on failure.
- **Java POJO generation** — infers field types (`Integer`, `Long`, `Double`, `Boolean`, `String`, `List<T>`, nested classes), emits `@JsonProperty` for renamed (snake_case / kebab-case) fields, and generates `toString()`, `equals()`, and `hashCode()` automatically.

---

## Supported Conversion Paths

Every input format can be converted to every output format via an internal **JSON hub**:

```
Input → JSON (hub) → Output
```

| From \ To | JSON | XML | YAML | CSV | TOML | Protobuf | Java POJO |
|---|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| **JSON** | — | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **XML** | ✅ | — | ✅ | ✅ | ✅ | ✅ | ✅ |
| **YAML** | ✅ | ✅ | — | ✅ | ✅ | ✅ | ✅ |
| **CSV** | ✅ | ✅ | ✅ | — | ✅ | ✅ | ✅ |
| **TOML** | ✅ | ✅ | ✅ | ✅ | — | ✅ | ✅ |
| **Protobuf** | ✅ | ✅ | ✅ | ✅ | ✅ | — | ✅ |

---

## Installation

### From source

1. Clone the repository:
   ```bash
   git clone https://github.com/your-username/data-format-converter.git
   cd data-format-converter
   ```

2. Build the plugin JAR:
   ```bash
   ./gradlew clean buildPlugin
   ```

3. In IntelliJ IDEA, go to **Settings → Plugins → ⚙ → Install Plugin from Disk…** and select the generated `.zip` from `build/distributions/`.

4. Restart the IDE. The **DataConverter** tool window will appear in the right-hand side panel.

### Requirements

- IntelliJ IDEA 2023.1 or later (Community or Ultimate)
- JDK 17+

---

## Usage

1. Open the **DataConverter** tool window from the right side panel.
2. Select the **input format** from the *From* dropdown.
3. Paste or type your input in the left editor.
4. Select the **output format** from the *To* dropdown.
5. Click **Convert**. The result appears in the right editor.

### Toolbar buttons

| Button | Description |
|---|---|
| **Convert** | Runs the conversion |
| **Format** | Pretty-prints the current input in place |
| **Swap** | Swaps input ↔ output content and formats |
| **Copy** | Copies output to clipboard |
| **Clear** | Resets both editors |

---

## Project Structure

```
src/
├── main/
│   ├── java/com/converter/
│   │   ├── ConverterPanel.java              # UI, toolbar, autoClose, dispatch hub
│   │   ├── ConverterToolWindowFactory.java  # IntelliJ Tool Window entry point
│   │   └── converter/
│   │       ├── JsonXmlConverter.java
│   │       ├── JsonYamlConverter.java
│   │       ├── CsvConverter.java
│   │       ├── TomlConverter.java
│   │       ├── ProtoConverter.java
│   │       └── JavaPojoGenerator.java
│   └── resources/META-INF/
│       └── plugin.xml
└── test/
    └── java/com/converter/converter/
        ├── JsonXmlConverterTest.java
        ├── JsonXmlConverterEdgeCaseTest.java
        ├── JsonYamlConverterTest.java
        ├── JsonYamlConverterEdgeCaseTest.java
        ├── CsvConverterTest.java
        ├── TomlConverterTest.java
        ├── TomlConverterEdgeCaseTest.java
        ├── ProtoConverterTest.java
        ├── ProtoConverterEdgeCaseTest.java
        ├── JavaPojoGeneratorTest.java
        ├── JavaPojoGeneratorEdgeCaseTest.java
        └── ConverterPipelineEdgeCaseTest.java
```

---

## Dependencies

| Dependency | Version | Scope |
|---|---|---|
| `com.fasterxml.jackson.core:jackson-databind` | `2.18.6` | `implementation` |
| `com.fasterxml.jackson.dataformat:jackson-dataformat-xml` | `2.18.6` | `implementation` |
| `com.fasterxml.jackson.dataformat:jackson-dataformat-yaml` | `2.18.6` | `implementation` |
| `com.fasterxml.jackson.dataformat:jackson-dataformat-csv` | `2.18.6` | `implementation` |
| `com.fasterxml.jackson.dataformat:jackson-dataformat-toml` | `2.18.6` | `implementation` |
| `org.junit.jupiter:junit-jupiter-api` | `5.10.2` | `testImplementation` |
| `org.assertj:assertj-core` | `3.27.7` | `testImplementation` |

---

## Running Tests

```bash
./gradlew clean test
```

Test results are written to `build/reports/tests/test/index.html`.

To always re-run all tests regardless of cache:
```bash
./gradlew clean test --rerun-tasks
```

---

## License

MIT License — see [LICENSE](LICENSE) for details.
