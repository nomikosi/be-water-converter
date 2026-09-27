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
import com.converter.core.ConversionPipeline;
import com.converter.core.Formats;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JComboBox;
import javax.swing.JLabel;
import java.util.List;

import static com.converter.TestTasks.field;
import static com.converter.TestTasks.onEdt;
import static org.assertj.core.api.Assertions.assertThat;

/** The actions registered in the keymap, and the panel operations they drive. */
@DisplayName("Keymap actions")
class ConverterActionsTest {

    private TestTasks tasks;
    private ConverterPanel panel;

    @BeforeEach
    void createPanel() throws Exception {
        tasks = new TestTasks();
        onEdt(() -> panel = new ConverterPanel(null, new ConversionPipeline(), tasks.runner));
    }

    @AfterEach
    void disposePanel() throws Exception {
        onEdt(() -> panel.dispose());
    }

    private static List<AnAction> actions() {
        return List.of(new ConverterActions.Convert(), new ConverterActions.FormatInput(),
              new ConverterActions.CopyOutput(), new ConverterActions.OpenFile(), new ConverterActions.SaveOutput());
    }

    // update() and actionPerformed() take an event, and an event needs the
    // IDE's ActionManager, which these tests run without. What they decide
    // with is covered here: the lookup, and each action's panel operation.
    @Test @DisplayName("actions update on the EDT, and find no panel without a project")
    void noPanelWithoutAProject() throws Exception {
        onEdt(() -> {
            for (AnAction action : actions())
                assertThat(action.getActionUpdateThread()).as(action.getClass().getSimpleName())
                      .isEqualTo(ActionUpdateThread.EDT);
            assertThat(ConverterToolWindowAccess.findPanel(null)).isNull();
        });
    }

    @Test @DisplayName("Convert converts the panel's input to the selected output format")
    void convert() throws Exception {
        onEdt(() -> {
            panel.loadContent("{\"b\":1}", Formats.FMT_JSON);
            field(panel, "outputCombo", JComboBox.class).setSelectedItem(Formats.FMT_YAML);
            new ConverterActions.Convert().run(panel);
        });
        tasks.completeNext();
        ConversionPipeline pipeline = new ConversionPipeline();
        String expected = pipeline.renderFromJson(pipeline.normalizeToJson(
              "{\"b\":1}", Formats.FMT_JSON, ConversionOptions.DEFAULTS), Formats.FMT_YAML, ConversionOptions.DEFAULTS);
        onEdt(() -> assertThat(output().getText()).isEqualTo(expected));
    }

    @Test @DisplayName("Format Input formats the panel's input in place")
    void formatInput() throws Exception {
        String input = "{\"b\":[1,2]}";
        onEdt(() -> {
            panel.loadContent(input, Formats.FMT_JSON);
            new ConverterActions.FormatInput().run(panel);
        });
        tasks.completeNext();
        onEdt(() -> assertThat(input().getText())
              .isEqualTo(new ConversionPipeline().formatInput(input, Formats.FMT_JSON, ConversionOptions.DEFAULTS)));
    }

    @Test @DisplayName("with no output, Save Output says there is nothing to save and Copy Output does nothing")
    void nothingToSaveOrCopy() throws Exception {
        onEdt(() -> {
            new ConverterActions.SaveOutput().run(panel);
            assertThat(status()).isEqualTo("Nothing to save");
            new ConverterActions.CopyOutput().run(panel);
            assertThat(status()).isEqualTo("Nothing to save");
        });
    }

    private RSyntaxTextArea input() throws Exception { return field(panel, "inputArea", RSyntaxTextArea.class); }
    private RSyntaxTextArea output() throws Exception { return field(panel, "outputArea", RSyntaxTextArea.class); }
    private String status() throws Exception { return field(panel, "statusLabel", JLabel.class).getText(); }
}
