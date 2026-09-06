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

import javax.swing.SwingUtilities;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

/** Tests choose exactly when workers finish and when the EDT receives results. */
final class TestTasks {
    final Queue worker = new Queue();
    final Queue ui = new Queue();
    final BackgroundTasks runner = new BackgroundTasks(worker, ui);

    void completeNext() throws Exception {
        worker.runNext();
        onEdt(ui::runNext);
    }

    static final class Queue implements Executor {
        private final Deque<Runnable> tasks = new ArrayDeque<>();
        @Override public synchronized void execute(Runnable command) { tasks.addLast(command); }
        void runNext() { take(false).run(); }
        void runLast() { take(true).run(); }
        private synchronized Runnable take(boolean last) {
            return last ? tasks.removeLast() : tasks.removeFirst();
        }
    }

    static <T> T field(Object target, String name, Class<T> type) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(target));
    }

    static Object call(Object target, String name) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }

    static void onEdt(CheckedRunnable action) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try { action.run(); }
            catch (Throwable ex) { failure.set(ex); }
        });
        if (failure.get() instanceof Exception ex) throw ex;
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    interface CheckedRunnable { void run() throws Exception; }
}
