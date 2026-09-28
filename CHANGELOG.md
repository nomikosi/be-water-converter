# Changelog

What changed in each release of Be Water Converter, newest first. The change notes on the
JetBrains Marketplace and in the IDE's plugin manager are built from this file, so the build
fails until the version in `build.gradle` has a section here.

## [1.5.3]

- **Format keeps numbers as written** — JSON Format keeps each number's spelling, so `1.5e1`,
  `1.0e2`, `-0.0` and `1e400` stay as they are, with sorted keys too. YAML and TOML Format
  refuse a number they would write back differently, such as `1.5e1` as `15` (a float turned
  into an integer) or YAML's `0x1F` as `31`, and say what it would become.
- **XML Format writes the document as it was written** — it re-indents only elements that hold
  nothing but other elements. Mixed content such as `<p>Hello <b>world</b></p>`,
  `xml:space="preserve"` elements, CDATA sections and elements holding only a comment are
  written as they are, instead of being refused or re-indented into a different value, and
  entity references an external DTD declares, such as XHTML's `&nbsp;` and `&copy;`, are no
  longer deleted from text; the parser drops one inside an attribute value, so Format refuses
  such a document instead. The declaration keeps its `standalone`, a document without one gets
  none, each comment or processing instruction before the root keeps its own line, and a
  document nested more than 1,000 levels deep is refused, as conversion refuses it. XML 1.1
  keeps its control characters, NEL and line separator as character references, and text of
  Unicode spaces between elements is no longer taken for indentation. In XHTML, told by its
  DOCTYPE or by its namespace as XHTML5 writes it, only HTML's void elements, such as `<br />`,
  are minimized: an empty `<script src="a.js"></script>` was written as
  `<script src="a.js" />`, which an HTML parser reads as an opening tag.
- **XML keeps text, names, nulls and whitespace** — a document that declares a non-UTF-8
  encoding is no longer decoded twice (`José` read as `JosÃ©`, and UTF-16 failed). JSON `null`
  is written as `xsi:nil="true"` and reads back as `null` rather than `""`. Element names
  follow XML's naming rules, so a key such as `latency_µs` no longer produces XML the plugin
  then refused to read, and letters beyond the Basic Multilingual Plane are kept. A key that is
  already a valid name keeps it and only renamed keys are numbered, so `first_name` is no
  longer written as `first_name_2` because `first name` came first. Format keeps whitespace
  that is an element's whole value.
- **CSV keeps cells and rows as written** — the first cell of each row keeps its leading
  spaces, as the other cells always did: Format no longer strips them, and type inference no
  longer reads a space-padded first cell as a number. A row whose only cell is empty is written
  quoted and survives the round trip, and a row of empty cells (`,,`) is kept instead of being
  dropped as blank. Delimiter detection picks the delimiter that splits the header and the
  first rows into the most columns, reading a quoted cell that spans lines as one cell, so a
  tab-separated file whose values contain commas is read as tab-separated. `.tsv` files open as
  tab-separated CSV.
- **Format detection reads real documents** — CSV whose rows hold `Re:` or `Fwd:` is CSV, not
  YAML; YAML carrying a `.proto`, as a Kubernetes ConfigMap does, is YAML; a Protobuf editions
  file (`edition = "2023";`) is Protobuf while Cargo.toml's `edition = "2021"` stays TOML; and
  a TOML table header may be followed by a comment.
- **Files open in the encoding the IDE has for them** — Open and the context-menu actions read
  a file by its byte-order mark, then by its encoding in the IDE (File Encodings,
  `.editorconfig`), then as UTF-8, then as Windows-1252. A UTF-16 file no longer opens as
  NUL-interleaved text, and a Windows-1252 file keeps its `€` and curly quotes. The status bar
  names the encoding when it was not UTF-8.
- **YAML reads `0o` octal and deep documents, and writes for YAML 1.1 readers** — `0o17` is the
  number 15, as YAML 1.2 writes octal; it was read as text. Documents may nest 500 levels deep,
  where anything past 50 was refused, including YAML the plugin had just written. Strings that
  YAML 1.1 readers such as PyYAML and docker-compose take for numbers or dates, like `22:22`
  and `2024-01-01`, are written quoted, so Format no longer strips the quotes a docker-compose
  file put around its ports.
