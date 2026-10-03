/*
 * Copyright (c) 2026 Nomikosi Consulting
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.converter;

import com.converter.core.ConversionOptions;
import com.converter.core.CsvConverter;
import com.converter.core.FormatDetector;
import com.converter.core.Formats;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.WrapLayout;

import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import java.awt.FlowLayout;

import static com.converter.ConverterTheme.*;

/**
 * The options bar under the toolbar: every per-conversion setting, which of
 * them the current formats make relevant, and the snapshot a conversion takes
 * of them. Choices are remembered through {@link ConverterSettings}.
 */
final class OptionsBar {

    private final JPanel bar;
    private final JComboBox<CsvConverter.CsvMode> csvModeCombo;
    private final JLabel csvModeHint;
    private final JSpinner rowThresholdSpinner;
    private final JCheckBox lombokCheck;
    private final JCheckBox detectDatesCheck;
    private final JCheckBox inferTypesCheck;
    private final JCheckBox sortKeysCheck;
    private final JTextField filterField;
    private final JComboBox<CsvDelimiter> csvDelimiterCombo;
    private final JPanel csvOptions;
    private final JPanel csvInputOptions;
    private final JPanel csvDelimiterOptions;
    private final JPanel javaOptions;
    private final JPanel payloadOptions;
    private final JLabel schemaLabel;
    private final JComboBox<String> messageCombo;
    /** The .proto text a payload is decoded against; empty until one is chosen. */
    private String protoSchema = "";
    /**
     * True while the controls are set from, or saved to, the remembered
     * settings: the panel then neither saves a change it is following nor
     * follows a change it is saving.
     */
    private boolean following;
    private final Runnable follow = this::followSettings;

    /** The message choice that decodes a payload without its schema. */
    static final String RAW_MESSAGE = "(raw)";

