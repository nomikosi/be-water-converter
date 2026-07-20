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

    private static List<Shortcut> documentedShortcuts() {
        return List.of(
              new Shortcut(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK),
                    "convert")
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
