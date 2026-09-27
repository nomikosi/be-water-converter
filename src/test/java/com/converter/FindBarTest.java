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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;

import static com.converter.TestTasks.field;
import static com.converter.TestTasks.onEdt;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Find bar")
class FindBarTest {

    private final List<RSyntaxTextArea> editors = new ArrayList<>();
    private final List<String> reported = new ArrayList<>();
    private final FindBar.StatusSink sink = new FindBar.StatusSink() {
        @Override public void ok(String message) { reported.add("ok: " + message); }
        @Override public void warn(String message) { reported.add("warn: " + message); }
    };

    @AfterEach
    void stopCarets() throws Exception {
        // The IDE's test framework fails a test that leaves a Swing timer running.
        SwingUtilities.invokeAndWait(() -> editors.forEach(e -> e.getCaret().setBlinkRate(0)));
    }

    private RSyntaxTextArea editor(String text) {
        RSyntaxTextArea editor = new RSyntaxTextArea(text);
        editor.setCaretPosition(0);
        editors.add(editor);
        return editor;
    }

    @Test @DisplayName("Enter finds the next match ignoring case, Shift+Enter the previous, both wrapping")
    void findsAndWraps() throws Exception {
        onEdt(() -> {
            RSyntaxTextArea editor = editor("alpha beta Alpha");
            FindBar bar = new FindBar(() -> editor, sink);
            bar.open();
            JTextField query = field(bar, "field", JTextField.class);
            query.setText("alpha");

            query.postActionEvent();
            assertThat(editor.getSelectionStart()).isZero();
            assertThat(editor.getSelectedText()).isEqualTo("alpha");
            query.postActionEvent();
            assertThat(editor.getSelectionStart()).isEqualTo(11);
            query.postActionEvent();
            assertThat(editor.getSelectionStart()).as("wrapped past the end").isZero();
            // "Alpha" ends the document: RSyntaxTextArea's own wrap skipped it
            // and selected the first match again, every time.
            query.getActionMap().get("findPrev").actionPerformed(null);
            assertThat(editor.getSelectionStart()).as("wrapped past the start").isEqualTo(11);
            query.getActionMap().get("findPrev").actionPerformed(null);
            assertThat(editor.getSelectionStart()).isZero();
            assertThat(reported).containsOnly("ok: Found \"alpha\"");
        });
    }

    @Test @DisplayName("a query with no match warns and leaves the selection alone")
    void noMatch() throws Exception {
        onEdt(() -> {
            RSyntaxTextArea editor = editor("alpha beta");
            editor.select(6, 10);
            FindBar bar = new FindBar(() -> editor, sink);
            JTextField query = field(bar, "field", JTextField.class);
            query.setText("gamma");

            query.postActionEvent();
            assertThat(reported).containsExactly("warn: No matches for \"gamma\"");
            assertThat(editor.getSelectedText()).isEqualTo("beta");
        });
    }

    @Test @DisplayName("an empty query, or no editor to search, does nothing")
    void nothingToSearch() throws Exception {
        onEdt(() -> {
            RSyntaxTextArea editor = editor("alpha");
            FindBar withEditor = new FindBar(() -> editor, sink);
            field(withEditor, "field", JTextField.class).postActionEvent();

            FindBar withoutEditor = new FindBar(() -> null, sink);
            JTextField query = field(withoutEditor, "field", JTextField.class);
            query.setText("alpha");
            query.postActionEvent();

            assertThat(reported).isEmpty();
        });
    }

    @Test @DisplayName("it starts hidden, opens, closes on Esc, and names its field for screen readers")
    void opensAndCloses() throws Exception {
        onEdt(() -> {
            RSyntaxTextArea editor = editor("alpha");
            FindBar bar = new FindBar(() -> editor, sink);
            JTextField query = field(bar, "field", JTextField.class);
            assertThat(bar.isVisible()).isFalse();

            bar.open();
            assertThat(bar.isVisible()).isTrue();
            query.getActionMap().get("closeFind").actionPerformed(null);
            assertThat(bar.isVisible()).isFalse();
            assertThat(query.getAccessibleContext().getAccessibleName()).isEqualTo("Find");
        });
    }
}
