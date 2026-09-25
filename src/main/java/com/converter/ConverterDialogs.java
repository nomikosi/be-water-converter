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
import com.intellij.openapi.ui.Messages;

import java.awt.Component;

/**
 * The plugin's questions, asked through the IDE's own dialogs. The tool window
 * used Swing's JOptionPane while the context menu asked the same large-file
 * question with Messages, so one question looked two ways — and a JOptionPane
 * is a modal dialog the IDE's modality tracking does not know about.
 */
final class ConverterDialogs {

    private ConverterDialogs() {}

    /**
     * Asks a warning question with Continue and Cancel; true when the user
     * continues. Parented to {@code parent} when there is one, so the dialog
     * belongs to the tool window's frame, otherwise to {@code project}.
     */
    static boolean confirm(Project project, Component parent, String title, String message) {
        int answer = parent != null
              ? Messages.showOkCancelDialog(parent, message, title, "Continue", "Cancel", Messages.getWarningIcon())
              : Messages.showOkCancelDialog(project, message, title, "Continue", "Cancel", Messages.getWarningIcon());
        return answer == Messages.OK;
    }
}
