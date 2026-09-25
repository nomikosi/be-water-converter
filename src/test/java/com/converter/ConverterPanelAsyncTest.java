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

import com.converter.core.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.swing.*;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.converter.TestTasks.*;
import static org.assertj.core.api.Assertions.*;

class ConverterPanelAsyncTest {
    @TempDir Path dir;
    private TestTasks tasks;
    private ConverterPanel panel;

    @BeforeEach void setUp() throws Exception { createPanel(new ConversionPipeline()); }
    @AfterEach void dispose() throws Exception { onEdt(() -> panel.dispose()); }

    private void createPanel(ConversionPipeline pipeline) throws Exception {
        tasks = new TestTasks();
        onEdt(() -> {
            if (panel != null) panel.dispose();
            panel = new ConverterPanel(null, pipeline, tasks.runner);
            applyOptions(ConversionOptions.DEFAULTS);
        });
    }

    @Test void formatPreservesTrailingEmptyTsvCell() throws Exception {
        onEdt(() -> {
            panel.loadContent("a\tb\nx\t", "CSV");
            applyOptions(ConversionOptions.DEFAULTS.withCsvFormat(CsvConverter.CsvFormat.TAB));
            panel.formatInput();
        });
        tasks.completeNext();
        onEdt(() -> assertThat(input().getText()).isEqualTo("a\tb\nx\t\n"));
    }

    @Test void formatPreservesYamlBlockScalarNewlines() throws Exception {
        onEdt(() -> {
            panel.loadContent("text: |+\n  hello\n\n", "YAML");
            panel.formatInput();
        });
        tasks.completeNext();
        onEdt(() -> assertThat(new JsonYamlConverter().yamlToJson(input().getText()))
              .isEqualTo("{\"text\":\"hello\\n\\n\"}"));
    }

    @Test void formattingDoesNotOverwriteWhitespaceEditsMadeBeforeDelivery() throws Exception {
        onEdt(() -> {
            panel.loadContent("{\"a\":1}", "JSON");
            panel.formatInput();
        });
        tasks.worker.runNext();
        onEdt(() -> {
            input().append("  ");
            tasks.ui.runNext();
            assertThat(input().getText()).isEqualTo("{\"a\":1}  ");
            assertThat(status()).contains("discarded");
        });
    }

    @Test void formattingDoesNotOverwriteEditsEvenIfTextWasRestored() throws Exception {
        onEdt(() -> {
            panel.loadContent("{\"a\":1}", "JSON");
            panel.formatInput();
            input().append(" ");
            input().setText("{\"a\":1}");
        });
        tasks.completeNext();
        onEdt(() -> {
            assertThat(input().getText()).isEqualTo("{\"a\":1}");
            assertThat(status()).contains("discarded");
        });
    }

    @Test void changingInputFormatInvalidatesPendingFormatting() throws Exception {
        onEdt(() -> {
            panel.loadContent("{\"a\":1}", "JSON");
            panel.formatInput();
            field(panel, "inputCombo", JComboBox.class).setSelectedItem("YAML");
        });
        tasks.completeNext();
        onEdt(() -> assertThat(status()).contains("discarded"));
    }