- **Type inference leaves hash-like text alone** — a digit run with an `e` in it, such as the
  short git hash `1234e56`, stays text; `1e3` and `1.5e3` are still numbers.
- **The subtree filter reads `$` as the root only before `.`, `[` or the end** — keys such as
  `$id`, and paths such as `$defs.Address` in the plugin's own JSON Schema output, select what
  they name.
- **Protobuf reads options, numbers, extensions and proto2 as `protoc` does** — a field whose
  options hold a list or a message, as protovalidate writes them, is no longer dropped; an
  `extend` block inside a message no longer adds fields to it; `map<string,string>labels`, hex
  field numbers and octal numbers such as `010` read as `protoc` reads them. proto2 schemas
  read too: a group is a nested message and a field named after it, `[default = …]` values are
  the values the fields start with, `extensions` ranges and an `edition` line are declarations
  rather than errors, and an aggregate option may separate its fields with `;`. An escape such
  as `\303\251` reads as the UTF-8 bytes it spells, `é`. A schema that would expand to more
  than two million values, or that nests more than 1,000 levels deep, is refused instead of
  exhausting memory or the stack, and Format no longer stalls on a line with a long run of
  spaces.
- **Generated Protobuf is accepted by `protoc`** — keys such as `user_id` and `userId`, or
  `name` and `Name`, become distinct field names, and a field whose JSON name would clash
  carries its key as `json_name`, so every key still reads back as itself. Values of mixed
  kinds, and lists holding `null`, are `google.protobuf.Value`, with its import, instead of
  `repeated string`, which proto3's JSON mapping cannot read them back into; a key such as
  `[id]`, a form `protoc` reserves for extensions, is refused with a message instead of being
  emitted.
- **Generated Java and Kotlin compile and read their own JSON** — Lombok classes with a `$ref`
  key compile; classes with keys such as `xAxis`, `eTag`, or both `id` and `ID`, bind through
  their fields with `@JsonAutoDetect`; and class names are unique ignoring case, so objects
  under `url` and `URL` no longer compile to the same class file on Windows and macOS. An empty
  object is a `Map<String, Object>` (`Map<String, Any?>` in Kotlin) instead of an empty class
  that failed on whatever the real data held there. A Lombok class with more than 1,000 fields
  uses `@Getter` and `@Setter`, since `@Data`'s `equals` is too large for javac, and one with
  more than 500 leaves its fields out of `toString`, which javac cannot compile.
- **Compare shows plain numbers** — whole numbers are written out (`30`, `12000`) instead of
  `3E+1` and `1.2E+4`, and equal values still compare equal however they are spelled.
- **Output uses LF line breaks on every system, and Format keeps CRLF** — on Windows, JSON, XML
  and JSON Schema output took the system's CRLF. Every output now uses LF, and Format keeps the
  line breaks of the document it formats. Line breaks inside quoted CSV cells are data, and
  Format and Save leave them as written.
- **Save keeps a file's permissions, symbolic links, line breaks and encoding** — on Linux and
  macOS a saved file keeps its permissions instead of becoming readable by its owner only, and
  saving through a symbolic link updates the file it points to instead of replacing the link. A
  file keeps its CRLF or LF line breaks, and a new one gets the project's line separator. A
  CSV, Java or Kotlin file keeps its encoding and byte-order mark, so Excel still reads a
  Windows-1252 or "CSV UTF-8" file correctly after a save; JSON, XML, YAML, TOML and Protobuf
  are written as UTF-8, the encoding their tools read. On Windows, a save retries for a moment
  when another program, such as a virus scanner, holds the file.
- **Context-menu actions appear where they can work** — *Open in Be Water Converter* and
  *Convert with Be Water* are offered on JSON, XML (SVG and XHTML included), YAML, TOML, CSV,
  TSV and Protobuf files, on plain-text files whose start reads as one of them, and on any
  selection that does. They no longer appear on Java classes, scripts, READMEs or
  `gradle.properties` because a line looked like `key = value`. The menu reads only the start
  of a large selection, so it opens at once, and Cancel stops a conversion under way, not only
  the read before it.
