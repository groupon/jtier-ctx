/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.groupon.jtier;

import org.immutables.value.Value;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.temporal.Temporal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

class Life {
    private final AtomicReference<Timeout> timeout = new AtomicReference<>();
    private final AtomicReference<State> state = new AtomicReference<>(State.ALIVE);
    private final AtomicReference<Optional<Throwable>> cancelCause = new AtomicReference<>(Optional.empty());
    private final List<Consumer<Optional<Throwable>>> cancelListeners = new CopyOnWriteArrayList<>();
    private final ReentrantLock lock = new ReentrantLock();


    Life(final Optional<Life> parent) {
        parent.ifPresent((p) -> p.onCancel(this::cancel));
    }

    void cancel(Optional<Throwable> cause) {
        this.lock.lock();
        try {
            if (this.state.get() == State.ALIVE) {
                this.state.set(State.CANCELLED);
                this.cancelListeners.forEach((l) -> l.accept(cause));
                this.cancelListeners.clear();
            }
        } finally {
            this.lock.unlock();
        }
    }

    void startTimeout(final long time, final TimeUnit unit, final ScheduledExecutorService scheduler) {
        this.lock.lock();
        try {
            final ScheduledFuture<?> future = scheduler.schedule(() -> {
                cancel(Optional.of(new TimeoutException("Operation timed out after " + timeout.get().finishAt())));
            }, time, unit);

            final ChronoUnit cronut = chronoUnit(unit);
            final Timeout t = new TimeoutBuilder().future(future).finishAt(Instant.now().plus(time, cronut)).build();
            final Timeout old = this.timeout.get();
            this.timeout.set(t);

            // try to cancel as we are replacing the timeout, best effort
            if (old != null) {
                old.future().cancel(false);
            }
        } finally {
            this.lock.unlock();
        }
    }

    Optional<Duration> timeRemaining() {
        this.lock.lock();
        try {
            final Timeout t = this.timeout.get();
            if (t == null) {
                return Optional.empty();
            }
            else {
                return Optional.of(Duration.between(Instant.now(), t.finishAt()));
            }
        } finally {
            this.lock.unlock();
        }
    }

    boolean isCancelled() {
        return this.state.get() == State.CANCELLED;

    }

    Disposable onCancel(final Consumer<Optional<Throwable>> runnable) {
        if (isCancelled()) {
            runnable.accept(cancelCause.get());
            return () -> {};
        }

        this.lock.lock();
        try {
            this.cancelListeners.add(runnable);
        }
        finally {
            this.lock.unlock();
        }
        return () -> {
            this.lock.lock();
            try {
                this.cancelListeners.remove(runnable);
            }
            finally {
                this.lock.unlock();
            }
        };
    }

    private enum State {
        ALIVE, CANCELLED
    }

    @Value.Immutable
    @Value.Style(visibility = Value.Style.ImplementationVisibility.PRIVATE)
    abstract static class Timeout {
        abstract Temporal finishAt();

        abstract ScheduledFuture<?> future();
    }

    /**
     * Converts a {@code TimeUnit} to a {@code ChronoUnit}.
     * <p>
     * This handles the seven units declared in {@code TimeUnit}.
     *
     * @param unit  the unit to convert, not null
     * @return the converted unit, not null
     */
    private static ChronoUnit chronoUnit(final TimeUnit unit) {
        Objects.requireNonNull(unit, "unit");
        switch (unit) {
            case NANOSECONDS:
                return ChronoUnit.NANOS;
            case MICROSECONDS:
                return ChronoUnit.MICROS;
            case MILLISECONDS:
                return ChronoUnit.MILLIS;
            case SECONDS:
                return ChronoUnit.SECONDS;
            case MINUTES:
                return ChronoUnit.MINUTES;
            case HOURS:
                return ChronoUnit.HOURS;
            case DAYS:
                return ChronoUnit.DAYS;
            default:
                throw new IllegalArgumentException("Unknown TimeUnit constant");
        }
    }

}
