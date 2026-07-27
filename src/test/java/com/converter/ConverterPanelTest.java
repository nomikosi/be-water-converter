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

    @Test
    void documentedShortcutsAreBoundToPanelAndEditors() throws Exception {
        runOnEdt(() -> {
            ConverterPanel panel = new ConverterPanel();
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
            ConverterPanel panel = new ConverterPanel();
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

    // ── Paste detection ───────────────────────────────────────────────────

    @Test
    void pasteSizedInsertSwitchesTheInputFormat() throws Exception {
        AtomicReference<ConverterPanel> ref = new AtomicReference<>();
        runOnEdt(() -> {
            ConverterPanel panel = new ConverterPanel();
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
            ConverterPanel panel = new ConverterPanel();
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
            ConverterPanel panel = new ConverterPanel();
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
            ConverterPanel panel = new ConverterPanel();
            field(panel, "sortKeysCheck", JCheckBox.class).setSelected(true);
            field(panel, "inferTypesCheck", JCheckBox.class).setSelected(false);
            field(panel, "filterField", JTextField.class).setText("users[0]");
            JComboBox<?> delimiter = field(panel, "csvDelimiterCombo", JComboBox.class);
            delimiter.setSelectedIndex(1);   // semicolon

            Method m = ConverterPanel.class.getDeclaredMethod("currentOptions");
            m.setAccessible(true);
            Object opts = m.invoke(panel);

            assertThat(readBool(opts, "sortKeys")).isTrue();
            assertThat(readBool(opts, "inferTypes")).isFalse();
            assertThat(String.valueOf(read(opts, "filterPath"))).isEqualTo("users[0]");
            // The delimiter must reach the options record, not just the combo:
            // Compare read the wrong one for exactly this reason.
            Object csvFormat = read(opts, "csvFormat");
            assertThat(String.valueOf(read(csvFormat, "delimiter"))).isEqualTo(";");
        });
    }

    private static Object read(Object target, String accessor) throws Exception {
        Method m = target.getClass().getDeclaredMethod(accessor);
        m.setAccessible(true);
        return m.invoke(target);
    }

    private static boolean readBool(Object target, String accessor) throws Exception {
        return (Boolean) read(target, accessor);
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
