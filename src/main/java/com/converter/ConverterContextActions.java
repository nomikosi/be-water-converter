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

import com.converter.converter.ConversionOptions;
import com.converter.converter.ConversionPipeline;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
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

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Actions that reach the converter from the rest of the IDE — an open editor or
 * a file in the Project view — instead of requiring text to be pasted into the
 * tool window.
 */
public final class ConverterContextActions {

    private ConverterContextActions() {}

    /** Text size above which conversion runs on a background task with a progress bar. */
    private static final int BACKGROUND_THRESHOLD_CHARS = 100_000;

    private static final String NOTIFICATION_GROUP = "Be Water Converter";

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
            return com.intellij.openapi.application.ReadAction.compute(() -> {
                var document = com.intellij.openapi.fileEditor.FileDocumentManager
                      .getInstance().getCachedDocument(file);
                if (document != null) return document.getText();
                return com.intellij.openapi.fileEditor.impl.LoadTextUtil.loadText(file).toString();
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
            String ext = file.getExtension();
            if (ext != null && SUPPORTED_EXTENSIONS.contains(ext.toLowerCase(Locale.ROOT))) return true;
        }
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        if (editor == null) return false;
        // No usable extension: only offer when the content itself is recognisable.
        // A bounded prefix keeps update() cheap on a large document.
        String selected = editor.getSelectionModel().getSelectedText();
        String sample = selected != null && !selected.isBlank() ? selected
              : editor.getDocument().getText(new com.intellij.openapi.util.TextRange(0,
                    Math.min(SNIFF_PREFIX_CHARS, editor.getDocument().getTextLength())));
        return ConversionPipeline.detectFormat(sample) != null;
    }

    private static final java.util.Set<String> SUPPORTED_EXTENSIONS =
          java.util.Set.of("json", "xml", "yaml", "yml", "csv", "toml", "proto");

    /** Extension wins when there is one; otherwise fall back to sniffing the text. */
    private static String formatFor(String fileName, String text) {
        String byExtension = extensionFormat(fileName);
        return byExtension != null ? byExtension : ConversionPipeline.detectFormat(text);
    }

    private static String extensionFormat(String fileName) {
        if (fileName == null) return null;
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) return null;
        return switch (fileName.substring(dot + 1).toLowerCase(Locale.ROOT)) {
            case "json"        -> ConversionPipeline.FMT_JSON;
            case "xml"         -> ConversionPipeline.FMT_XML;
            case "yaml", "yml" -> ConversionPipeline.FMT_YAML;
            case "csv"         -> ConversionPipeline.FMT_CSV;
            case "toml"        -> ConversionPipeline.FMT_TOML;
            case "proto"       -> ConversionPipeline.FMT_PROTO;
            default            -> null;
        };
    }

    private static void notifyError(Project project, String message) {
        try {
            NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
                  .createNotification("Be Water: conversion failed", message, NotificationType.ERROR)
                  .notify(project);
        } catch (Throwable ignored) {
            // Notification group unavailable (e.g. headless): nothing else to do.
        }
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
                      + unreadable.getMessage());
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
                                + unreadable.getMessage()),
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

        private static final String[] TARGETS = {
              ConversionPipeline.FMT_JSON, ConversionPipeline.FMT_XML,
              ConversionPipeline.FMT_YAML, ConversionPipeline.FMT_CSV,
              ConversionPipeline.FMT_TOML, ConversionPipeline.FMT_PROTO,
              ConversionPipeline.FMT_JAVA, ConversionPipeline.FMT_SCHEMA,
        };

        public ConvertToGroup() {
            setPopup(true);
            for (String target : TARGETS) add(new ConvertTo(target));
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
                String pivot = pipeline.normalizeToJson(text, inputFormat,
                      ConversionOptions.DEFAULTS);
                indicator.checkCanceled();
                result = pipeline.renderFromJson(pivot, target, ConversionOptions.DEFAULTS);
            } catch (com.intellij.openapi.progress.ProcessCanceledException cancelled) {
                throw cancelled;
            } catch (Exception failure) {
                String message = failure.getMessage() == null
                      ? failure.getClass().getSimpleName() : failure.getMessage();
                ApplicationManager.getApplication().invokeLater(
                      () -> notifyError(project, message), project.getDisposed());
                return;
            }
            String finalResult = result;
            // Guarded on the project, not the application: closing the project
            // mid-conversion otherwise reached CommandProcessor with a disposed
            // project, which surfaces as an IDE internal-error report while the
            // scratch file is never created.
            ApplicationManager.getApplication().invokeLater(
                  () -> ConverterScratchFiles.openAsScratch(project, sourceName, target, finalResult),
                  project.getDisposed());
        }
    }
}
