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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/** Runs work on a pool and delivers completion on the UI executor. */
final class BackgroundTasks {
    private final Executor worker;
    private final Executor ui;

    BackgroundTasks(Executor worker, Executor ui) {
        this.worker = worker;
        this.ui = ui;
    }

    static BackgroundTasks forIde() {
        return new BackgroundTasks(
              task -> com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService().execute(task),
              task -> com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(task));
    }

    <T> void submit(Supplier<T> work, BiConsumer<T, Throwable> onDone) {
        CompletableFuture.supplyAsync(work, worker).whenComplete((result, error) ->
              ui.execute(() -> onDone.accept(result,
                    error instanceof CompletionException ? error.getCause() : error)));
    }
}
