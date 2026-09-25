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
import com.converter.core.FormatDetector;
import com.converter.core.Formats;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

/**
 * Actions that reach the converter from the rest of the IDE — an open editor or
 * a file in the Project view — instead of requiring text to be pasted into the
 * tool window.
 */
public final class ConverterContextActions {

    private ConverterContextActions() {}

    /** Text size above which conversion runs on a background task with a progress bar. */
    private static final int BACKGROUND_THRESHOLD_CHARS = 100_000;

    // ── Shared context extraction ────────────────────────────────────────

    /**
     * What an action should operate on. Either {@code text} is already in hand
     * (an editor selection or document, both in memory) or {@code file} has to be
     * read — which is deliberately deferred, because reading a file on the EDT is
     * exactly what freezes the IDE on a large one.
     */
    private record Source(String text, VirtualFile file, String name) {

        /** Size in characters if known without reading, else the file's byte length. */
        long approximateSize() {
            return text != null ? text.length() : file.getLength();
        }

        /**
         * Resolves the text. Must not be called on the EDT when backed by a file.
         *
         * <p>An unsaved editor Document wins over the bytes on disk: autosave is
         * off by default and does not fire when focus moves to the Project view,
         * so reading the file directly produced a silently stale conversion with
         * an unbounded staleness window. {@code LoadTextUtil} is used for the
         * on-disk case so the file's own charset is honoured rather than assumed
         * to be UTF-8.
         */
        String resolve() {
            if (text != null) return text;
            // runReadAction(Computable), not ReadAction.compute(ThrowableComputable):
            // the latter is deprecated as of 2026.2 and the verifier flags it.
            return ApplicationManager.getApplication().runReadAction(
                  (com.intellij.openapi.util.Computable<String>) () -> {
                      var document = com.intellij.openapi.fileEditor.FileDocumentManager
                            .getInstance().getCachedDocument(file);
                      if (document != null) return document.getText();
                      return com.intellij.openapi.fileEditor.impl.LoadTextUtil
                            .loadText(file).toString();
                  });
        }
    }

    /**
     * Editor selection first, then the whole editor document, then the selected
     * file. Runs on the EDT, so it only ever touches in-memory state.
     */
    private static Source sourceFrom(AnActionEvent e) {
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
        String name = file == null ? null : file.getName();

        if (editor != null) {
            String selected = editor.getSelectionModel().getSelectedText();
            if (selected != null && !selected.isBlank()) return new Source(selected, null, name);
            String all = editor.getDocument().getText();
            if (!all.isBlank()) return new Source(all, null, name);
        }

        if (file != null && !file.isDirectory() && file.isValid()) {
            return new Source(null, file, name);
        }
        return null;
    }

    /** Prefix of an editor document sniffed when the file name says nothing. */
    private static final int SNIFF_PREFIX_CHARS = 4_096;

    /**
     * True when the context is something the plugin can actually read. A bare
     * "is there an editor" check put both entries in every popup, so a .java
     * file (detected as Protobuf via {@code package x.y;}) or gradle.properties
     * (detected as TOML) offered a conversion that could only fail — and prose
     * detected as YAML silently produced a junk scratch file.
     */
    private static boolean isConvertible(AnActionEvent e) {
        VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
        if (file != null && !file.isDirectory()) {
            if (Formats.inputForFileName(file.getName()) != null) return true;
        }
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        if (editor == null) return false;
        // No usable extension: only offer when the content itself is recognisable.
        // A bounded prefix keeps update() cheap on a large document.
        String selected = editor.getSelectionModel().getSelectedText();
        String sample = selected != null && !selected.isBlank() ? selected
              : editor.getDocument().getText(new com.intellij.openapi.util.TextRange(0,
                    Math.min(SNIFF_PREFIX_CHARS, editor.getDocument().getTextLength())));
        return FormatDetector.detectFormat(sample) != null;
    }

    /** Extension wins when there is one; otherwise fall back to sniffing the text. */
    private static String formatFor(String fileName, String text) {
        String byExtension = Formats.inputForFileName(fileName);
        return byExtension != null ? byExtension : FormatDetector.detectFormat(text);
    }

    private static void notifyError(Project project, String message) {
        ConverterNotifications.error(project, "Be Water: conversion failed", message);
    }

    // ── Open in the tool window ──────────────────────────────────────────

