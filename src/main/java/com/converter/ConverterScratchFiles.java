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

import com.converter.converter.ConversionPipeline;
import com.intellij.ide.scratch.ScratchRootType;
import com.intellij.lang.Language;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.fileTypes.LanguageFileType;
import com.intellij.openapi.fileTypes.PlainTextLanguage;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;

import java.util.Locale;
import java.util.Map;

/**
 * Opens conversion results as scratch files. A scratch buffer gives the result
 * the IDE's own editor — real highlighting, the user's keymap, folding, and
 * Save As — which the plugin's embedded editor cannot match.
 */
final class ConverterScratchFiles {

    private ConverterScratchFiles() {}

    private static final Map<String, String> EXTENSIONS = Map.of(
          ConversionPipeline.FMT_JSON,   "json",
          ConversionPipeline.FMT_SCHEMA, "json",
          ConversionPipeline.FMT_XML,    "xml",
          ConversionPipeline.FMT_YAML,   "yaml",
          ConversionPipeline.FMT_CSV,    "csv",
          ConversionPipeline.FMT_TOML,   "toml",
          ConversionPipeline.FMT_PROTO,  "proto",
          ConversionPipeline.FMT_JAVA,   "java");

    /** File extension a conversion result should carry. Package-private for testing. */
    static String extensionFor(String format) {
        return EXTENSIONS.getOrDefault(format, "txt");
    }

    /**
     * Scratch file name for a result, derived from the source name where there
     * is one so several conversions of different files stay tellable apart.
     */
    static String scratchNameFor(String sourceName, String format) {
        String ext = extensionFor(format);
        if (sourceName == null || sourceName.isBlank()) return "converted." + ext;
        int dot = sourceName.lastIndexOf('.');
        String base = dot > 0 ? sourceName.substring(0, dot) : sourceName;
        return base + "." + ext;
    }

    /**
     * Writes {@code text} to a new scratch file and opens it. Returns the file,
     * or null when the platform refused to create it.
     */
    static VirtualFile openAsScratch(Project project, String sourceName, String format, String text) {
        String name = scratchNameFor(sourceName, format);
        Language language = languageForExtension(extensionFor(format));
        return WriteCommandAction.writeCommandAction(project)
              .withName("Be Water: Open Conversion Result")
              .compute(() -> {
                  VirtualFile file = ScratchRootType.getInstance()
                        .createScratchFile(project, name, language, text);
                  if (file != null) FileEditorManager.getInstance(project).openFile(file, true);
                  return file;
              });
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
