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

import com.converter.core.Formats;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;

import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.util.Map;

/** EDT-owned editor content, presentation, and revision tracking. */
final class ConverterEditorState {
    static final int HIGHLIGHT_LIMIT_CHARS = 2_000_000;

    /** How each format is highlighted. The core names the formats; how they look belongs here. */
    private static final Map<String, String> SYNTAX_STYLES = Map.of(
          Formats.FMT_JSON, SyntaxConstants.SYNTAX_STYLE_JSON,
          Formats.FMT_XML, SyntaxConstants.SYNTAX_STYLE_XML,
          Formats.FMT_YAML, SyntaxConstants.SYNTAX_STYLE_YAML,
          Formats.FMT_CSV, SyntaxConstants.SYNTAX_STYLE_CSV,
          // No TOML mode exists; INI shares its [table] headers and key = value lines.
          Formats.FMT_TOML, SyntaxConstants.SYNTAX_STYLE_INI,
          Formats.FMT_PROTO, SyntaxConstants.SYNTAX_STYLE_PROTO,
          Formats.FMT_JAVA, SyntaxConstants.SYNTAX_STYLE_JAVA,
          Formats.FMT_KOTLIN, SyntaxConstants.SYNTAX_STYLE_KOTLIN,
          Formats.FMT_SCHEMA, SyntaxConstants.SYNTAX_STYLE_JSON);

    /** The editor style for a format, or plain text for one this editor does not know. */
    static String syntaxStyle(String format) {
        return format == null ? SyntaxConstants.SYNTAX_STYLE_NONE
              : SYNTAX_STYLES.getOrDefault(format, SyntaxConstants.SYNTAX_STYLE_NONE);
    }

    record Document(String text, String format) {}
    record Snapshot(Document input, Document output) {
        Snapshot swapped() { return new Snapshot(output, input); }
    }

    private final RSyntaxTextArea input;
    private final RSyntaxTextArea output;
    private final JLabel inputLabel;
    private final JLabel outputLabel;
    private long revision;
    private long outputRevision;
    private long detectionGeneration;
    private boolean autoDetect = true;

    ConverterEditorState(RSyntaxTextArea input, RSyntaxTextArea output,
          JLabel inputLabel, JLabel outputLabel) {
        this.input = input;
        this.output = output;
        this.inputLabel = inputLabel;
        this.outputLabel = outputLabel;
        input.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { revision++; }
            @Override public void removeUpdate(DocumentEvent e) { revision++; }
            @Override public void changedUpdate(DocumentEvent e) { revision++; }
        });
        output.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { outputRevision++; }
            @Override public void removeUpdate(DocumentEvent e) { outputRevision++; }
            @Override public void changedUpdate(DocumentEvent e) { outputRevision++; }
        });
        setInputFormat(inputLabel.getText());
        present(output, outputLabel, outputLabel.getText(), output.getDocument().getLength());
    }

    long revision() { return revision; }
    long outputRevision() { return outputRevision; }
    boolean shouldAutoDetect() { return autoDetect; }

    Snapshot snapshot() {
        return new Snapshot(new Document(input.getText(), inputLabel.getText()),
              new Document(output.getText(), outputLabel.getText()));
    }

    void setInputFormat(String format) {
        revision++;
        present(input, inputLabel, format, input.getDocument().getLength());
    }

    void replaceInput(String text, boolean detectFormat) {
        revision++; // Also invalidate pending work when replacing empty with empty.
        long generation = ++detectionGeneration;
        autoDetect = detectFormat;
        present(input, inputLabel, inputLabel.getText(), text.length());
        input.setText(text);
        input.setCaretPosition(0);
        if (!detectFormat) SwingUtilities.invokeLater(() -> {
            if (generation == detectionGeneration) autoDetect = true;
        });
    }

    boolean replaceOutput(String text, String format) {
        outputRevision++;
        boolean huge = present(output, outputLabel, format, text.length());
        output.setText(text);
        output.setCaretPosition(0);
        return huge;
    }

    void apply(Snapshot state) {
        setInputFormat(state.input().format());
        replaceInput(state.input().text(), false);
        replaceOutput(state.output().text(), state.output().format());
    }

    void clear() {
        apply(new Snapshot(new Document("", Formats.FMT_JSON), new Document("", Formats.FMT_XML)));
    }

    void setLineWrap(boolean wrap) {
        for (RSyntaxTextArea area : new RSyntaxTextArea[]{input, output}) {
            area.setLineWrap(wrap);
            area.setWrapStyleWord(wrap);
        }
        present(input, inputLabel, inputLabel.getText(), input.getDocument().getLength());
        present(output, outputLabel, outputLabel.getText(), output.getDocument().getLength());
    }

    private static boolean present(RSyntaxTextArea area, JLabel label, String format, int length) {
        boolean huge = length > HIGHLIGHT_LIMIT_CHARS;
        area.setSyntaxEditingStyle(huge ? SyntaxConstants.SYNTAX_STYLE_NONE : syntaxStyle(format));
        area.setCodeFoldingEnabled(!huge && !area.getLineWrap());
        label.setText(format);
        label.repaint();
        return huge;
    }
}