    @Test void cancellationBeforeWorkerStartsDoesNotRunThePipeline() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        createPanel(new ConversionPipeline() {
            @Override public String normalizeToJson(String input, String format, ConversionOptions options)
                  throws Exception {
                calls.incrementAndGet();
                return super.normalizeToJson(input, format, options);
            }
        });
        onEdt(() -> { startConversion("YAML"); call(panel, "cancelConvert"); });
        tasks.completeNext();
        assertThat(calls).hasValue(0);
        onEdt(this::assertCancelled);
    }

    @ParameterizedTest @ValueSource(strings = {"YAML", "CSV"})
    void cancellationDuringRenderingDiscardsEvenAnUncooperativeResult(String target) throws Exception {
        BlockingPipeline pipeline = new BlockingPipeline();
        createPanel(pipeline);
        onEdt(() -> startConversion(target));
        AtomicBoolean interruptedAfterTask = new AtomicBoolean();
        Thread worker = new Thread(() -> {
            tasks.worker.runNext();
            interruptedAfterTask.set(Thread.currentThread().isInterrupted());
        }, "converter-render-test");
        worker.start();
        try {
            assertThat(pipeline.entered.await(10, TimeUnit.SECONDS)).isTrue();
            onEdt(() -> call(panel, "cancelConvert"));
        } finally {
            pipeline.release.countDown();
            worker.join(10_000);
        }
        assertThat(worker.isAlive()).isFalse();
        assertThat(interruptedAfterTask).isFalse();
        onEdt(() -> { tasks.ui.runNext(); assertCancelled(); });
    }

    @Test void cancellationAfterRenderingButBeforeDeliveryDiscardsResult() throws Exception {
        onEdt(() -> startConversion("YAML"));
        tasks.worker.runNext();
        onEdt(() -> {
            call(panel, "cancelConvert");
            tasks.ui.runNext();
            assertCancelled();
        });
    }

    @Test void disposalDiscardsPendingConversion() throws Exception {
        onEdt(() -> startConversion("YAML"));
        tasks.worker.runNext();
        onEdt(() -> {
            panel.dispose();
            tasks.ui.runNext();
            assertThat(output().getText()).isEqualTo("previous output");
            assertThat(history().entries()).isEmpty();
        });
    }

    @Test void newestFileWinsWhenReadsFinishOutOfOrder() throws Exception {
        Path first = writeFile("first.json", "{\"first\":1}");
        Path second = writeFile("second.json", "{\"second\":2}");
        onEdt(() -> { fileOps().loadFile(first.toFile()); fileOps().loadFile(second.toFile()); });
        tasks.worker.runLast();
        onEdt(tasks.ui::runNext);
        tasks.completeNext();
        onEdt(() -> {
            assertThat(input().getText()).isEqualTo("{\"second\":2}");
            assertThat(status()).contains("second.json");
        });
    }

    @Test void newestRequestSupersedesAReadAlreadyAwaitingDelivery() throws Exception {
        Path first = writeFile("first.json", "{\"first\":1}");
        Path second = writeFile("second.json", "{\"second\":2}");
        onEdt(() -> fileOps().loadFile(first.toFile()));
        tasks.worker.runNext();
        onEdt(() -> { fileOps().loadFile(second.toFile()); tasks.ui.runNext(); });
        tasks.completeNext();
        onEdt(() -> assertThat(input().getText()).isEqualTo("{\"second\":2}"));
    }

    @Test void editsMadeWhileLoadingSurviveDelivery() throws Exception {
        Path file = writeFile("input.json", "{\"old\":1}");
        onEdt(() -> fileOps().loadFile(file.toFile()));
        tasks.worker.runNext();
        onEdt(() -> {
            input().setText("my newer input");
            tasks.ui.runNext();
            assertThat(input().getText()).isEqualTo("my newer input");
            assertThat(status()).contains("Input changed");
        });
    }

    @Test void clearInvalidatesLoadingEvenWhenTheInputWasAlreadyEmpty() throws Exception {
        Path file = writeFile("input.json", "{\"old\":1}");
        onEdt(() -> { fileOps().loadFile(file.toFile()); call(panel, "doClear"); });
        tasks.completeNext();
        onEdt(() -> assertThat(input().getText()).isEmpty());
    }

    @Test void disposedPanelDoesNotReceiveFileLoad() throws Exception {
        Path file = writeFile("input.json", "{\"old\":1}");
        onEdt(() -> { fileOps().loadFile(file.toFile()); panel.dispose(); });
        tasks.completeNext();
        onEdt(() -> assertThat(input().getText()).isEmpty());
    }

    @Test void failedOlderLoadDoesNotReplaceNewerStatus() throws Exception {
        Path second = writeFile("second.json", "{\"second\":2}");
        onEdt(() -> {
            fileOps().loadFile(dir.resolve("missing.json").toFile());
            fileOps().loadFile(second.toFile());
        });
        tasks.worker.runLast();
        onEdt(tasks.ui::runNext);
        tasks.completeNext();
        onEdt(() -> assertThat(status()).contains("second.json").doesNotContain("Failed"));
    }

    @Test void historyRestoresOptionsAndCanRepeatTheOriginalConversion() throws Exception {
        ConversionOptions original = ConversionOptions.DEFAULTS
              .withCsvMode(CsvConverter.CsvMode.CROSS_JOIN)
              .withCsvFormat(CsvConverter.CsvFormat.SEMICOLON)
              .withLombok(true).withDetectDates(false).withInferTypes(false)
              .withSortKeys(true).withFilterPath("/users");
        onEdt(() -> {
            panel.loadContent("{\"users\":[{\"name\":\"Ada\",\"age\":36}]}", "JSON");
            applyOptions(original);
            field(panel, "outputCombo", JComboBox.class).setSelectedItem("CSV");
            panel.convert();
            // History must keep the snapshot used by the worker, even when
            // controls change before it finishes.
            applyOptions(ConversionOptions.DEFAULTS);
        });
        tasks.completeNext();
        onEdt(() -> {
            ConversionHistory.Entry entry = history().entries().getFirst();
            assertThat(entry.options()).isEqualTo(original);
            Method restore = ConverterPanel.class.getDeclaredMethod("restoreFromHistory", ConversionHistory.Entry.class);
            restore.setAccessible(true);
            restore.invoke(panel, entry);
            assertThat(call(panel, "currentOptions")).isEqualTo(original);
            assertThat(history().entries().getFirst().options()).isEqualTo(ConversionOptions.DEFAULTS);
            assertThat(new ConversionPipeline().renderFromJson(
                  new ConversionPipeline().normalizeToJson(input().getText(), "JSON", original), "CSV", original))
                  .isEqualTo(output().getText());
        });
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
          "clear,true", "clear,false", "swap,true", "swap,false",
          "load,true", "load,false", "history,true", "history,false", "edit,true"})
    void documentChangesDiscardPendingConversions(String action, boolean alreadyRendered) throws Exception {
        onEdt(() -> startConversion("YAML"));
        if (alreadyRendered) tasks.worker.runNext();
        onEdt(() -> {
            switch (action) {
                case "clear" -> call(panel, "doClear");
                case "swap" -> call(panel, "doSwap");
                case "load" -> panel.loadContent("{\"new\":2}", "JSON");
                case "edit" -> input().append(" ");
                case "history" -> {
                    Method restore = ConverterPanel.class.getDeclaredMethod("restoreFromHistory", ConversionHistory.Entry.class);
                    restore.setAccessible(true);
                    restore.invoke(panel, new ConversionHistory.Entry("JSON", "YAML",
                          "{\"restored\":3}", "restored: 3\n", java.time.LocalTime.now()));
                }
                default -> throw new AssertionError(action);
            }
        });
        if (!alreadyRendered) tasks.worker.runNext();
        onEdt(() -> {
            String expectedInput = input().getText();
            String expectedOutput = output().getText();
            String expectedStatus = status();
            String expectedFormat = field(panel, "outputFormatLabel", JLabel.class).getText();
            var expectedHistory = java.util.List.copyOf(history().entries());
            tasks.ui.runNext();
            assertThat(input().getText()).isEqualTo(expectedInput);
            assertThat(output().getText()).isEqualTo(expectedOutput);
            if (expectedStatus.equals("Converting…")) assertThat(status()).contains("discarded");
            else assertThat(status()).isEqualTo(expectedStatus);
            assertThat(field(panel, "outputFormatLabel", JLabel.class).getText()).isEqualTo(expectedFormat);
            assertThat(history().entries()).isEqualTo(expectedHistory);
            assertThat(field(panel, "converting", AtomicBoolean.class)).isFalse();
            assertThat(field(panel, "convertBtn", JButton.class).getText()).isEqualTo("Convert");
        });
    }

    @Test void oldConversionErrorsDoNotReplaceClearStatus() throws Exception {
        createPanel(new ConversionPipeline() {
            @Override public String renderFromJson(String input, String format, ConversionOptions options) {
                throw new IllegalArgumentException("obsolete error");
            }
        });
        onEdt(() -> startConversion("YAML"));
        tasks.worker.runNext();
        onEdt(() -> {
            call(panel, "doClear");
            String expectedStatus = status();
            tasks.ui.runNext();
            assertThat(status()).isEqualTo(expectedStatus);
            assertThat(output().getText()).isEmpty();
        });
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"error,clear", "error,load", "cancel,clear", "cancel,load"})
    void oldFormatFailuresAndCancellationDoNotAffectNewDocuments(String outcome, String action) throws Exception {
        createPanel(new ConversionPipeline() {
            @Override public String formatInput(String input, String format, ConversionOptions options) {
                if (outcome.equals("cancel")) throw new java.util.concurrent.CancellationException("obsolete cancel");
                throw new IllegalArgumentException("obsolete failure");
            }
        });
        onEdt(() -> { panel.loadContent("{\"a\":1}", "JSON"); panel.formatInput(); });
        tasks.worker.runNext();
        onEdt(() -> {
            if (action.equals("clear")) call(panel, "doClear");
            else panel.loadContent("{\"new\":2}", "JSON");
            String text = input().getText();
            int caret = input().getCaretPosition();
            String previousStatus = status();
            tasks.ui.runNext();
            assertThat(input().getText()).isEqualTo(text);
            assertThat(input().getCaretPosition()).isEqualTo(caret);
            assertThat(status()).doesNotContain("obsolete", "failed", "cancelled");
            if (action.equals("clear")) assertThat(status()).isEqualTo(previousStatus);
        });
    }

    @Test void oldFormatParseErrorDoesNotMoveTheNewDocumentsCaret() throws Exception {
        onEdt(() -> { panel.loadContent("{\"a\": bad}", "JSON"); panel.formatInput(); });
        tasks.worker.runNext();
        onEdt(() -> {
            panel.loadContent("{\"new\":1}", "JSON");
            input().setCaretPosition(2);
            tasks.ui.runNext();
            assertThat(input().getCaretPosition()).isEqualTo(2);
            assertThat(status()).doesNotContain("Format failed");
        });
    }

    @ParameterizedTest @ValueSource(strings = {"input", "output", "clear"})
    void comparisonCompletionsDiscardChangedEditors(String changed) throws Exception {
        onEdt(() -> {
            panel.loadContent("{\"a\":1}", "JSON");
            output().setText("<root><a>1</a></root>");
            call(panel, "doCompare");
        });
        tasks.worker.runNext();
        onEdt(() -> {
            if (changed.equals("clear")) call(panel, "doClear");
            else if (changed.equals("input")) input().append(" ");
            else output().append(" ");
            String previousStatus = status();
            tasks.ui.runNext();
            if (changed.equals("clear")) assertThat(status()).isEqualTo(previousStatus);
            else assertThat(status()).contains("comparison discarded");
        });
    }

    private void startConversion(String target) throws Exception {
        panel.loadContent("{\"a\":1}", "JSON");
        output().setText("previous output");
        field(panel, "outputCombo", JComboBox.class).setSelectedItem(target);
        panel.convert();
    }

    private void assertCancelled() throws Exception {
        assertThat(output().getText()).isEqualTo("previous output");
        assertThat(history().entries()).isEmpty();
        assertThat(status()).contains("cancelled");
        assertThat(field(panel, "converting", AtomicBoolean.class)).isFalse();
        assertThat(field(panel, "convertBtn", JButton.class).getText()).isEqualTo("Convert");
    }

    private void applyOptions(ConversionOptions options) throws Exception {
        Method method = ConverterPanel.class.getDeclaredMethod("applyOptions", ConversionOptions.class);
        method.setAccessible(true);
        method.invoke(panel, options);
    }

    private Path writeFile(String name, String content) throws Exception {
        return Files.writeString(dir.resolve(name), content);
    }
    private RSyntaxTextArea input() throws Exception { return field(panel, "inputArea", RSyntaxTextArea.class); }
    private RSyntaxTextArea output() throws Exception { return field(panel, "outputArea", RSyntaxTextArea.class); }
    private String status() throws Exception { return field(panel, "statusLabel", JLabel.class).getText(); }
    private ConversionHistory history() throws Exception { return field(panel, "history", ConversionHistory.class); }
    private ConverterFileOps fileOps() throws Exception { return field(panel, "fileOps", ConverterFileOps.class); }

    private static final class BlockingPipeline extends ConversionPipeline {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        private String render() {
            entered.countDown();
            while (true) {
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Renderer was not released");
                    return "cancelled result";
                } catch (InterruptedException ignored) {
                    // Model a library renderer that does not honour interruption.
                }
            }
        }

        @Override public String renderFromJson(String input, String format, ConversionOptions options) {
            return render();
        }
        @Override public String renderCsv(JsonNode input, CsvConverter.CsvMode mode, CsvConverter.CsvFormat format) {
            return render();
        }
    }
}
