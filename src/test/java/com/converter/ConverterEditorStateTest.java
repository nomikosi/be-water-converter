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

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.junit.jupiter.api.Test;
import javax.swing.JLabel;
import static com.converter.TestTasks.onEdt;
import static org.assertj.core.api.Assertions.*;

class ConverterEditorStateTest {
    // A blinking caret runs a Swing timer until it is stopped, which the
    // platform's test framework reports as a leak.
    private final java.util.List<RSyntaxTextArea> editors = new java.util.ArrayList<>();

    private RSyntaxTextArea track(RSyntaxTextArea editor) {
        editors.add(editor);
        return editor;
    }

    @org.junit.jupiter.api.AfterEach
    void stopCarets() throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(() -> editors.forEach(e -> e.getCaret().setBlinkRate(0)));
    }

    @Test void aHugeDocumentIsNeverSoftWrapped() throws Exception {
        onEdt(() -> {
            var input = track(new RSyntaxTextArea());
            var output = track(new RSyntaxTextArea());
            var state = new ConverterEditorState(input, output, new JLabel("JSON"), new JLabel("XML"));
            state.setLineWrap(true);
            state.replaceOutput(" ".repeat(ConverterEditorState.HIGHLIGHT_LIMIT_CHARS + 1), "YAML");
            // Wrapping measures every line again at each width: seconds per resize.
            assertThat(output.getLineWrap()).isFalse();
            assertThat(input.getLineWrap()).isTrue();
            state.replaceOutput("a: 1", "YAML");
            assertThat(output.getLineWrap()).isTrue();
        });
    }

    @Test void everyFormatHasAnEditorStyle() {
        for (String format : com.converter.core.Formats.outputNames())
            assertThat(ConverterEditorState.syntaxStyle(format)).as(format)
                  .isNotEqualTo(SyntaxConstants.SYNTAX_STYLE_NONE);
        assertThat(ConverterEditorState.syntaxStyle("no such format"))
              .isEqualTo(SyntaxConstants.SYNTAX_STYLE_NONE);
    }

    @Test void snapshotsSwapAndRestoreBothEditorsWithTheirFormats() throws Exception {
        onEdt(() -> {
            var input = track(new RSyntaxTextArea());
            var output = track(new RSyntaxTextArea());
            var inputLabel = new JLabel("JSON");
            var outputLabel = new JLabel("XML");
            var state = new ConverterEditorState(input, output, inputLabel, outputLabel);
            state.replaceInput("{\"a\":1}", false);
            state.replaceOutput("a: 1\n", "YAML");
            var saved = state.snapshot();
            long revision = state.revision();
            state.apply(saved.swapped());
            assertThat(state.revision()).isGreaterThan(revision);
            assertThat(state.snapshot()).isEqualTo(saved.swapped());
            assertThat(input.getSyntaxEditingStyle()).isEqualTo(SyntaxConstants.SYNTAX_STYLE_YAML);
            assertThat(output.getSyntaxEditingStyle()).isEqualTo(SyntaxConstants.SYNTAX_STYLE_JSON);
            state.apply(saved);
            assertThat(state.snapshot()).isEqualTo(saved);
            assertThat(input.getCaretPosition()).isZero();
            assertThat(output.getCaretPosition()).isZero();
        });
    }

    @Test void clearingEmptyEditorsStillInvalidatesPendingWorkAndKeepsBadgesConsistent() throws Exception {
        onEdt(() -> {
            var input = track(new RSyntaxTextArea());
            var output = track(new RSyntaxTextArea());
            var state = new ConverterEditorState(input, output, new JLabel("JSON"), new JLabel("XML"));
            long revision = state.revision();
            state.clear();
            assertThat(state.revision()).isGreaterThan(revision);
            assertThat(state.snapshot().output().format()).isEqualTo("XML");
            assertThat(output.getSyntaxEditingStyle()).isEqualTo(SyntaxConstants.SYNTAX_STYLE_XML);
            assertThat(state.shouldAutoDetect()).isFalse();
        });
    }

    @Test void restoringLargeOutputAndChangingWrapCannotReenableExpensiveHighlighting() throws Exception {
        onEdt(() -> {
            var input = track(new RSyntaxTextArea());
            var output = track(new RSyntaxTextArea());
            var state = new ConverterEditorState(input, output, new JLabel("JSON"), new JLabel("XML"));
            var large = new ConverterEditorState.Snapshot(new ConverterEditorState.Document("{}", "JSON"),
                  new ConverterEditorState.Document(" ".repeat(ConverterEditorState.HIGHLIGHT_LIMIT_CHARS + 1), "YAML"));
            state.apply(large);
            state.setLineWrap(true);
            state.setLineWrap(false);
            assertThat(output.getSyntaxEditingStyle()).isEqualTo(SyntaxConstants.SYNTAX_STYLE_NONE);
            assertThat(output.isCodeFoldingEnabled()).isFalse();
            state.replaceOutput("a: 1", "YAML");
            assertThat(output.getSyntaxEditingStyle()).isEqualTo(SyntaxConstants.SYNTAX_STYLE_YAML);
            assertThat(output.isCodeFoldingEnabled()).isTrue();
        });
    }
}
