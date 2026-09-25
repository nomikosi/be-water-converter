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

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The conversion in flight: whether one runs, whether Cancel was asked for,
 * and the pooled thread to interrupt when it was.
 *
 * <p>The flag is what makes a Cancel pressed before the pooled task starts
 * take effect, when there is no thread to interrupt yet; the interrupt is what
 * unblocks a task already deep in a long loop.
 */
final class ConversionRun {

    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean cancelRequested = new AtomicBoolean();
    /** Guards {@link #worker}, so a Cancel cannot interrupt the pool's next task. */
    private final Object workerLock = new Object();
    private Thread worker;

    /** Starts a run unless one is already in flight; false when it is. */
    boolean tryStart() {
        if (!running.compareAndSet(false, true)) return false;
        cancelRequested.set(false);
        return true;
    }

    boolean isRunning() { return running.get(); }

    boolean cancelRequested() { return cancelRequested.get(); }

    /** Marks the run over; called on the EDT once its result has been handled. */
    void finished() { running.set(false); }

    /** Called on the worker thread as the task begins. */
    void attachWorker() {
        synchronized (workerLock) { worker = Thread.currentThread(); }
    }

    /**
     * Called on the worker thread as the task ends, under the same lock a
     * Cancel interrupts under: the thread belongs to the shared application
     * pool, so an interrupt landing after the task finished would hit whatever
     * unrelated work the thread picked up next. A late one is cleared here.
     */
    void detachWorker() {
        synchronized (workerLock) {
            worker = null;
            Thread.interrupted();
        }
    }

    /** Throws when Cancel was pressed, whether or not the worker saw an interrupt. */
    void checkCancelled() {
        if (cancelRequested.get() || Thread.currentThread().isInterrupted())
            throw new CancellationException("Conversion cancelled");
    }

    /** Asks the running conversion to stop; false when none is running. */
    boolean cancel() {
        if (!running.get()) return false;
        stop();
        return true;
    }

    /**
     * Stops whatever runs, for a panel being disposed. Without it a conversion
     * in flight when the project closes ran to completion on a pooled thread,
     * and the CSV row warning could put up a modal dialog with no window left.
     */
    void stop() {
        cancelRequested.set(true);
        synchronized (workerLock) {
            if (worker != null) worker.interrupt();
        }
    }
}