- **Options follow across projects** — each open project has its own panel, and a choice made
  in one (CSV mode and delimiter, row warning, Lombok, dates, type inference, sort keys) now
  shows in the others. A panel saves only the option that changed, so it no longer writes its
  stale choices over one just made in another project.
- **Keys the IDE owns are left to it** — Ctrl+D (Cmd+D on macOS) no longer deletes the line in
  the panel's editors, where the IDE's Duplicate Line does not reach. The panel no longer binds
  Alt+Shift+L, Alt+Shift+C and Ctrl+Shift+O, which never reached it past the IDE's Load
  Context, Recent Changes and Load Gradle Changes: Format Input, Copy Output and Open File take
  any shortcut you give them in Settings | Keymap.
- **Find's Previous reaches a match at the end of the document** — when the document ended with
  a match, Shift+Enter and the Previous button skipped it and selected the first match again,
  every time.
- **The tool window follows the IDE** — fonts follow the IDE's font size and Zoom IDE, and a
  new theme, editor color scheme or font size restyles the panel at once. Confirmations use the
  IDE's own dialogs, context-menu error balloons keep XML tag names and line breaks, *Convert
  with Be Water* greys out the file's own format, and an XML Format error moves the caret to
  the problem like other parse errors do. The editors are RSyntaxTextArea 4.0.1.
- **Keyboard and screen-reader use** — opening the tool window puts the caret in the input
  editor, toolbar buttons show a focus ring when Tab reaches them, and screen readers name the
  editors, format selectors, option controls and the find field.
- **Large documents stay responsive** — soft wrap is never applied to a document too large to
  highlight, where each resize took seconds, and a wrap turned on meanwhile applies once a
  smaller document replaces it. Pressing Format while it runs says so instead of starting a
  second pass.

## [1.5.2]

- **Fixed: CSV headers beginning with `#` were mistaken for comments** — comma, semicolon and
  tab exports retain their format and delimiter detection, including files with just a header
  and one data row.
- **Background results respect newer edits** — Convert, Format, Compare and file loading
  discard results that belong to an older document. Clear, Swap and history restoration also
  invalidate pending results, and a late Cancel still stops a completed conversion from
  replacing the output.
- **History restores conversion options** along with the documents and formats, including the
  CSV delimiter, type inference, date detection and subtree filter.
- **YAML Format refuses changes it cannot preserve** — non-string mapping keys, timestamps,
  sets and other unsupported YAML types leave the document untouched with an explanation.
  Explicit `null` documents remain convertible.
- **TOML safeguards distinguish keys from values** — numeric and date-looking table names no
  longer trigger value warnings, escaped quotes in multiline strings are handled correctly, and
  lowercase date-time separators are recognised.
- **Sorting preserves decimal scale** — values such as `1.10` keep their written precision
  through Format and conversion. Compare treats equivalent numeric forms as equal while
  preserving differences in their values.
- **Generated code handles numeric and JVM limits** — Java and Kotlin use exact numeric types
  when needed. Oversized Kotlin data classes are refused with an explanation, and Lombok output
  omits an all-args constructor when it would exceed the JVM parameter limit. Protobuf
  generation refuses numbers its scalar types cannot preserve.
- **Protobuf preserves explicit JSON names**, including escaped names in nested messages and
  `oneof` blocks, and rejects conflicting names. Package and global type references resolve in
  their declared scope, and generated field numbers skip the reserved `19000`–`19999` range.
- **Subtree paths handle quoted keys**, including empty names, escaped quotes and brackets.
  JSON Pointer spaces are preserved, malformed paths are rejected, and JSON Schema
  deduplication keeps distinct property names separate.
- **Large documents use less memory** when merging sparse array shapes, and highlighting is
  disabled for oversized input as well as output. Concurrent saves use separate temporary files
  so they cannot overwrite each other's temporary data.
- **Expanded regression coverage** includes asynchronous editor actions, generated Java and
  Kotlin compilation, real Lombok processing and a constrained-heap array test. CI runs both
  the plain JVM and IntelliJ test suites.

## [1.5.1]

- **Fixed: JSON→CSV merged two keys into one column** — a key containing a dot and a nested key
  with the same path (`a.b` and `a → b`) wrote one column and kept only the last value. It is
  refused now, with the column named.
