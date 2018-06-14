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
import java.util.function.Consumer;

/**
 * Ctx provides a means of tunneling context around between libraries, and occasionally, within
 * applications. Typical usage is to plumb information between different clients (http, redis, etc)
 * and app servers for things like request id, timeouts, etc.
 * <p>
 * Ctx *may* be used by libraries themselves to tunnel information, but this is discouraged. It is
 * generally MUCH better to be explicit about passing things around.
 * <p>
 * Applications which need to make use of a Ctx *should* explicitly pass and receive contexts, rather
 * than relying on the thread local tunneling capacities.
 * <p>
 * Ctx has a simple lifecycle. When it is created it is live, when canceled it transitions to canceled.
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

    private static final ThreadLocal<Ctx> ATTACHED = ThreadLocal.withInitial(Ctx::empty);

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

    /**
     * @return
     * @deprecated use {@link Ctx#current()}
     */
    @Deprecated
    public static Optional<Ctx> fromThread() {
        return Optional.of(ATTACHED.get());
    }

    public static Ctx current() {
        return ATTACHED.get();
    }

    /**
     * Forcibly detach whatever context is presently attached to the current thread.
     * It is preferred to use {@link Ctx#detach()}.
     */
    public static void cleanThread() {
        ATTACHED.get().detach();
        Ctx.empty().attach();
    }

    public static <T> Key<T> key(final String name, final Class<T> type) {
        return new Key<>(type, name);
    }

    /**
     * @return
     */
    public Ctx attach() {
        final Ctx previous = ATTACHED.get();
        if (previous == this) {
            // NOOP
            return this;
        }
        previous.detach();

        justAttach(this);
        return this;
    }

    public void runAttached(final Runnable r) {
        this.propagate(r).run();
    }

    public <T> T callAttached(final Callable<T> c) throws Exception {
        return this.propagate(c).call();
    }

    public <T> Ctx with(final Key<T> key, final T value) {
        final Map<Key<?>, Object> next = new HashMap<>(this.values);
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
     * An alias for `Ctx#cancel()`
     */
    @Override
    public void close() {
        this.cancel();
    }

    /**
     * Cancel this context.
     */
    public void cancel(Throwable cause) {
        this.life.cancel(Optional.of(cause));
    }

    public void detach() {
        detach(Ctx.empty());
    }

    public void detach(Ctx toAttach) {
        final Ctx attached = ATTACHED.get();
        if (attached == this) {
            this.detachListeners.forEach(Runnable::run);
            justAttach(toAttach);
        }
    }

    /**
     * helper method used to avoid mutual recursion on attach/detach
     */
    private void justAttach(Ctx toAttach) {
        ATTACHED.set(toAttach);
        toAttach.attachListeners.forEach(Runnable::run);
    }

    /**
     * Cancel this context.
     */
    public void cancel() {
        this.life.cancel(Optional.empty());
    }

    public boolean isCancelled() {
        return this.life.isCancelled();
    }

    /**
     * Add a callback to be invoked when this context is detached from a thread.
     * It will be invoked on the thread from which it is being detached.
     *
     * @return a {@link Disposable} that can cancel the callback.
     */
    public Disposable onDetach(final Runnable runnable) {
        this.detachListeners.add(runnable);
        return () -> this.detachListeners.remove(runnable);
    }

    /**
     * Add a callback to be invoked when this context is attached to a thread.
     * It will be invoked on the thread to which it is being attached.
     *
     * @return a {@link Disposable} that can cancel the callback.
     */
    public Disposable onAttach(final Runnable runnable) {
        this.attachListeners.add(runnable);
        return () -> this.attachListeners.remove(runnable);
    }

    /**
     * Callback which will be invoked if/when the Ctx is cancelled. If the Ctx is
     * already canceled, the callback will be invoked immediately.
     *
     * @return a {@link Disposable} that can cancel the callback.
     */
    public Disposable onCancel(final Consumer<Optional<Throwable>> runnable) {
        return this.life.onCancel(runnable);
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
        return () -> {
            final Ctx previous = Ctx.current();
            if (previous == Ctx.this) {
                r.run();
            }
            else {
                Ctx.this.attach();
                try {
                    r.run();

                } finally {
                    previous.attach();
                }
            }
        };
    }

    /**
     * Wraps a callable such that this context is bound to the thread on which the
     * runnable is run.
     */
    public <T> Callable<T> propagate(final Callable<T> r) {
        return () -> {
            final Ctx attached = Ctx.current();
                if (attached == Ctx.this) {
                    return r.call();
                }
                else {
                    Ctx.this.attach();
                    try {
                        return r.call();
                    } finally {
                        attached.attach();
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

        public Optional<T> get() {
            return Ctx.fromThread().flatMap((ctx) -> ctx.get(this));
        }

        public Ctx set(T value) {
            final Ctx ctx = Ctx.fromThread()
                               .orElseThrow(() -> new IllegalStateException("may not set value, no Ctx set"));
            return ctx.with(this, value).attach();
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
