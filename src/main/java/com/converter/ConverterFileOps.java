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
import com.converter.converter.ConversionPipeline;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * File open/save/drag-and-drop operations for the converter, using the IDE's
 * native file choosers (which remember the last-used directory and handle
 * overwrite confirmation). Reads run off the EDT.
 */
final class ConverterFileOps {

    /** Files larger than this trigger a confirmation before loading (whole pipeline is in-memory). */
    private static final long LARGE_FILE_WARNING_BYTES = 10L * 1024 * 1024;

    private static final Set<String> SUPPORTED_EXTENSIONS =
          Set.of("json", "xml", "yaml", "yml", "csv", "toml", "proto");

    // Extension mapping lives in ConversionFileNames. A private copy of it here
    // already went stale once: Kotlin was added there and not here, so Save
    // Output wrote Kotlin results as output.txt.

    /** How the panel receives results; every call arrives on the EDT. */
    interface Host {
        void status(String message, boolean ok);
        void loaded(String content, String detectedFormatOrNull, String fileName);
    }

    private final JComponent parent;
    private final Project project;
    private final BooleanSupplier disposed;
    private final Host host;

    ConverterFileOps(JComponent parent, Project project, BooleanSupplier disposed, Host host) {
        this.parent   = parent;
        this.project  = project;
        this.disposed = disposed;
        this.host     = host;
    }

    void openFile() {
        FileChooserDescriptor descriptor =
              new FileChooserDescriptor(true, false, false, false, false, false)
                    .withTitle("Open Input File")
                    .withFileFilter(vf -> vf.getExtension() != null
                          && SUPPORTED_EXTENSIONS.contains(
                                vf.getExtension().toLowerCase(Locale.ROOT)));
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
              .save((java.nio.file.Path) null, "output." + ext);
        if (wrapper == null) return;
        File file = wrapper.getFile();
        host.status("Saving " + file.getName() + "…", true);
        // Written off the EDT for the same reason reads are: a large output or a
        // slow network target would otherwise freeze the IDE.
        runOffEdt(() -> {
            try {
                Files.writeString(file.toPath(), output, StandardCharsets.UTF_8);
                return null;
            } catch (IOException ex) {
                throw new java.util.concurrent.CompletionException(ex);
            }
        }, (ignored, cause) -> {
            if (cause != null) host.status("Failed to save: " + cause.getMessage(), false);
            else               host.status("Saved to " + file.getName(), true);
        });
    }

    void loadFile(File file) {
        long size = file.length();
        if (size > LARGE_FILE_WARNING_BYTES) {
            int choice = JOptionPane.showConfirmDialog(parent,
                  String.format("%s is %,d MB. Loading large files may be slow. Continue?",
                        file.getName(), size / (1024 * 1024)),
                  "Large file", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.OK_OPTION) return;
        }

        host.status("Loading " + file.getName() + "…", true);
        // Read off the EDT so a large or slow-network file cannot freeze the IDE.
        runOffEdt(() -> {
            try {
                return Files.readString(file.toPath(), StandardCharsets.UTF_8);
            } catch (IOException ex) {
                throw new java.util.concurrent.CompletionException(ex);
            }
        }, (content, cause) -> {
            if (cause != null) {
                host.status("Failed to open file: " + cause.getMessage(), false);
                return;
            }
            host.loaded(content, detectFormat(file.getName()), file.getName());
        });
    }

    /**
     * Runs {@code work} on the shared application pool and delivers its result —
     * or the unwrapped failure — to {@code onDone} on the EDT. Nothing is
     * delivered once the owning panel has been disposed.
     */
    private <T> void runOffEdt(Supplier<T> work, BiConsumer<T, Throwable> onDone) {
        java.util.concurrent.CompletableFuture
              .supplyAsync(work, com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService())
              .whenComplete((result, error) ->
                    com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(() -> {
                        if (disposed.getAsBoolean()) return;
                        Throwable cause = error == null ? null
                              : (error.getCause() != null ? error.getCause() : error);
                        onDone.accept(result, cause);
                    }));
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
        };
    }

    private static String detectFormat(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String ext = dot >= 0 ? fileName.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        return switch (ext) {
            case "json"        -> ConversionPipeline.FMT_JSON;
            case "xml"         -> ConversionPipeline.FMT_XML;
            case "yaml", "yml" -> ConversionPipeline.FMT_YAML;
            case "csv"         -> ConversionPipeline.FMT_CSV;
            case "toml"        -> ConversionPipeline.FMT_TOML;
            case "proto"       -> ConversionPipeline.FMT_PROTO;
            default            -> null;
        };
    }
}
