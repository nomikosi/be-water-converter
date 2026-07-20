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

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;

/**
 * Registered IDE actions that drive the Be Water converter panel. Registering
 * them (rather than only binding Swing keystrokes) makes the operations
 * visible in Find Action and lets users assign their own shortcuts in the
 * Keymap settings. No default shortcuts are declared to avoid conflicts; the
 * panel's built-in Swing bindings remain the out-of-the-box defaults.
 */
public final class ConverterActions {

    private ConverterActions() {}

    private abstract static class PanelAction extends AnAction implements DumbAware {
        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabled(findPanel(e) != null);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            ConverterPanel panel = findPanel(e);
            if (panel != null) run(panel);
        }

        abstract void run(ConverterPanel panel);

        private static ConverterPanel findPanel(AnActionEvent e) {
            Project project = e.getProject();
            if (project == null) return null;
            ToolWindow tw = ToolWindowManager.getInstance(project).getToolWindow("Be Water");
            if (tw == null) return null;
            for (Content content : tw.getContentManager().getContents()) {
                if (content.getComponent() instanceof JComponent c
                      && c.getClientProperty(ConverterPanel.PANEL_CLIENT_PROPERTY)
                            instanceof ConverterPanel panel) {
                    return panel;
                }
            }
            return null;
        }
    }

    public static class Convert extends PanelAction {
        @Override void run(ConverterPanel panel) { panel.convert(); }
    }

    public static class FormatInput extends PanelAction {
        @Override void run(ConverterPanel panel) { panel.formatInput(); }
    }

    public static class CopyOutput extends PanelAction {
        @Override void run(ConverterPanel panel) { panel.copyOutput(); }
    }

    public static class OpenFile extends PanelAction {
        @Override void run(ConverterPanel panel) { panel.openFile(); }
    }

    public static class SaveOutput extends PanelAction {
        @Override void run(ConverterPanel panel) { panel.saveOutput(); }
    }
}