- **Fixed: JSON with a trailing comment could not be auto-closed** — the missing brace was
  appended inside the comment; and a comment-only JSON document is refused instead of
  converting to `null`.
- **Format detection looks past leading comments**, so a `//` or `#` line above a JSON document
  no longer hides it, and a commented JSON array is no longer taken for TOML.
- **XML Format keeps a DOCTYPE** — plist, XHTML and SVG files can be formatted; only a DOCTYPE
  with an internal subset is refused, with a plain explanation.
- **Files with a UTF-16 or UTF-32 byte-order mark open correctly** instead of as garbage; YAML
  parse errors move the caret like the other formats; Protobuf Format works on CRLF files; a
  YAML `null` key converts as `"null"`.
- **Format keeps your place** — the caret stays on its line, and an already formatted document
  is left untouched rather than replaced with itself. Save proposes the same file name the
  scratch files use (`Root.java` for Java), and _Open in Be Water Converter_ asks before
  loading a very large file.
- **Numbers keep their written form in YAML and TOML too** — `1.10` no longer becomes `1.1`,
  `1e400` no longer becomes the text "Infinity", and long decimals keep every digit. Format
  used to write the rewritten values back over the document.
- **YAML output quotes exactly what the reader would retype** — strings such as `"1_000"`,
  `"0x1F"` and `"1e3"` came back as numbers after a round trip.
- **Fixed: Format split a `---`-prefixed YAML list into one document per element**, and treated
  a `---` line inside a block scalar as a separator.
- **Fixed: CSV Format rewrote the document** — headers were renamed (`id,id,,` became
  `id,id_2,column_3`), a ragged row dropped the headers it lacked, and a header-only file was
  refused. Format now rewrites CSV row by row and never interprets it. Blank lines no longer
  become empty rows on conversion.
- **Fixed: Protobuf nested types leaked across messages** — two messages each declaring an
  `Inner` or a `Status` both resolved to the last one defined, a nested type hid a top-level
  one from every other message, and dotted references such as `Outer.Inner` resolved to
  nothing. Names now resolve the way protoc resolves them.
- **Repeated JSON keys are refused** rather than silently keeping the last value.
- **Format asks before dropping comments** — YAML, TOML and JSON-with-comments keep only the
  data through Format; it now says how many comments (and YAML anchors) would go and waits for
  a yes. It also refuses TOML hex/octal/binary, underscore-separated and inf/nan literals, YAML
  `.inf`/`.nan`, and XML mixed content, each of which it could only rewrite by changing it; and
  it no longer adds `standalone="no"` to an XML declaration.
- **Semicolon and tab CSV are recognised on paste**, and the Delimiter option follows the
  document — also when a `.csv` file is opened or converted from the Project view.
- **Open and Save talk to the IDE's file system** — opening a file with unsaved editor changes
  loads the editor's text, and a saved file shows up in the project without a manual sync.
  Saves go through a temporary file and a rename, so a failure part-way leaves the previous
  file intact. Format discards its result if the input was edited while it ran.
- **YAML booleans follow YAML 1.2** — `yes`, `no`, `on` and `off` are text, so the `on:` key of
  a GitHub Actions workflow no longer converts to `"true"`. Anchors that form a cycle, or whose
  aliases would expand to millions of values, are refused instead of crashing or exhausting
  memory.
- **Generated Java, Kotlin and Protobuf type arrays from every element** — objects contribute
  the union of their keys, numbers widen, and mixed kinds fall back to `Object`/`Any`. Kotlin
  marks a key that some element lacked as nullable. Generated Java is compiled by javac in the
  test suite.
- **Protobuf** — a `oneof` inside a nested message no longer leaks into the outer message
  (which reported a false duplicate field number); field numbers protoc rejects (0,
  19000–19999, above 536870911) are rejected; and JSON→Proto keeps a key its field name cannot
  spell through `json_name`.
- **Fixed: a TOML multi-line string ending in a quote hid the rest of the document** from the
  19-digit integer guard, so such an integer came back as 0.
- **XML names are compared by namespace, not prefix** — two prefixes bound to the same
  namespace are one repeated element, and are no longer refused as a collision.
- **Fixed: format detection froze the editor on a paste with thousands of blank lines**; it is
  linear now.
