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

import com.converter.converter.ConversionFileNames;
import com.intellij.ide.scratch.ScratchRootType;
import com.intellij.lang.Language;

import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.fileTypes.LanguageFileType;
import com.intellij.openapi.fileTypes.PlainTextLanguage;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;

import java.util.Locale;

/**
 * Opens conversion results as scratch files. A scratch buffer gives the result
 * the IDE's own editor — real highlighting, the user's keymap, folding, and
 * Save As — which the plugin's embedded editor cannot match.
 */
final class ConverterScratchFiles {

    private ConverterScratchFiles() {}

    /**
     * Above this size, creating the scratch file is confirmed first. The write
     * runs in a write command action, which the platform only runs on the EDT,
     * so it cannot be moved off it — the freeze is unavoidable and the most
     * that can be done is to announce it and let the user decline.
     */
    private static final int CONFIRM_ABOVE_CHARS = 1_000_000;

    /**
     * What came of a scratch request. Declining the size confirmation and the
     * platform refusing to create the file both leave no file, but only one of
     * them is a failure worth reporting.
     */
    record Result(VirtualFile file, boolean declined) {
        boolean created() { return file != null; }
    }

    /**
     * Writes {@code text} to a new scratch file and opens it.
     *
     * <p>The size confirmation lives here rather than in the callers because a
     * caller that forgets it freezes the IDE, and there was already one of each:
     * the tool window asked, the context menu did not.
     *
     * <p>Must be called on the EDT — it creates the file in a write command
     * action and may show a modal dialog.
     */
    static Result openAsScratch(Project project, String sourceName, String format, String text) {
        if (text.length() > CONFIRM_ABOVE_CHARS && !confirmLarge(project, text.length()))
            return new Result(null, true);
        String name = ConversionFileNames.nameFor(sourceName, format);
        Language language = languageForExtension(ConversionFileNames.extensionFor(format));
        // createScratchFile runs its own write command action, so wrapping it in
        // another added no locking — it only held the exclusive write lock across
        // the editor open and the platform's own IOException dialog. Opening
        // outside any write action lets openFile use its WriteIntentReadAction
        // mode, which still permits concurrent background reads.
        VirtualFile file = ScratchRootType.getInstance()
              .createScratchFile(project, name, language, text);
        if (file != null) FileEditorManager.getInstance(project).openFile(file, true);
        return new Result(file, false);
    }

    private static boolean confirmLarge(Project project, int length) {
        return Messages.showYesNoDialog(project,
              String.format("The result is %,d characters. Writing it to a scratch file "
                    + "will block the IDE until it is done. Continue?", length),
              "Large Result", Messages.getWarningIcon()) == Messages.YES;
    }

    /**
     * Resolves a language through the registered file types rather than by
     * hard-coded language ID: which languages exist depends on the IDE and the
     * plugins installed (Protobuf and TOML in particular are not always there).
     */
    private static Language languageForExtension(String extension) {
        FileType type = FileTypeManager.getInstance()
              .getFileTypeByExtension(extension.toLowerCase(Locale.ROOT));
        if (type instanceof LanguageFileType languageFileType) {
            return languageFileType.getLanguage();
        }
        return PlainTextLanguage.INSTANCE;
    }
}
