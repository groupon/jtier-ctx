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


import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Ctx provides a means of tunneling context around between libraries, and occasionally, within
 * applications. Typical usage is to plumb information between different clients (http, redis, etc)
 * and app servers for things like request id, timeouts, etc.
 * <p>
 * Ctx *may* be used by libraries themselves to tunnel information, but this is discouraged. It is
 * generally MUCH better to be explicit about passing things around.
 * <p>
 * Applications which need to make use of a Ctx should explicitely pass and receive contexts, rather
 * than relying on the thread local tunneling capacities.
 */
public class Ctx implements AutoCloseable {

    /**
     * Default executor for timeouts.
     */
    private static final ScheduledExecutorService TIMEOUT_POOL = Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        return t;
    });

    private static final ThreadLocal<Optional<Ctx>> ATTACHED = ThreadLocal.withInitial(Optional::empty);

    private final Life life;
    private final Map<Key<?>, Object> values;

    private final List<Runnable> attachListeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> detachListeners = new CopyOnWriteArrayList<>();

    private Ctx(final Life life, final Map<Key<?>, Object> values) {
        this.life = life;
        this.values = values;
    }

    public static Ctx empty() {
        return new Ctx(new Life(Optional.empty()), Collections.emptyMap());
    }

    public static Optional<Ctx> fromThread() {
        return ATTACHED.get();
    }

    /**
     * Forcibly detach whatever context is presently attached to the current thread.
     * It is preferred to use {@link Ctx#close()}.
     */
    public static void cleanThread() {
        ATTACHED.get().ifPresent(Ctx::close);
    }

    public static <T> Key<T> key(final String name, final Class<T> type) {
        return new Key<>(type, name);
    }

    /**
     * @return
     */
    public Ctx attachToThread() {
        final Optional<Ctx> previouslyAttached = ATTACHED.get();
        if (previouslyAttached.isPresent()) {
            if (previouslyAttached.get() == this) {
                return this;
            }
            previouslyAttached.get().close();
        }

        ATTACHED.set(Optional.of(this));
        this.attachListeners.forEach(Runnable::run);
        return this;
    }

    public void runAttached(final Runnable r) {
        this.propagate(r).run();
    }

    public <T> T callAttached(final Callable<T> c) throws Exception {
        return this.propagate(c).call();
    }

    public <T> Ctx with(final Key<T> key, final T value) {
        final Map<Key<?>, Object> next = new HashMap<>();
        next.putAll(this.values);
        next.put(key, value);
        return new Ctx(this.life, next);
    }

    public Ctx createChild() {
        return new Ctx(new Life(Optional.of(this.life)), this.values);
    }

    public <T> Optional<T> get(final Key<T> key) {
        if (this.values.containsKey(key)) {
            return Optional.of(key.cast(this.values.get(key)));
        }
        else {
            return Optional.empty();
        }
    }


    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        final Ctx ctx = (Ctx) o;

        return this.life.equals(ctx.life) &&
                this.values.equals(ctx.values) &&
                this.attachListeners.equals(ctx.attachListeners) &&
                this.detachListeners.equals(ctx.detachListeners);

    }

    @Override
    public int hashCode() {
        int result = this.life.hashCode();
        result = 31 * result + this.values.hashCode();
        result = 31 * result + this.attachListeners.hashCode();
        result = 31 * result + this.detachListeners.hashCode();
        return result;
    }

    /**
     * Create a child Ctx will be cancelled when the timeout is reached. If a previous timeout was set this will replace it.
     */
    public Ctx withTimeout(final long time, final TimeUnit unit, final ScheduledExecutorService scheduler) {
        Ctx child = this.createChild();
        child.life.startTimeout(time, unit, scheduler);
        return child;
    }

    /**
     * Create a child Ctx will be cancelled when the timeout is reached. If a previous timeout was set this will replace it.
     */
    public Ctx withTimeout(final long time, final TimeUnit unit) {
        return withTimeout(time, unit, TIMEOUT_POOL);
    }

    /**
     * Create a child Ctx will be cancelled when the timeout is reached. If a previous timeout was set this will replace it.
     */
    public Ctx withTimeout(final Duration d) {
        return withTimeout(d, TIMEOUT_POOL);
    }

    /**
     * Create a child Ctx will be cancelled when the timeout is reached. If a previous timeout was set this will replace it.
     */
    public Ctx withTimeout(final Duration d, final ScheduledExecutorService scheduler) {
        if (d.getNano() == 0) {
            return withTimeout(d.getSeconds(), TimeUnit.SECONDS, scheduler);
        }
        else {
            return withTimeout(TimeUnit.SECONDS.toNanos(d.getSeconds()) + d.getNano(), TimeUnit.NANOSECONDS, scheduler);
        }
    }

    /**
     * Return the time remaining before the context is cancelled by a timeout, if one is set.
     * If there is no active timeout, the returned optional will be empty.
     */
    public Optional<Duration> getApproximateTimeRemaining() {
        return this.life.timeRemaining();
    }

    /**
     * Detach this context from the current thread. If this Ctx is NOT attached to the current thread
     * it will raise an IllegalStateException.
     */
    @Override
    public void close() {
        final Optional<Ctx> o = ATTACHED.get();
        if (o.isPresent()) {
            final Ctx attached = o.get();
            if (attached != this) {
                throw new IllegalStateException("Attempt to detach different context from current thread");
            }
            ATTACHED.set(Optional.empty());
            this.detachListeners.forEach(Runnable::run);
        }
        else {
            throw new IllegalStateException("Attempt to detach context from unattached thread");
        }
    }

    /**
     * Cancel this context.
     */
    public void cancel() {
        this.life.cancel();
    }

    public boolean isCancelled() {
        return this.life.isCancelled();
    }

    /**
     * Add a callback to be invoked when this context is detached from a thread.
     * It will be invoked on the thread from which it is being detached.
     */
    public void onDetach(final Runnable runnable) {
        this.detachListeners.add(runnable);
    }

    /**
     * Add a callback to be invoked when this context is attached to a thread.
     * It will be invoked on the thread to which it is being attached.
     */
    public void onAttach(final Runnable runnable) {
        this.attachListeners.add(runnable);
    }

    /**
     * Callback which will be invoked if/when the Ctx is cancelled. If the Ctx is
     * already canceled, the callback will be invoked immediately.
     */
    public void onCancel(final Runnable runnable) {
        this.life.onCancel(runnable);
    }

    /**
     * Create a new context with an independent lifetime from this context. It will keep
     * all the values associated with this context, but have its own lifecycle.
     */
    public Ctx newRoot() {
        return new Ctx(new Life(Optional.empty()), new HashMap<>(this.values));
    }

    /**
     * Creates an ExecutorService which propagates attached contexts.
     * <p>
     * If a context is attached at the time of job submission, that context is saved and attached
     * before execution of the job, then detached after.
     */
    public static ExecutorService createPropagatingExecutor(final ExecutorService exec) {
        return AttachingExecutor.infect(exec);
    }

    /**
     * Wraps a runnable such that this context is bound to the thread on which the
     * runnable is run.
     */
    public Runnable propagate(final Runnable r) {
        final Ctx self = this;
        return () -> {
            final Optional<Ctx> attached = Ctx.fromThread();
            if (attached.isPresent()) {
                if (attached.get() == self) {
                    r.run();
                }
                else {
                    try (Ctx ignored = self.attachToThread()) {
                        r.run();
                    } finally {
                        // reattach previous ctx
                        attached.get().attachToThread();
                    }
                }
            }
            else {
                // no pre-existing context on the thread
                try (Ctx _i = this.attachToThread()) {
                    r.run();
                }
            }
        };
    }

    /**
     * Wraps a callable such that this context is bound to the thread on which the
     * runnable is run.
     */
    public <T> Callable<T> propagate(final Callable<T> r) {
        final Ctx self = this;
        return () -> {
            final Optional<Ctx> attached = Ctx.fromThread();
            if (attached.isPresent()) {
                if (attached.get() == self) {
                    return r.call();
                }
                else {
                    try (Ctx ignored = self.attachToThread()) {
                        return r.call();
                    } finally {
                        // reattach previous ctx
                        attached.get().attachToThread();
                    }
                }
            }
            else {
                // no pre-existing context on the thread
                try (Ctx _i = this.attachToThread()) {
                    return r.call();
                }
            }
        };
    }

    /**
     * Creates a dynamic proxy which propagates this context to all invoked methods. It is intended for use
     * with functional interfaces, but should work fine for any interface.
     * <p>
     * The return value *must* be an interface for it to not explode nastily at runtime.
     */
    public <T> T propagate(final T fi) {
        return (T) Proxy.newProxyInstance(fi.getClass().getClassLoader(),
                                          fi.getClass().getInterfaces(),
                                          (p, method, args) -> this.propagate(() -> method.invoke(fi, args)).call());
    }

    /**
     * A typed, named, key for a value in a context. See {@link Ctx#with(Key, Object)} and {@link Ctx#get(Key)}.
     *
     * @param <T> type of the value this key accesses.
     */
    public static final class Key<T> {
        private final Class<T> type;
        private final String name;

        private Key(final Class<T> type, final String name) {
            this.type = type;
            this.name = name;
        }

        public T cast(final Object obj) {
            return this.type.cast(obj);
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            final Key<?> key = (Key<?>) o;

            return this.type.equals(key.type) && this.name.equals(key.name);

        }

        @Override
        public int hashCode() {
            int result = this.type.hashCode();
            result = 31 * result + this.name.hashCode();
            return result;
        }
    }
}