- **Convert with Be Water asks before a large CSV** — the row-count confirmation the tool
  window gives applies to context-menu conversions too.

## [1.5.0]

- **Open in editor** — a toolbar button sends the output to a scratch file, so you get the
  IDE's own highlighting, folding and keymap on a result.
- **TOML is syntax-highlighted** in the panel; it was the one supported format being shown as
  plain text.
- **Kotlin data class output** — `data class` declarations with `val` properties,
  `@JsonProperty` where the key differs, and the same nested-class and name-collision handling
  as the Java generator.
- **Fixed several ways input could be silently mangled** — JSONL and any content after the
  first JSON value were truncated without warning; YAML anchors resolved to the anchor _name_
  and `<<:` merge keys were discarded; a UTF-8 BOM (what Excel writes on "CSV UTF-8" export)
  became part of the first column name; repeated CSV headers overwrote one another; and very
  large or very precise numbers lost their value. Format, which rewrites your editor in place,
  was the most damaging path for several of these.
- **Format no longer retypes CSV cells** — it is a layout action, so `1.50` stays `1.50` and a
  literal `null` cell stays text. Convert still infers types.
- **Protobuf field options parse** — `[deprecated = true]` and friends no longer fail the whole
  message, and an option on an enum's zero value no longer silently promotes the next constant
  to be the default.
- **JSON Schema output** — generate a draft 2020-12 schema from any supported input. Mixed-type
  arrays produce `anyOf` rather than guessing from the first element.
- **CSV delimiter selection** — read and write comma, semicolon or tab separated files.
  Semicolon CSV (the norm across much of Europe) previously parsed as a single column.
- **Sort keys** — sort object keys alphabetically so output is stable and diffable; array order
  is preserved. Applies to every target format.
- **Automatic format detection on paste** — pasting or dropping content sets the input format
  for you; it never overrides a format you picked yourself.
- **Works from the editor and Project view** — right-click a file or a selection for _Open in
  Be Water Converter_, or _Convert with Be Water_ to convert straight to any format. No more
  pasting into the tool window to get started.
- **Results open as scratch files** — conversions started from a context menu open in a real
  IDE editor, with full highlighting, folding and your own keymap.
- **Subtree filter** — convert only part of a document with a JSON Pointer (`/users/0/name`) or
  dotted path (`users[0].name`).
- **Compare** — diff the two editors in the IDE's diff viewer. Both sides are normalised and
  key-sorted first, so the same data in YAML and JSON compares as equal and only real
  differences are shown.
- **Parse errors move the caret** to the reported line and column instead of only describing
  where the problem is.

## [1.4.1]

_Not published on the JetBrains Marketplace: these changes first reached it in 1.5.0._

- **Fixed: generated Java POJOs now compile** — the output declared every class `public`, which
  is illegal for more than one top-level type in a single file. Only the root class is public
  now.
- **Fixed: JSON→XML no longer drops values on key collisions** — keys that sanitize to the same
  element name (`"a b"` and `"a+b"`) are numbered instead of silently overwriting each other.
- **Fixed: colliding POJO class names** — two differently-shaped objects that map to the same
  class name each get their own class instead of the second being typed with the first one's
  fields.
- **Fixed: JSON→Proto emitted duplicate message names** — keys like `user` and `User` produced
  two `message User` blocks in one scope, which is invalid proto3.
- **Fixed: Protobuf input with braces or `//` inside string literals** — a valid schema
  containing `option x = "{";` was rejected as having unbalanced braces.
- **Faster, leaner conversions** — the internal JSON pivot is no longer pretty-printed, which
  removed about a third of its size on large inputs, and CSV output no longer copies every row
  a second time.
- **Fixed: saving output no longer freezes the IDE** — writes moved off the UI thread, matching
  how files are read.
- **Cancel is now reliable** — cancelling before the conversion starts is honoured, and row
  expansion checks for cancellation as it runs.
- **Row-count warning now covers FLAT_FIRST**, not just CROSS_JOIN.
- **Generated POJO imports are emitted only when used** — `List`, `BigDecimal`, `BigInteger`
  and `@JsonProperty` are no longer added unconditionally.
