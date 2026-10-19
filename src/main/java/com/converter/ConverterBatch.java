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

import com.converter.core.BatchPlan;
import com.converter.core.ConversionOptions;
import com.converter.core.ConversionPipeline;
import com.converter.core.Formats;
import com.converter.core.TextEncoder;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Converts several files selected in the Project view at once, each result
 * written beside its source. {@link BatchPlan} decides what is written and
 * what is skipped; this reads, converts and writes, then reports it all in
 * one notification.
 */
final class ConverterBatch {

    private ConverterBatch() {}

    /** A converted file, ready to write. */
    private record Result(VirtualFile source, String outputName, String text) {}

    /** A file left alone, and why. */
    private record Note(String name, String reason) {}

    static void run(Project project, List<VirtualFile> files, String target) {
        ProgressManager.getInstance().run(new Task.Backgroundable(project,
              "Converting " + files.size() + " files to " + target, true) {
            @Override public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(false);
                List<String> texts = new ArrayList<>();
                List<BatchPlan.Source> sources = new ArrayList<>();
                List<Note> failed = new ArrayList<>();
                for (int i = 0; i < files.size(); i++) {
                    indicator.checkCanceled();
                    VirtualFile file = files.get(i);
                    indicator.setText2(file.getName());
                    indicator.setFraction(i / (2.0 * files.size()));
                    String text;
                    try {
                        text = new ConverterContextActions.Source(null, file, file.getName()).resolve();
                    } catch (IOException unreadable) {
                        failed.add(new Note(file.getName(), "could not be read: "
                              + ConverterNotifications.describe(unreadable)));
                        text = null;
                    }
                    texts.add(text);
                    String parent = file.getParent() == null ? "" : file.getParent().getPath();
                    sources.add(new BatchPlan.Source(parent, file.getName(),
                          text == null ? null : ContextDecisions.formatFor(file.getName(), text)));
                }

                List<Note> skipped = new ArrayList<>();
                List<Result> results = new ArrayList<>();
                List<BatchPlan.Step> steps = BatchPlan.plan(sources, target);
                ConversionPipeline pipeline = new ConversionPipeline();
                for (int i = 0; i < steps.size(); i++) {
                    indicator.checkCanceled();
                    BatchPlan.Step step = steps.get(i);
                    String name = step.source().name();
                    indicator.setText2(name);
                    indicator.setFraction(0.5 + i / (2.0 * steps.size()));
                    if (texts.get(i) == null) continue;               // reported as unreadable
                    if (!step.converts()) {
                        skipped.add(new Note(name, step.skipped()));
                        continue;
                    }
                    try {
                        results.add(new Result(files.get(i), step.outputName(),
                              convert(pipeline, name, texts.get(i), step.source().format(), target)));
                    } catch (com.intellij.openapi.progress.ProcessCanceledException cancelled) {
                        throw cancelled;
                    } catch (Exception failure) {
                        failed.add(new Note(name, ConverterNotifications.describe(failure)));
                    }
                }
                ApplicationManager.getApplication().invokeLater(
                      () -> write(project, target, results, skipped, failed), project.getDisposed());
            }
        });
    }

    /** As the single-file conversion converts: the user's options, and the document's own CSV delimiter. */
    private static String convert(ConversionPipeline pipeline, String name, String text, String format,
          String target) throws Exception {
        ConversionOptions options = ConverterSettings.options();
        if (Formats.FMT_CSV.equals(format))
            options = options.withCsvFormat(ContextDecisions.csvFormatFor(name, text, options.csvFormat()));
        String pivot = pipeline.normalizeToJson(text, format, options);
        if (Formats.FMT_CSV.equals(target)) {
            long estimate = pipeline.estimateCsvRows(pipeline.parseJson(pivot), options.csvMode());
            // Refused rather than asked file by file: a question per file of a
            // batch is a dialog the user has to sit through.
            if (estimate > ConverterSettings.rowWarningThreshold())
                throw new IllegalArgumentException(String.format(
                      "it would make ~%,d rows, past the row warning of %,d; convert it on its own to go ahead",
                      estimate, ConverterSettings.rowWarningThreshold()));
        }
        return pipeline.renderFromJson(pivot, target, options);
    }

    private static void write(Project project, String target, List<Result> results, List<Note> skipped,
          List<Note> failed) {
        List<Result> writing = new ArrayList<>();
        List<String> replacing = new ArrayList<>();
        FileDocumentManager documents = FileDocumentManager.getInstance();
        for (Result result : results) {
            VirtualFile existing = result.source().getParent() == null ? null
                  : result.source().getParent().findChild(result.outputName());
            if (existing != null) {
                var document = documents.getCachedDocument(existing);
                if (document != null && documents.isDocumentUnsaved(document)) {
                    skipped.add(new Note(result.source().getName(), result.outputName()
                          + " is open with unsaved changes"));
                    continue;
                }
                replacing.add(result.outputName());
            }
            writing.add(result);
        }
        if (!replacing.isEmpty()) {
            int answer = Messages.showYesNoCancelDialog(project,
                  (replacing.size() == 1 ? replacing.getFirst() + " exists" : replacing.size() + " files exist")
                        + " and would be replaced:\n\n" + String.join("\n", replacing),
                  "Replace Files", "Replace", "Skip Them", "Cancel", Messages.getWarningIcon());
            if (answer == Messages.CANCEL) return;
            if (answer == Messages.NO) {
                for (Result result : List.copyOf(writing)) {
                    if (replacing.contains(result.outputName())
                          && result.source().getParent().findChild(result.outputName()) != null) {
                        writing.remove(result);
                        skipped.add(new Note(result.source().getName(), result.outputName() + " exists"));
                    }
                }
            }
        }

        String separator = newFileLineSeparator(project);
        List<String> written = new ArrayList<>();
        try {
            WriteCommandAction.writeCommandAction(project).withName("Convert to " + target).run(() -> {
                for (Result result : writing) {
                    VirtualFile directory = result.source().getParent();
                    VirtualFile existing = directory.findChild(result.outputName());
                    // Encoded as Save encodes: a file replaced keeps its line breaks
                    // and, for CSV and source files, its encoding.
                    TextEncoder.Encoded encoded = TextEncoder.forSave(result.text(), target,
                          existing == null ? null : head(existing), existing == null ? null : existing.getCharset(),
                          separator);
                    VirtualFile out = existing != null ? existing
                          : directory.createChildData(ConverterBatch.class, result.outputName());
                    out.setBinaryContent(encoded.bytes());
                    written.add(result.outputName());
                }
            });
        } catch (IOException | RuntimeException failure) {
            failed.add(new Note(written.size() < writing.size() ? writing.get(written.size()).outputName() : "",
                  "could not be written: " + ConverterNotifications.describe(failure)));
        }
        report(project, target, written, skipped, failed);
    }

    private static byte[] head(VirtualFile file) throws IOException {
        try (InputStream in = file.getInputStream()) {
            return in.readNBytes(64 * 1024);
        }
    }

    private static String newFileLineSeparator(Project project) {
        String separator = com.intellij.application.options.CodeStyle.getProjectOrDefaultSettings(project)
              .getLineSeparator();
        return separator == null ? "\n" : separator;
    }

    private static void report(Project project, String target, List<String> written, List<Note> skipped,
          List<Note> failed) {
        StringBuilder message = new StringBuilder();
        if (!written.isEmpty()) {
            message.append("Wrote ").append(String.join(", ", written.subList(0, Math.min(written.size(), SHOWN))));
            if (written.size() > SHOWN) message.append(" and ").append(written.size() - SHOWN).append(" more");
            message.append('.');
        }
        appendNotes(message, "Skipped", skipped);
        appendNotes(message, "Failed", failed);
        String title = "Be Water: converted " + written.size() + (written.size() == 1 ? " file" : " files")
              + " to " + target;
        NotificationType type = !failed.isEmpty() && written.isEmpty() ? NotificationType.ERROR
              : !failed.isEmpty() || !skipped.isEmpty() ? NotificationType.WARNING : NotificationType.INFORMATION;
        try {
            NotificationGroupManager.getInstance().getNotificationGroup(ConverterNotifications.GROUP_ID)
                  .createNotification(title, ConverterNotifications.html(message.toString()), type)
                  .notify(project);
        } catch (Throwable outsideIde) {
            // No notifications to show outside a running IDE.
        }
    }

    private static void appendNotes(StringBuilder message, String heading, List<Note> notes) {
        if (notes.isEmpty()) return;
        if (!message.isEmpty()) message.append("\n\n");
        message.append(heading).append(':');
        for (Note note : notes.subList(0, Math.min(notes.size(), SHOWN)))
            message.append("\n• ").append(note.name()).append(": ").append(note.reason());
        if (notes.size() > SHOWN) message.append("\n• and ").append(notes.size() - SHOWN).append(" more");
    }

    /** How many files a notification names before it only counts the rest. */
    private static final int SHOWN = 10;
}
