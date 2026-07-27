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
     * Writes {@code text} to a new scratch file and opens it. Returns the file,
     * or null when the platform refused to create it.
     */
    static VirtualFile openAsScratch(Project project, String sourceName, String format, String text) {
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
        return file;
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