- **Minimum IDE version is now 2025.1** — the save-file dialog moved to the current
  `FileSaverDescriptor` API, which removes the last deprecated platform API usage. Users on
  2024.3 should stay on 1.4.0.

## [1.4.0]

- **Fixed: Format no longer corrupts XML input** — pretty-printing now preserves the original
  root element and attributes instead of renaming the root to `<root>`.
- **Fixed: FLAT_FIRST CSV no longer drops data** — non-object elements (primitives, nested
  arrays) of the expanded array now become rows instead of silently disappearing.
- **CSV type inference** — CSV input cells that look like numbers, booleans or null become
  typed JSON values; toggleable via the new _Infer types_ option (leading-zero values like
  `007` stay strings).
- **Cancellable conversions** — the Convert button turns into Cancel while a conversion runs.
- **Conversion history** — a toolbar history button lists the last 20 conversions; selecting
  one restores both editors and formats.
- **Date detection for Java POJOs** — ISO-8601 values are typed as `LocalDate`, `LocalDateTime`
  or `OffsetDateTime` (toggleable via _Detect dates_); `java.time` imports are emitted only
  when used.
- **Fixed: JSON→XML always emits well-formed XML** — keys that are not valid element names
  (spaces, leading digits) are sanitized instead of producing unparseable output.
- **Fixed: JSON→Proto emits valid identifiers** — kebab-case and other hostile keys are
  sanitized to snake_case and deduplicated.
- **Type inference for XML input** — the _Infer types_ option now also applies to XML.
- **Lenient JSON input** — comments, trailing commas, single quotes and unquoted field names
  are accepted and normalised.
- **Multi-document YAML** — "---"-separated documents (e.g. Kubernetes manifests) convert to a
  JSON array.
- **Find bar and soft-wrap** — Ctrl+F searches the focused editor; a toolbar button toggles
  soft-wrap in both editors.
- **Clear no longer resets saved preferences**, and restoring from history first saves the
  current editors so the restore can be undone.
- **Options persist across restarts** — CSV mode, row-warning threshold, Lombok toggle, type
  inference and split orientation are remembered.
- **Better error reporting** — multi-line validation errors are shown as an IDE notification
  balloon instead of being cut off in the status bar.
- **Protobuf** — enum fields resolve to their first declared value; duplicate field numbers are
  now also rejected across `oneof` blocks.
- **Java POJO generation** — colliding field names are deduplicated, all name segments are
  sanitized, `BigInteger` is supported, and empty arrays map to `List<Object>`.
- **UI robustness** — file loading moved off the UI thread with a large-file guard, save asks
  before overwriting, syntax highlighting is disabled for very large outputs, actions are
  registered in the Keymap for custom shortcuts, and the tool window is available during
  indexing.

## [1.3.1]

- **Updated shortcut documentation** — only the confirmed `Ctrl+Enter` conversion shortcut is
  documented; other actions remain available from the toolbar.
- **Improved swap handling** — Java POJO output is now treated as output-only and no longer
  leaves the UI in an invalid input-format state when swap is requested.
- **Added UI regression coverage** for output-only swap behavior.

## [1.3.0]

_The first release on the JetBrains Marketplace; 1.0.0 and 1.1.0 were not published there._

- **Fixed dependency bundling** — all Jackson dataformat modules and RSyntaxTextArea are now
  correctly included in the plugin distribution, resolving "Package not found" errors across
  supported IDE versions.
- **Replaced deprecated `JsonNode.fields()`** — migrated all 10 usages to
  `JsonNode.properties()` for forward-compatibility with Jackson 2.21+.
- **Extended compatibility range** — removed the upper build cap for IntelliJ Platform 2024.3+
  forward compatibility.

## [1.1.0]

- **Drag-and-drop file input** — drop a file onto the input editor to load it with automatic
  format detection.
- **Configurable CROSS_JOIN row-warning threshold** — adjust the row limit via a spinner in the
  CSV options bar.
- **Full Protobuf structural parsing** — nested `message`, `oneof`, and `enum` blocks are
  parsed correctly; field types referencing known messages resolve to nested JSON objects;
  JSON-to-Proto generates inline nested messages.

## [1.0.0]

- Initial release: JSON, XML, YAML, CSV, TOML and Protobuf conversion plus Java POJO
  generation.