    /**
     * Loads the current editor selection, editor contents, or selected file into
     * the converter panel and focuses the tool window.
     */
    public static class SendToConverter extends AnAction implements DumbAware {

        @Override public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }

        @Override public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabledAndVisible(e.getProject() != null && isConvertible(e));
        }

        @Override public void actionPerformed(@NotNull AnActionEvent e) {
            Project project = e.getProject();
            Source source = sourceFrom(e);
            if (project == null || source == null) return;
            // The same question the toolbar's Open asks: the panel's editor is
            // what slows down on a large document, whichever way it arrives.
            if (source.approximateSize() > ConverterFileOps.LARGE_FILE_WARNING_BYTES
                  && !ConverterDialogs.confirm(project, null, "Large File",
                        String.format("%s is %,d MB. Loading large files may be slow. Continue?",
                              describe(source), source.approximateSize() / (1024 * 1024))))
                return;
            withResolvedText(project, source, "Loading " + describe(source), text ->
                  ConverterToolWindowAccess.withPanel(project,
                        panel -> panel.loadContent(text, formatFor(source.name(), text))));
        }
    }

    private static String describe(Source source) {
        return source.name() == null ? "selection" : source.name();
    }

    /**
     * Hands {@code onText} the source's text on the EDT, reading the file on a
     * background task first when the source is a file large enough that reading
     * it inline could stall the UI.
     */
    private static void withResolvedText(Project project, Source source, String title,
          java.util.function.Consumer<String> onText) {
        if (source.text() != null) {
            onText.accept(source.text());
            return;
        }
        if (source.approximateSize() < BACKGROUND_THRESHOLD_CHARS) {
            try {
                onText.accept(source.resolve());
            } catch (Exception unreadable) {
                notifyError(project, "Could not read " + describe(source) + ": "
                      + ConverterNotifications.describe(unreadable));
            }
            return;
        }
        ProgressManager.getInstance().run(new Task.Backgroundable(project, title, true) {
            @Override public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                final String text;
                try {
                    text = source.resolve();
                } catch (Exception unreadable) {
                    ApplicationManager.getApplication().invokeLater(
                          () -> notifyError(project, "Could not read " + describe(source) + ": "
                                + ConverterNotifications.describe(unreadable)),
                          project.getDisposed());
                    return;
                }
                ApplicationManager.getApplication().invokeLater(
                      () -> onText.accept(text), project.getDisposed());
            }
        });
    }

    // ── Convert straight to a scratch file ───────────────────────────────

    /**
     * Submenu listing every output format. Built dynamically so adding a format
     * to the pipeline does not mean adding another action to plugin.xml.
     */
    public static class ConvertToGroup extends DefaultActionGroup implements DumbAware {

        public ConvertToGroup() {
            setPopup(true);
            for (String target : Formats.outputNames()) add(new ConvertTo(target));
        }

        @Override public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }

        @Override public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabledAndVisible(e.getProject() != null && isConvertible(e));
        }
    }

    /** Converts the context to one target format and opens the result as a scratch file. */
    public static class ConvertTo extends AnAction implements DumbAware {

        private final String target;

        ConvertTo(String target) {
            super(target);
            this.target = target;
        }

        @Override public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }

        /**
         * Greys out the file's own format. It was offered, and picking it
         * reported that input and output were the same format. Only a format
         * the file name gives away is known here; sniffed content is not
         * read on every menu update.
         */
        @Override public void update(@NotNull AnActionEvent e) {
            VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
            String own = file == null || file.isDirectory() ? null : Formats.inputForFileName(file.getName());
            e.getPresentation().setEnabled(!target.equals(own));
        }

        @Override public void actionPerformed(@NotNull AnActionEvent e) {
            Project project = e.getProject();
            Source source = sourceFrom(e);
            if (project == null || source == null) return;

            withResolvedText(project, source, "Reading " + describe(source), text -> {
                String inputFormat = formatFor(source.name(), text);
                if (inputFormat == null) {
                    notifyError(project, "Could not determine the input format of "
                          + describe(source)
                          + ". Open it in the Be Water tool window to choose one.");
                    return;
                }
                if (inputFormat.equals(target)) {
                    notifyError(project, "Input and output format are both " + target + ".");
                    return;
                }
                // Conversion always runs off the EDT: even a modest document can
                // take real time through the POJO or cross-join paths.
                ProgressManager.getInstance().run(
                      new Task.Backgroundable(project, "Converting to " + target, true) {
                          @Override public void run(@NotNull ProgressIndicator indicator) {
                              indicator.setIndeterminate(true);
                              runConversion(project, source.name(), text, inputFormat, indicator);
                          }
                      });
            });
        }

        private void runConversion(Project project, String sourceName, String text,
              String inputFormat, ProgressIndicator indicator) {
            String result;
            try {
                ConversionPipeline pipeline = new ConversionPipeline();
                // The user's own choices, not DEFAULTS: converting from the
                // Project view otherwise read a semicolon CSV as comma-separated
                // and ignored Sort keys, Detect dates and Lombok, so the same
                // document converted differently depending on the entry point.
                // The subtree filter is deliberately not carried over — it
                // belongs to the document open in the panel.
                ConversionOptions options = ConverterSettings.options();
                if (Formats.FMT_CSV.equals(inputFormat)) {
                    // The document's own delimiter beats the remembered one: a
                    // semicolon file read with the comma setting is one column wide.
                    Character delimiter = FormatDetector.detectCsvDelimiter(text);
                    if (delimiter != null)
                        options = options.withCsvFormat(
                              com.converter.core.CsvConverter.CsvFormat.forDelimiter(delimiter));
                }
                String pivot = pipeline.normalizeToJson(text, inputFormat, options);
                indicator.checkCanceled();
                // The same row-count confirmation the tool window gives: this
                // path went straight to the Cartesian product, so a CROSS_JOIN
                // over a few nested arrays could run away with nothing asked.
                if (Formats.FMT_CSV.equals(target)) {
                    long estimate = pipeline.estimateCsvRows(pipeline.parseJson(pivot), options.csvMode());
                    if (estimate > ConverterSettings.rowWarningThreshold()
                          && !confirmRows(project, options.csvMode(), estimate)) return;
                    indicator.checkCanceled();
                }
                result = pipeline.renderFromJson(pivot, target, options);
                // Checked again: cancelling during the render used to do nothing
                // and the result was delivered anyway, so the progress bar
                // advertised a Cancel that did not cancel.
                indicator.checkCanceled();
            } catch (com.intellij.openapi.progress.ProcessCanceledException cancelled) {
                throw cancelled;
            } catch (Exception failure) {
                String message = ConverterNotifications.describe(failure);
                ApplicationManager.getApplication().invokeLater(
                      () -> notifyError(project, message), project.getDisposed());
                return;
            }
            String finalResult = result;
            deliver(project, sourceName, finalResult);
        }

        /** Asks on the EDT from the background task; false when declined or the project is gone. */
        private static boolean confirmRows(Project project, com.converter.core.CsvConverter.CsvMode mode,
              long estimate) {
            java.util.concurrent.atomic.AtomicBoolean proceed = new java.util.concurrent.atomic.AtomicBoolean(false);
            if (project.isDisposed()) return false;
            ApplicationManager.getApplication().invokeAndWait(() -> proceed.set(
                  ConverterDialogs.confirm(project, null, "Row Count Warning",
                        String.format("%s will produce ~%,d rows. Continue?", mode, estimate))));
            return proceed.get();
        }

        private void deliver(Project project, String sourceName, String finalResult) {
            // Guarded on the project, not the application: closing the project
            // mid-conversion otherwise reached CommandProcessor with a disposed
            // project, which surfaces as an IDE internal-error report while the
            // scratch file is never created.
            ApplicationManager.getApplication().invokeLater(() -> {
                try {
                    // Declining the size confirmation is a choice, not a failure,
                    // so only a genuine refusal is reported.
                    ConverterScratchFiles.Result opened = ConverterScratchFiles
                          .openAsScratch(project, sourceName, target, finalResult);
                    if (!opened.declined() && !opened.created())
                        notifyError(project, "Could not create a scratch file for the "
                              + target + " result.");
                } catch (com.intellij.openapi.progress.ProcessCanceledException cancelled) {
                    throw cancelled;
                } catch (Throwable failure) {
                    // Escaping this runnable would reach IdeEventQueue and be
                    // reported as an IDE internal error rather than as ours.
                    notifyError(project, "Could not open the " + target + " result: "
                          + ConverterNotifications.describe(failure));
                }
            }, project.getDisposed());
        }
    }
}
