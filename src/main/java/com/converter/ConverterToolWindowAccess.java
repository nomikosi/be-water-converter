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

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;

import javax.swing.JComponent;
import java.util.function.Consumer;

/**
 * Locating the converter panel from an action. The panel registers itself as a
 * client property on its root component, so the lookup walks the tool window's
 * contents rather than keeping a static reference that would leak across
 * projects.
 */
final class ConverterToolWindowAccess {

    static final String TOOL_WINDOW_ID = "Be Water";

    private ConverterToolWindowAccess() {}

    /** The panel if the tool window is already open, otherwise null. */
    static ConverterPanel findPanel(Project project) {
        if (project == null) return null;
        ToolWindow tw = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID);
        return tw == null ? null : panelOf(tw);
    }

    /**
     * Opens and focuses the tool window, then hands the panel to {@code action}.
     * Content is created lazily on first activation, so the callback has to run
     * after activate() rather than before it.
     */
    static void withPanel(Project project, Consumer<ConverterPanel> action) {
        if (project == null) return;
        ToolWindow tw = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID);
        if (tw == null) return;
        tw.activate(() -> {
            ConverterPanel panel = panelOf(tw);
            if (panel != null) action.accept(panel);
        }, true, true);
    }

    private static ConverterPanel panelOf(ToolWindow toolWindow) {
        for (Content content : toolWindow.getContentManager().getContents()) {
            if (content.getComponent() instanceof JComponent c
                  && c.getClientProperty(ConverterPanel.PANEL_CLIENT_PROPERTY)
                        instanceof ConverterPanel panel) {
                return panel;
            }
        }
        return null;
    }
}
