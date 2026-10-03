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
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ConverterPanelTest {
    // Every panel is disposed after its test: a panel left undisposed kept its
    // listeners registered and, with RSyntaxTextArea 4, its caret timers running.
    private final List<ConverterPanel> panels = new ArrayList<>();

    private ConverterPanel track(ConverterPanel panel) {
        synchronized (panels) { panels.add(panel); }
        return panel;
    }

    @org.junit.jupiter.api.AfterEach
    void disposePanels() throws Exception {
        runOnEdt(() -> { synchronized (panels) { panels.forEach(ConverterPanel::dispose); } });
    }

    @Test
    void theIdesKeysAreLeftToTheIde() throws Exception {
        runOnEdt(() -> {
            ConverterPanel panel = track(new ConverterPanel());
            JPanel content = panel.getContent();
            // Ctrl+D duplicates a line in the IDE; RSyntaxTextArea deleted it.
            for (RSyntaxTextArea editor : findComponents(content, RSyntaxTextArea.class)) {
                javax.swing.InputMap keys = editor.getInputMap();
                for (KeyStroke key : keys.allKeys())
                    assertThat(keys.get(key)).as(key.toString())
                          .isNotEqualTo(org.fife.ui.rtextarea.RTextAreaEditorKit.rtaDeleteLineAction);
            }
            // Keys the IDE's keymap takes first are not bound here, where they never arrived.
            for (KeyStroke taken : List.of(
                  KeyStroke.getKeyStroke(KeyEvent.VK_L, InputEvent.ALT_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK),
                  KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.ALT_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK),
                  KeyStroke.getKeyStroke(KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK)))
                assertThat(content.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).get(taken))
                      .as(taken.toString()).isNull();
            // The tool window focuses the input editor, which is named for screen readers.
            RSyntaxTextArea input = field(panel, "inputArea", RSyntaxTextArea.class);
            assertThat(panel.preferredFocusComponent()).isSameAs(input);
            assertThat(input.getAccessibleContext().getAccessibleName()).isEqualTo("Input");
        });
    }


    @Test
    void documentedShortcutsAreBoundToPanelAndEditors() throws Exception {
        runOnEdt(() -> {
            ConverterPanel panel = track(new ConverterPanel());
            JPanel content = panel.getContent();

            List<RSyntaxTextArea> editors = findComponents(content, RSyntaxTextArea.class);
            assertThat(editors).hasSize(2);

            for (Shortcut shortcut : documentedShortcuts()) {
                assertShortcut(content, shortcut, JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
                for (RSyntaxTextArea editor : editors) {
                    assertShortcut(editor, shortcut, JComponent.WHEN_FOCUSED);
                    assertShortcut(editor, shortcut, JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
                }
            }
        });
    }

    @Test
    void swapRefusesJavaPojoOutputBecauseItIsNotAValidInputFormat() throws Exception {
        runOnEdt(() -> {
            ConverterPanel panel = track(new ConverterPanel());
            RSyntaxTextArea inputArea = field(panel, "inputArea", RSyntaxTextArea.class);
            RSyntaxTextArea outputArea = field(panel, "outputArea", RSyntaxTextArea.class);
            JLabel inputFormatLabel = field(panel, "inputFormatLabel", JLabel.class);
            JLabel outputFormatLabel = field(panel, "outputFormatLabel", JLabel.class);
            JLabel statusLabel = field(panel, "statusLabel", JLabel.class);

            inputArea.setText("{\"name\":\"Ada\"}");
            outputArea.setText("public class Root {}");
            inputFormatLabel.setText("JSON");
            outputFormatLabel.setText("Java POJO");

            invoke(panel, "doSwap");

            assertThat(inputArea.getText()).isEqualTo("{\"name\":\"Ada\"}");
            assertThat(outputArea.getText()).isEqualTo("public class Root {}");
            assertThat(inputFormatLabel.getText()).isEqualTo("JSON");
            assertThat(outputFormatLabel.getText()).isEqualTo("Java POJO");
            assertThat(statusLabel.getText()).contains("cannot be used as input");
        });
    }

    @Test
    void swapRefusesAPayloadInputBecauseAPayloadIsNeverWritten() throws Exception {
        runOnEdt(() -> {
            ConverterPanel panel = track(new ConverterPanel());
            RSyntaxTextArea inputArea = field(panel, "inputArea", RSyntaxTextArea.class);
            RSyntaxTextArea outputArea = field(panel, "outputArea", RSyntaxTextArea.class);
            JLabel inputFormatLabel = field(panel, "inputFormatLabel", JLabel.class);
            JLabel outputFormatLabel = field(panel, "outputFormatLabel", JLabel.class);

            inputArea.setText("08 96 01");
            outputArea.setText("{\"1\": 150}");
            inputFormatLabel.setText("Protobuf payload");
            outputFormatLabel.setText("JSON");

            invoke(panel, "doSwap");

            assertThat(inputArea.getText()).isEqualTo("08 96 01");
            assertThat(outputArea.getText()).isEqualTo("{\"1\": 150}");
            assertThat(inputFormatLabel.getText()).isEqualTo("Protobuf payload");
            assertThat(field(panel, "statusLabel", JLabel.class).getText())
                  .contains("Protobuf payload input cannot be used as output");
        });
    }

    @Test
    void aPayloadsSchemaAndMessageTypeShowForPayloadsAndReachTheOptions() throws Exception {
        runOnEdt(() -> {
            ConverterPanel panel = track(new ConverterPanel());
            OptionsBar options = field(panel, "options", OptionsBar.class);
            JPanel payloadOptions = field(options, "payloadOptions", JPanel.class);
            JComboBox<?> inputCombo = field(panel, "inputCombo", JComboBox.class);
            assertThat(payloadOptions.isVisible()).isFalse();

            inputCombo.setSelectedItem("Protobuf payload");
            assertThat(payloadOptions.isVisible()).isTrue();
            // A payload is decoded, never written: it is no output to choose.
            JComboBox<?> outputCombo = field(panel, "outputCombo", JComboBox.class);
            for (int i = 0; i < outputCombo.getItemCount(); i++)
                assertThat(outputCombo.getItemAt(i)).isNotEqualTo("Protobuf payload");
            // No schema yet: decoded raw.
            assertThat(options.currentOptions().protoSchema()).isEmpty();
            assertThat(options.currentOptions().protoMessage()).isEmpty();

            String schema = "syntax = \"proto3\";\npackage demo;\nmessage A { int32 a = 1; }\nmessage B { string b = 1; }\n";
            options.useProtoSchema("demo.proto", schema, List.of("demo.A", "demo.B"));
            assertThat(field(options, "schemaLabel", JLabel.class).getText()).isEqualTo("demo.proto");
            JComboBox<?> messages = field(options, "messageCombo", JComboBox.class);
            assertThat(messages.getItemCount()).isEqualTo(3);
            // The first message type is chosen, (raw) still on offer.
            assertThat(options.currentOptions().protoSchema()).isEqualTo(schema);
            assertThat(options.currentOptions().protoMessage()).isEqualTo("demo.A");
            messages.setSelectedItem("demo.B");
            assertThat(options.currentOptions().protoMessage()).isEqualTo("demo.B");
            messages.setSelectedItem(OptionsBar.RAW_MESSAGE);
            assertThat(options.currentOptions().protoMessage()).isEmpty();
            assertThat(options.currentOptions().protoSchema()).isEqualTo(schema);

            inputCombo.setSelectedItem("JSON");
            assertThat(payloadOptions.isVisible()).isFalse();
        });
    }

    // ── Paste detection ───────────────────────────────────────────────────

    @Test
    void bytesPastedForAPayloadStayAPayload() throws Exception {
        AtomicReference<ConverterPanel> ref = new AtomicReference<>();
        runOnEdt(() -> {
            ConverterPanel panel = track(new ConverterPanel());
            ref.set(panel);
            field(panel, "inputCombo", JComboBox.class).setSelectedItem("Protobuf payload");
            // A C array of the bytes, which alone is detected as CSV.
            field(panel, "inputArea", RSyntaxTextArea.class).setText("0x08, 0x96, 0x01,\n0x12, 0x07, 0x74,\n");
        });
        flushEdt();
        runOnEdt(() -> assertThat(comboSelection(ref.get(), "inputCombo")).isEqualTo("Protobuf payload"));
        // What is not bytes still switches it.
        runOnEdt(() -> field(ref.get(), "inputArea", RSyntaxTextArea.class).setText("name: Ada\nage: 36\n"));
        flushEdt();
        runOnEdt(() -> assertThat(comboSelection(ref.get(), "inputCombo")).isEqualTo("YAML"));
    }

    @Test
    void pasteSizedInsertSwitchesTheInputFormat() throws Exception {
        AtomicReference<ConverterPanel> ref = new AtomicReference<>();
        runOnEdt(() -> {
            ConverterPanel panel = track(new ConverterPanel());
            ref.set(panel);
            field(panel, "inputArea", RSyntaxTextArea.class).setText("name: Ada\nage: 36\n");
        });
        flushEdt();
        runOnEdt(() -> assertThat(comboSelection(ref.get(), "inputCombo")).isEqualTo("YAML"));
    }

    @Test
    void typingSizedInsertDoesNotSwitchTheInputFormat() throws Exception {
        AtomicReference<ConverterPanel> ref = new AtomicReference<>();
        runOnEdt(() -> {
            ConverterPanel panel = track(new ConverterPanel());
            ref.set(panel);
            // Below the paste threshold: detection must not fight the user as a
            // document takes shape keystroke by keystroke.
            field(panel, "inputArea", RSyntaxTextArea.class).setText("a: 1");
        });
        flushEdt();
        runOnEdt(() -> assertThat(comboSelection(ref.get(), "inputCombo")).isEqualTo("JSON"));
    }

    @Test
    void setInputTextQuietlyDoesNotTriggerDetection() throws Exception {
        AtomicReference<ConverterPanel> ref = new AtomicReference<>();
        runOnEdt(() -> {
            ConverterPanel panel = track(new ConverterPanel());
            ref.set(panel);
            // The panel already knows the format here (file load, history
            // restore, swap); detection must not second-guess it.
            invoke(panel, "setInputTextQuietly", "name: Ada\nage: 36\n");
        });
        flushEdt();
        runOnEdt(() -> assertThat(comboSelection(ref.get(), "inputCombo")).isEqualTo("JSON"));
    }

    // ── Option snapshot ───────────────────────────────────────────────────

    @Test
    void currentOptionsReflectsTheOptionControls() throws Exception {
        runOnEdt(() -> {
            ConverterPanel panel = track(new ConverterPanel());
            OptionsBar options = field(panel, "options", OptionsBar.class);
            field(options, "sortKeysCheck", JCheckBox.class).setSelected(true);
            field(options, "inferTypesCheck", JCheckBox.class).setSelected(false);
            field(options, "filterField", JTextField.class).setText("users[0]");
            JComboBox<?> delimiter = field(options, "csvDelimiterCombo", JComboBox.class);
            delimiter.setSelectedIndex(1);   // semicolon

            com.converter.core.ConversionOptions opts = options.currentOptions();

            assertThat(opts.sortKeys()).isTrue();
            assertThat(opts.inferTypes()).isFalse();
            assertThat(opts.filterPath()).isEqualTo("users[0]");
            // The delimiter must reach the options record, not just the combo:
            // Compare read the wrong one for exactly this reason.
            assertThat(opts.csvFormat().delimiter()).isEqualTo(';');
        });
    }

    @Test
    void aSavedOptionReachesOtherPanelsButDoesNotReloadItsOwn() throws Exception {
        runOnEdt(() -> {
            OptionsBar saving = field(track(new ConverterPanel()), "options", OptionsBar.class);
            OptionsBar following = field(track(new ConverterPanel()), "options", OptionsBar.class);
            // Set without saving, as a history restore sets controls before its one save.
            field(saving, "sortKeysCheck", JCheckBox.class).setSelected(true);
            field(following, "sortKeysCheck", JCheckBox.class).setSelected(true);

            field(saving, "csvDelimiterCombo", JComboBox.class).setSelectedIndex(1);   // saves semicolon

            // Outside the IDE nothing is stored, so the follower shows the defaults.
            assertThat(saving.currentOptions().sortKeys()).as("the panel that saved").isTrue();
            assertThat(following.currentOptions().sortKeys()).as("the panel that followed").isFalse();
        });
    }

    @Test
    void theToolbarWrapsOntoMoreRowsWhenNarrow() throws Exception {
        runOnEdt(() -> {
            JPanel content = track(new ConverterPanel()).getContent();
            JPanel toolbar = findComponents(content, JPanel.class).stream()
                  .filter(p -> p.getLayout() instanceof com.intellij.util.ui.WrapLayout)
                  .findFirst().orElseThrow();
            content.setSize(2400, 800);
            content.validate();
            int wide = toolbar.getPreferredSize().height;
            content.setSize(260, 800);
            content.validate();
            // The bar reports the height of every row it wraps onto, so the
            // panel above the editors grows instead of clipping them.
            assertThat(toolbar.getPreferredSize().height).isGreaterThan(wide);
        });
    }

    private static String comboSelection(ConverterPanel panel, String fieldName) throws Exception {
        return String.valueOf(field(panel, fieldName, JComboBox.class).getSelectedItem());
    }

    /** Lets queued invokeLater work (deferred paste detection) run. */
    private static void flushEdt() throws Exception {
        SwingUtilities.invokeAndWait(() -> {});
        SwingUtilities.invokeAndWait(() -> {});
    }

    private static void invoke(Object target, String methodName, String arg) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName, String.class);
        method.setAccessible(true);
        method.invoke(target, arg);
    }

    private static List<Shortcut> documentedShortcuts() {
        return List.of(
              new Shortcut(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK),
                    "convert"),
              new Shortcut(KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK),
                    "find")
        );
    }

    private static void assertShortcut(JComponent component, Shortcut shortcut, int condition) {
        assertThat(component.getInputMap(condition).get(shortcut.keyStroke()))
              .isEqualTo(shortcut.actionKey());
        assertThat(component.getActionMap().get(shortcut.actionKey())).isNotNull();
    }

    private static <T extends Component> List<T> findComponents(Container root, Class<T> type) {
        List<T> matches = new ArrayList<>();
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) {
                matches.add(type.cast(child));
            }
            if (child instanceof Container container) {
                matches.addAll(findComponents(container, type));
            }
        }
        return matches;
    }

    private static <T> T field(Object target, String name, Class<T> type) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(target));
    }

    private static void invoke(Object target, String methodName) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName);
        method.setAccessible(true);
        method.invoke(target);
    }

    private static void runOnEdt(CheckedRunnable runnable) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            runnable.run();
            return;
        }

        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                runnable.run();
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        Throwable thrown = failure.get();
        if (thrown instanceof Exception exception) throw exception;
        if (thrown != null) throw new AssertionError(thrown);
    }

    private record Shortcut(KeyStroke keyStroke, String actionKey) {}

    private interface CheckedRunnable {
        void run() throws Exception;
    }
}
