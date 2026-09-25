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

import com.converter.core.ConversionFileNames;
import com.converter.core.Formats;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileWrapper;

import javax.swing.JComponent;
import javax.swing.JOptionPane;
import javax.swing.TransferHandler;
import java.awt.datatransfer.DataFlavor;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * File open/save/drag-and-drop operations for the converter, using the IDE's
 * native file choosers (which remember the last-used directory and handle
 * overwrite confirmation). Reads run off the EDT.
 */
final class ConverterFileOps {

    /**
     * Files larger than this trigger a confirmation before loading (whole
     * pipeline is in-memory). Shared with the context-menu entry point, which
     * loads the same editor and used to skip the question.
     */
    static final long LARGE_FILE_WARNING_BYTES = 10L * 1024 * 1024;

    /** File content, and a note on how it was read when that was not plain UTF-8. */
    private record Loaded(String text, String note) {}

    /** How the panel receives results; every call arrives on the EDT. */
    interface Host {
        void status(String message, boolean ok);
        void loaded(String content, String detectedFormatOrNull, String fileName);
        long inputRevision();
    }

    private final JComponent parent;
    private final Project project;
    private final BooleanSupplier disposed;
    private final Host host;
    private final BackgroundTasks tasks;
    private long loadRequest;

    ConverterFileOps(JComponent parent, Project project, BooleanSupplier disposed, Host host) {
        this(parent, project, disposed, host, BackgroundTasks.forIde());
    }

    ConverterFileOps(JComponent parent, Project project, BooleanSupplier disposed, Host host,
          BackgroundTasks tasks) {
        this.parent   = parent;
        this.project  = project;
        this.disposed = disposed;
        this.host     = host;
        this.tasks    = tasks;
    }

    void openFile() {
        FileChooserDescriptor descriptor =
              new FileChooserDescriptor(true, false, false, false, false, false)
                    .withTitle("Open Input File")
                    .withFileFilter(vf -> Formats.inputForFileName(vf.getName()) != null);
        VirtualFile chosen = FileChooser.chooseFile(descriptor, project, null);
        if (chosen != null) loadFile(new File(chosen.getPath()));
    }

    void saveOutput(String output, String outputFormat) {
        String ext = ConversionFileNames.extensionFor(outputFormat);
        // Single-extension constructor; the varargs overload is deprecated
        // since 2025.1. Requires sinceBuild >= 251.
        VirtualFileWrapper wrapper = FileChooserFactory.getInstance()
              .createSaveFileDialog(
                    new FileSaverDescriptor("Save Output", "Save the converter output", ext),
                    project)
              // The same name the scratch files use: for Java the file has to
              // be Root.java to match the public class it holds, and
              // "output.java" was flagged by the IDE the moment it was opened.
              .save((java.nio.file.Path) null, ConversionFileNames.nameFor(null, outputFormat));
        if (wrapper == null) return;
        File file = wrapper.getFile();
        host.status("Saving " + file.getName() + "…", true);
        // Written off the EDT for the same reason reads are: a large output or a
        // slow network target would otherwise freeze the IDE.
        runOffEdt(() -> {
            try {
                AtomicFileWriter.write(file.toPath(), output);
                return null;
            } catch (IOException ex) {
                throw new java.util.concurrent.CompletionException(ex);
            }
        }, (ignored, cause) -> {
            if (cause != null) {
                host.status("Failed to save: " + cause.getMessage(), false);
                return;
            }
            host.status("Saved to " + file.getName(), true);
            // The write went behind the VFS's back, so tell it. Without this a
            // file saved into the project was not listed, and an editor already
            // showing it kept the old text, until something else refreshed.
            refreshInVfs(file);
        });
    }

    private static void refreshInVfs(File file) {
        try {
            com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                  .refreshIoFiles(List.of(file), true, false, null);
        } catch (Throwable outsideIde) {
            // No VFS to tell (tests, standalone).
        }
    }