    /**
     * @param onFilterEnter  what Enter in the filter box does: re-run the conversion
     * @param onChooseSchema what the schema button does: let the user pick a .proto file
     */
    OptionsBar(Runnable onFilterEnter, Runnable onChooseSchema) {
        csvModeCombo = ConverterWidgets.combo(CsvConverter.CsvMode.values());
        csvModeCombo.setToolTipText("How arrays of objects are expanded into CSV rows");
        csvModeCombo.getAccessibleContext().setAccessibleName("CSV mode");

        csvModeHint = new JLabel(csvModeHintFor(CsvConverter.CsvMode.FLAT_FIRST));
        csvModeHint.setForeground(TEXT_DIM);
        csvModeHint.setFont(JBFont.medium().asItalic());
        csvModeCombo.addActionListener(e -> {
            CsvConverter.CsvMode mode = (CsvConverter.CsvMode) csvModeCombo.getSelectedItem();
            if (mode != null) csvModeHint.setText(csvModeHintFor(mode));
        });

        rowThresholdSpinner = new JSpinner(new SpinnerNumberModel((Number) ConverterSettings.DEFAULT_ROW_WARNING,
              ConverterSettings.MIN_ROW_WARNING, ConverterSettings.MAX_ROW_WARNING, 100L));
        rowThresholdSpinner.setToolTipText(
              "CSV conversions estimated to exceed this row count trigger a confirmation");
        rowThresholdSpinner.setFont(JBFont.label());
        rowThresholdSpinner.setPreferredSize(JBUI.size(90, 26));
        rowThresholdSpinner.getAccessibleContext().setAccessibleName("Row warning threshold");

        lombokCheck = ConverterWidgets.checkBox("Lombok annotations", false,
              "Annotate generated classes with @Data, @NoArgsConstructor and @AllArgsConstructor");
        detectDatesCheck = ConverterWidgets.checkBox("Detect dates", true,
              "Type ISO-8601 values as LocalDate / LocalDateTime / OffsetDateTime instead of String");
        inferTypesCheck = ConverterWidgets.checkBox("Infer types", true,
              "Convert CSV/XML values that look like numbers, booleans or null into typed JSON values");
        sortKeysCheck = ConverterWidgets.checkBox("Sort keys", false,
              "<html>Sort object keys alphabetically so output is stable and diffable (array order is kept).<br>"
              + "Applies to conversions, and to Format for JSON, YAML and TOML.</html>");

        csvDelimiterCombo = ConverterWidgets.combo(CsvDelimiter.values());
        csvDelimiterCombo.setToolTipText(
              "Delimiter used when reading and writing CSV (semicolon is common in Europe)");
        csvDelimiterCombo.getAccessibleContext().setAccessibleName("CSV delimiter");

        csvOptions = group("CSV mode:", csvModeCombo, csvModeHint,
              ConverterWidgets.toolbarLabel("Row warning:"), rowThresholdSpinner);
        // The row warning is shown for both CSV modes: the warning it governs
        // fires for both, and hiding it under FLAT_FIRST left the default
        // 1,000-row question with no visible way to change the limit.
        csvInputOptions = group("Input:", inferTypesCheck);
        // Shown whenever CSV is on either side: the delimiter applies to both.
        csvDelimiterOptions = group("Delimiter:", csvDelimiterCombo);
        javaOptions = group("Code gen:", lombokCheck, detectDatesCheck);

        filterField = new JTextField(16);
        filterField.setToolTipText("<html>Convert only part of the document.<br>"
              + "JSON Pointer (<code>/users/0/name</code>) or dotted "
              + "(<code>users[0].name</code>). Empty converts everything.</html>");
        filterField.setFont(JBFont.label());
        filterField.setBackground(DROPDOWN_BG);
        filterField.setForeground(TEXT_BRIGHT);
        filterField.setCaretColor(TEXT_BRIGHT);
        filterField.setBorder(BorderFactory.createCompoundBorder(
              BorderFactory.createLineBorder(BORDER, 1), JBUI.Borders.empty(3, 6)));
        filterField.addActionListener(e -> onFilterEnter.run());
        filterField.getAccessibleContext().setAccessibleName("Subtree filter");

        schemaLabel = ConverterWidgets.toolbarLabel("none");
        schemaLabel.setToolTipText("The .proto file the payload is decoded against");
        javax.swing.JButton chooseSchema = ConverterWidgets.button("Choose .proto…", UTIL_BG, UTIL_HOVER, true);
        chooseSchema.setToolTipText("Choose the .proto file that declares the payload's message type");
        chooseSchema.getAccessibleContext().setAccessibleName("Choose schema");
        chooseSchema.addActionListener(e -> onChooseSchema.run());
        messageCombo = ConverterWidgets.combo(new String[]{RAW_MESSAGE});
        messageCombo.setToolTipText("<html>The message type the payload holds.<br>"
              + "(raw) decodes field numbers and wire types, as protoc --decode_raw does.</html>");
        messageCombo.getAccessibleContext().setAccessibleName("Payload message type");
        payloadOptions = group("Schema:", schemaLabel, chooseSchema, ConverterWidgets.toolbarLabel("Message:"), messageCombo);

        // Sort keys and the filter apply to every conversion, so they always show.
        JPanel generalOptions = group(null, sortKeysCheck, ConverterWidgets.toolbarLabel("Filter:"), filterField);

        bar = new JPanel(new WrapLayout(FlowLayout.LEFT, 8, 5));
        bar.setBackground(BG_LABEL_BAR);
        bar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER));
        bar.add(ConverterWidgets.toolbarLabel("Options:"));
        bar.add(generalOptions);
        bar.add(csvInputOptions);
        bar.add(csvDelimiterOptions);
        bar.add(csvOptions);
        bar.add(javaOptions);
        bar.add(payloadOptions);
    }

    private static JPanel group(String caption, JComponent... controls) {
        JPanel group = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        group.setOpaque(false);
        if (caption != null) group.add(ConverterWidgets.toolbarLabel(caption));
        for (JComponent control : controls) group.add(control);
        return group;
    }

    JComponent component() { return bar; }

    /** The controls that must see the panel's shortcuts even though they consume keys themselves. */
    JComponent[] shortcutTargets() {
        return new JComponent[]{csvModeCombo, rowThresholdSpinner, lombokCheck};
    }

    /**
     * Restores the remembered choices, then remembers each change from here on
     * and follows changes made in other projects' panels.
     */
    void restoreAndRemember() {
        followSettings();
        csvModeCombo.addActionListener(e -> {
            if (csvModeCombo.getSelectedItem() instanceof CsvConverter.CsvMode mode)
                remember(() -> ConverterSettings.saveCsvMode(mode));
        });
        csvDelimiterCombo.addActionListener(e -> {
            if (csvDelimiterCombo.getSelectedItem() instanceof CsvDelimiter delimiter)
                remember(() -> ConverterSettings.saveCsvDelimiter(delimiter));
        });
        lombokCheck.addActionListener(e -> remember(() -> ConverterSettings.saveLombok(lombokCheck.isSelected())));
        detectDatesCheck.addActionListener(e ->
              remember(() -> ConverterSettings.saveDetectDates(detectDatesCheck.isSelected())));
        inferTypesCheck.addActionListener(e ->
              remember(() -> ConverterSettings.saveInferTypes(inferTypesCheck.isSelected())));
        sortKeysCheck.addActionListener(e -> remember(() -> ConverterSettings.saveSortKeys(sortKeysCheck.isSelected())));
        rowThresholdSpinner.addChangeListener(e ->
              remember(() -> ConverterSettings.saveRowWarningThreshold(rowWarningThreshold())));
        ConverterSettings.addListener(follow);
    }

    /**
     * Saves a change made in this panel. The panel already shows it, so it
     * does not follow its own save: reloading every control would put stored
     * values back over ones set but not yet saved.
     */
    private void remember(Runnable save) {
        if (following) return;
        following = true;
        try {
            save.run();
        } finally {
            following = false;
        }
    }

    /** Stops following the remembered settings: the panel is gone. */
    void dispose() {
        ConverterSettings.removeListener(follow);
    }

    /** Shows the remembered settings, the filter aside, without saving them again. */
    private void followSettings() {
        if (following) return;
        following = true;
        try {
            ConversionOptions stored = ConverterSettings.options();
            csvModeCombo.setSelectedItem(stored.csvMode());
            csvDelimiterCombo.setSelectedItem(CsvDelimiter.forChar(stored.csvFormat().delimiter()));
            lombokCheck.setSelected(stored.useLombok());
            detectDatesCheck.setSelected(stored.detectDates());
            inferTypesCheck.setSelected(stored.inferTypes());
            sortKeysCheck.setSelected(stored.sortKeys());
            rowThresholdSpinner.setValue(ConverterSettings.rowWarningThreshold());
        } finally {
            following = false;
        }
    }

    /** Shows the groups the current input and output formats make relevant. */
    void showFor(String inputFormat, String outputFormat) {
        boolean csvOut = Formats.FMT_CSV.equals(outputFormat);
        boolean csvIn = Formats.FMT_CSV.equals(inputFormat);
        csvOptions.setVisible(csvOut);
        csvInputOptions.setVisible(csvIn || Formats.FMT_XML.equals(inputFormat));
        csvDelimiterOptions.setVisible(csvIn || csvOut);
        javaOptions.setVisible(Formats.FMT_JAVA.equals(outputFormat) || Formats.FMT_KOTLIN.equals(outputFormat));
        payloadOptions.setVisible(Formats.FMT_PROTO_PAYLOAD.equals(inputFormat));
        // Lombok is a Java-only concept; offering it for Kotlin output would be
        // a toggle that silently does nothing.
        lombokCheck.setVisible(Formats.FMT_JAVA.equals(outputFormat));
        bar.revalidate();
        bar.repaint();
    }

    /** Snapshot of every option control. Must be called on the EDT. */
    ConversionOptions currentOptions() {
        CsvDelimiter delimiter = (CsvDelimiter) csvDelimiterCombo.getSelectedItem();
        CsvConverter.CsvMode mode = (CsvConverter.CsvMode) csvModeCombo.getSelectedItem();
        return new ConversionOptions(
              mode == null ? CsvConverter.CsvMode.FLAT_FIRST : mode,
              delimiter == null ? CsvConverter.CsvFormat.DEFAULT : delimiter.format,
              lombokCheck.isSelected(),
              detectDatesCheck.isSelected(),
              inferTypesCheck.isSelected(),
              sortKeysCheck.isSelected(),
              filterField.getText(),
              protoSchema,
              selectedMessage());
    }

    /** The message type chosen for a payload, or "" to decode it raw. */
    private String selectedMessage() {
        Object chosen = messageCombo.getSelectedItem();
        return chosen == null || RAW_MESSAGE.equals(chosen) ? "" : chosen.toString();
    }

    /**
     * Decodes payloads against a schema from here on: its text, and the message
     * types it declares to choose from. The first is chosen, being the usual
     * top-level message; (raw) stays on offer.
     */
    void useProtoSchema(String fileName, String text, java.util.List<String> messages) {
        protoSchema = text;
        schemaLabel.setText(fileName);
        messageCombo.removeAllItems();
        messageCombo.addItem(RAW_MESSAGE);
        for (String message : messages) messageCombo.addItem(message);
        messageCombo.setSelectedItem(messages.isEmpty() ? RAW_MESSAGE : messages.getFirst());
    }

    /**
     * The schema and message a history entry was decoded with. The file it
     * came from is not part of the entry, so the label says where it is from.
     */
    private void restoreProtoSchema(String text, String message) {
        if (!text.equals(protoSchema)) {
            java.util.List<String> messages = java.util.List.of();
            if (!text.isBlank()) {
                try {
                    messages = new com.converter.core.ProtoConverter().readSchema(text).messageNames();
                } catch (RuntimeException unreadable) {
                    // It was read when the entry was made; keep (raw) on offer regardless.
                }
            }
            useProtoSchema(text.isBlank() ? "none" : "from history", text, messages);
        }
        messageCombo.setSelectedItem(message.isBlank() ? RAW_MESSAGE : message);
    }

    /** Sets every control to {@code options} and remembers them, as history restores them. */
    void applyOptions(ConversionOptions options) {
        // One save for every option, not one per control that fires as it is set.
        following = true;
        try {
            csvModeCombo.setSelectedItem(options.csvMode());
            csvDelimiterCombo.setSelectedItem(CsvDelimiter.forChar(options.csvFormat().delimiter()));
            lombokCheck.setSelected(options.useLombok());
            detectDatesCheck.setSelected(options.detectDates());
            inferTypesCheck.setSelected(options.inferTypes());
            sortKeysCheck.setSelected(options.sortKeys());
            filterField.setText(options.filterPath());
            restoreProtoSchema(options.protoSchema(), options.protoMessage());
            // setSelected fires no ActionListener either: remember the restored
            // choices explicitly, so context-menu conversions and the next IDE
            // session agree.
            ConverterSettings.saveOptions(currentOptions());
        } finally {
            following = false;
        }
    }

    long rowWarningThreshold() {
        return ((Number) rowThresholdSpinner.getValue()).longValue();
    }

    /**
     * Selects the delimiter a CSV text actually uses, so a semicolon file is
     * not read as one wide column because the option still said comma. Returns
     * a status suffix naming the change, or "" when the selection already
     * matched or nothing could be told.
     */
    String applyDetectedDelimiter(String text, String fileName) {
        Character found = ContextDecisions.delimiterFor(fileName, text);
        CsvDelimiter option = found == null ? null : CsvDelimiter.forChar(found);
        if (option == null || option == csvDelimiterCombo.getSelectedItem()) return "";
        csvDelimiterCombo.setSelectedItem(option);   // its listener remembers the choice
        return " (" + option.noun + "-separated)";
    }

    /**
     * The filter belongs to the document that was cleared, not to the
     * preferences. Left armed, it silently narrowed — or rejected — the next,
     * unrelated document pasted in.
     */
    void clearFilter() { filterField.setText(""); }

    private static String csvModeHintFor(CsvConverter.CsvMode mode) {
        return switch (mode) {
            case FLAT_FIRST -> "expands only the first object-array into rows (safe default)";
            case CROSS_JOIN -> "Cartesian product of all object-arrays — rows can explode";
        };
    }
}
