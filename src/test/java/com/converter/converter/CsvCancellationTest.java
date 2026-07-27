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

package com.converter.converter;

import com.converter.converter.CsvConverter.CsvMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Cancel button's only mechanism once row expansion has started is the
 * interrupt polling inside {@link CsvConverter}. Nothing exercised it, so any
 * of those polls could be deleted with the whole suite still green.
 */
@DisplayName("CSV cancellation")
class CsvCancellationTest {

    private CsvConverter csv;
    private ConversionPipeline pipeline;

    @BeforeEach void setUp() {
        csv = new CsvConverter();
        pipeline = new ConversionPipeline();
    }

    @AfterEach void clearInterrupt() {
        // The throw leaves the interrupt flag set; without clearing it the flag
        // leaks onto the shared JUnit worker and fails unrelated tests.
        Thread.interrupted();
    }

    private static String crossJoinInput(int arrays, int each) {
        StringBuilder sb = new StringBuilder("{");
        for (int a = 0; a < arrays; a++) {
            if (a > 0) sb.append(",");
            sb.append("\"a").append(a).append("\":[");
            for (int i = 0; i < each; i++) {
                if (i > 0) sb.append(",");
                sb.append("{\"v\":").append(i).append("}");
            }
            sb.append("]");
        }
        return sb.append("}").toString();
    }

    @Test @DisplayName("an already-interrupted thread aborts FLAT_FIRST")
    void interruptedFlatFirst() throws Exception {
        var tree = pipeline.parseJson("[{\"a\":1},{\"a\":2}]");
        Thread.currentThread().interrupt();
        assertThatThrownBy(() -> csv.jsonToCsv(tree, CsvMode.FLAT_FIRST))
              .isInstanceOf(CancellationException.class);
    }

    @Test @DisplayName("an already-interrupted thread aborts CROSS_JOIN")
    void interruptedCrossJoin() throws Exception {
        var tree = pipeline.parseJson(crossJoinInput(3, 3));
        Thread.currentThread().interrupt();
        assertThatThrownBy(() -> csv.jsonToCsv(tree, CsvMode.CROSS_JOIN))
              .isInstanceOf(CancellationException.class);
    }

    @Test @DisplayName("a mid-flight interrupt stops a runaway cross join promptly")
    void interruptMidFlight() throws Exception {
        // Large enough that it would take a long time to finish on its own.
        var tree = pipeline.parseJson(crossJoinInput(12, 5));
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        Thread worker = new Thread(() -> {
            started.countDown();
            try {
                csv.jsonToCsv(tree, CsvMode.CROSS_JOIN);
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        worker.start();
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(50);          // let it get into the expansion
        worker.interrupt();
        worker.join(TimeUnit.SECONDS.toMillis(10));

        assertThat(worker.isAlive()).as("worker should have stopped").isFalse();
        assertThat(thrown.get()).isInstanceOf(CancellationException.class);
    }

    @Test @DisplayName("without an interrupt the same conversion completes normally")
    void noInterruptCompletes() throws Exception {
        var tree = pipeline.parseJson(crossJoinInput(2, 2));
        assertThat(csv.jsonToCsv(tree, CsvMode.CROSS_JOIN)).isNotBlank();
    }
}