    void loadFile(File file) {
        if (disposed.getAsBoolean()) return;
        // An editor holding unsaved changes to this file has the newer text.
        // Autosave is off by default and does not fire when focus moves to the
        // tool window, so reading the disk copy loaded a silently stale document.
        // The context-menu path already prefers the editor for the same reason.
        String unsaved = unsavedEditorText(file);
        long size = unsaved != null ? unsaved.length() : file.length();
        if (size > LARGE_FILE_WARNING_BYTES) {
            int choice = JOptionPane.showConfirmDialog(parent,
                  String.format("%s is %,d MB. Loading large files may be slow. Continue?",
                        file.getName(), size / (1024 * 1024)),
                  "Large file", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.OK_OPTION) return;
        }
        // Only the newest accepted request may replace the input. The revision
        // also changes on edits and explicit Clear/Swap/History operations.
        long request = ++loadRequest;
        long revision = host.inputRevision();
        if (unsaved != null) {
            host.loaded(unsaved, Formats.inputForFileName(file.getName()),
                  file.getName() + " (unsaved editor contents)");
            return;
        }

        host.status("Loading " + file.getName() + "…", true);
        // Read off the EDT so a large or slow-network file cannot freeze the IDE.
        runOffEdt(() -> {
            try {
                // By its byte-order mark first: read as UTF-8 with a Latin-1
                // fallback, a UTF-16 file opened as NUL-interleaved garbage.
                var decoded = com.converter.core.TextDecoder.decode(
                      Files.readAllBytes(file.toPath()));
                String note = decoded.fallback()
                      ? " (not valid UTF-8 — read as ISO-8859-1)"
                      : decoded.charset().equals(StandardCharsets.UTF_8) ? ""
                      : " (read as " + decoded.charset().name() + ")";
                return new Loaded(decoded.text(), note);
            } catch (IOException ex) {
                throw new java.util.concurrent.CompletionException(ex);
            }
        }, (content, cause) -> {
            if (request != loadRequest) return;
            if (revision != host.inputRevision()) {
                host.status("Input changed while loading; the file was not applied", false);
                return;
            }
            if (cause != null) {
                host.status("Failed to open file: " + cause.getMessage(), false);
                return;
            }
            host.loaded(content.text(), Formats.inputForFileName(file.getName()), file.getName() + content.note());
        });
    }

    /**
     * The text of {@code file} as it stands in an open editor, when that editor
     * holds changes not yet written to disk; null otherwise, including outside
     * a running IDE. Only an unsaved document is preferred: once saved, the disk
     * copy is the same text and keeps the Latin-1 fallback for odd encodings.
     */
    private static String unsavedEditorText(File file) {
        try {
            VirtualFile virtualFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                  .findFileByIoFile(file);
            if (virtualFile == null) return null;
            var manager = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance();
            var document = manager.getCachedDocument(virtualFile);
            if (document == null || !manager.isDocumentUnsaved(document)) return null;
            return com.intellij.openapi.application.ApplicationManager.getApplication()
                  .runReadAction((com.intellij.openapi.util.Computable<String>) document::getText);
        } catch (Throwable outsideIde) {
            return null;
        }
    }

    /**
     * Runs {@code work} on the shared application pool and delivers its result —
     * or the unwrapped failure — to {@code onDone} on the EDT. Nothing is
     * delivered once the owning panel has been disposed.
     */
    private <T> void runOffEdt(Supplier<T> work, BiConsumer<T, Throwable> onDone) {
        tasks.submit(work, (result, error) -> {
            if (!disposed.getAsBoolean()) onDone.accept(result, error);
        });
    }

    /** Wraps an existing TransferHandler so dropped files load into the input editor. */
    TransferHandler chainFileDrop(TransferHandler original) {
        return new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                if (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return true;
                return original != null && original.canImport(support);
            }

            @Override
            public boolean importData(TransferSupport support) {
                if (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    try {
                        @SuppressWarnings("unchecked")
                        List<File> files = (List<File>) support.getTransferable()
                              .getTransferData(DataFlavor.javaFileListFlavor);
                        if (!files.isEmpty()) loadFile(files.get(0));
                        return true;
                    } catch (Exception ex) {
                        host.status("Drop failed: " + ex.getMessage(), false);
                        return false;
                    }
                }
                return original != null && original.importData(support);
            }

            // The export half must be delegated too. TransferHandler's defaults
            // are "no source actions, nothing to transfer", so overriding only
            // the import half left Copy, Cut and drag-out dead in the editor.
            //
            // Delegating the PUBLIC entry points is what does it: each one calls
            // the original's own protected createTransferable/exportDone, which
            // a subclass cannot reach across instances anyway.
            @Override public int getSourceActions(JComponent c) {
                return original == null ? NONE : original.getSourceActions(c);
            }

            @Override public void exportAsDrag(JComponent c, java.awt.event.InputEvent e, int action) {
                if (original != null) original.exportAsDrag(c, e, action);
            }

            @Override public void exportToClipboard(JComponent c,
                  java.awt.datatransfer.Clipboard clip, int action) {
                if (original != null) original.exportToClipboard(c, clip, action);
                else super.exportToClipboard(c, clip, action);
            }
        };
    }

}
