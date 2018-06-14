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

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class CtxTest {

    private static final Ctx.Key<String> NAME = Ctx.key("name", String.class);

    @Before
    @After
    public void cleanUpCurrentThread() {
        Ctx.cleanThread();
    }

    @Test
    public void testKeyOnChildIsNotOnParent() throws Exception {
        final Ctx root = Ctx.empty();
        final Ctx child = root.with(NAME, "Brian");

        assertThat(child.get(NAME).get()).isEqualTo("Brian");
        assertThat(root.get(NAME)).isEmpty();
    }

    @Test
    public void testExplicitThreadLocalInfection() throws Exception {
        final Ctx root = Ctx.empty();

        try (Ctx i = root.attach()) {
            assertThat(Ctx.fromThread()).isPresent();
            final Ctx magic = Ctx.fromThread().get();
            assertThat(magic).isEqualTo(i);
            i.detach();
        } }

    @Test
    public void testThereIsAlwaysAContext() throws Exception {
        assertThat(Ctx.fromThread()).isNotEmpty();
    }

    @Test
    public void testCancelOnPeers() throws Exception {
        final Ctx brian = Ctx.empty().with(NAME, "Brian");
        final Ctx eric = brian.with(NAME, "Eric");
        final Ctx keith = brian.with(NAME, "Keith");

        brian.close();

        assertThat(brian.isCancelled()).isTrue();
        assertThat(eric.isCancelled()).isTrue();
        assertThat(keith.isCancelled()).isTrue();
    }

    @Test
    public void testCancelOnTree() throws Exception {
        final Ctx tip = Ctx.empty().with(NAME, "Tip");

        /*
        (tip
          (brian
            ((ian
              (panda))
             (cora
               (sprinkle)))))
         */

        final Ctx brian = tip.createChild().with(NAME, "Brian");
        final Ctx ian = brian.createChild().with(NAME, "Ian");
        final Ctx panda = ian.createChild().with(NAME, "Panda");
        final Ctx cora = brian.createChild().with(NAME, "Cora");
        final Ctx sprinkle = cora.createChild().with(NAME, "Sprinkle Kitty");

        brian.cancel();

        assertThat(brian.isCancelled()).isTrue();
        assertThat(ian.isCancelled()).isTrue();
        assertThat(cora.isCancelled()).isTrue();
        assertThat(panda.isCancelled()).isTrue();
        assertThat(sprinkle.isCancelled()).isTrue();
        assertThat(tip.isCancelled()).isFalse();
    }

    @Test
    public void testPropagateFromThread() throws Exception {
        final ExecutorService pool = Ctx.createPropagatingExecutor(Executors.newFixedThreadPool(1));
        final AtomicReference<Boolean> isCurrentThreadAttached = new AtomicReference(false);

        final Runnable command = () -> {
            if (Ctx.fromThread().isPresent()) {
                isCurrentThreadAttached.set(true);
            }
        };

        try (Ctx _i = Ctx.empty().attach()) {
            pool.execute(command);
        }
        pool.awaitTermination(1, TimeUnit.SECONDS);
        assertThat(isCurrentThreadAttached.get()).isTrue();
    }

    @Test
    public void testStaticDetachCallsListeners() throws Exception {
        final Ctx c = Ctx.empty();
        c.attach();

        final AtomicBoolean ran = new AtomicBoolean(false);
        c.onDetach(() -> ran.set(true));

        Ctx.cleanThread();
        assertThat(ran.get()).isTrue();
    }

    @Test
    public void testAttachingNewContextDetachesOld() throws Exception {
        final Ctx one = Ctx.empty();
        final AtomicBoolean one_detached = new AtomicBoolean(false);
        one.onDetach(() -> one_detached.set(true));
        one.attach();

        final Ctx two = Ctx.empty();
        two.attach();
        two.detach();

        assertThat(one_detached.get()).describedAs("detach listener was not invoked")
                                      .isTrue();
    }

    @Test
    public void testCleanThreadCleans() throws Exception {
        final Ctx one = Ctx.empty();
        one.attach();
        Ctx.cleanThread();
        final Optional<Ctx> ft = Ctx.fromThread();
        assertThat(ft).isPresent();
        assertThat(ft.get()).isNotSameAs(one);
    }

    @Test
    public void testDetach() throws Exception {
        final Ctx one = Ctx.empty().with(Ctx.key("hello", String.class), "world");
        final Ctx two = one.with(Ctx.key("greeting", String.class), "bonjour");
        final Ctx three = two.newRoot();
        one.cancel();
        assertThat(one.isCancelled()).isTrue();
        assertThat(two.isCancelled()).isTrue();
        assertThat(three.isCancelled()).isFalse();
    }

    @Test
    public void testPropagateRunnable() throws Exception {
        final Ctx ctx = Ctx.empty().with(Ctx.key("hello", String.class), "world");
        final AtomicReference<String> hello = new AtomicReference<>();
        final Runnable r = ctx.propagate(() -> Ctx.fromThread()
                                                  .flatMap((c) -> c.get(Ctx.key("hello", String.class)))
                                                  .ifPresent(hello::set));
        r.run();
        assertThat(hello.get()).isEqualTo("world");
    }

    @Test
    public void testPropagateRunnableRestoresState() throws Exception {
        final Ctx existing = Ctx.empty().attach();

        Ctx.empty().with(Ctx.key("hello", String.class), "world")
           .propagate(() -> {
           })
           .run();

        assertThat(Ctx.fromThread()).describedAs("thread has bound context").isPresent();
        assertThat(Ctx.fromThread().get()).describedAs("bound context is same as expected").isSameAs(existing);
    }

    @Test
    public void testPropogateCallable() throws Exception {
        final Ctx ctx = Ctx.empty().with(Ctx.key("hello", String.class), "world");
        final Callable<String> r = ctx.propagate(() -> Ctx.fromThread()
                                                          .flatMap((c) -> c.get(Ctx.key("hello", String.class)))
                                                          .orElse("WRONG ANSWER"));
        final String rs = r.call();
        assertThat(rs).isEqualTo("world");
    }

    @Test
    public void testPropogateCallableRestoresState() throws Exception {
        final Ctx existing = Ctx.empty().attach();

        Ctx.empty().with(Ctx.key("hello", String.class), "world")
           .propagate(() -> Ctx.fromThread()
                               .flatMap((c) -> c.get(Ctx.key("hello", String.class)))
                               .orElse("WRONG ANSWER"))
           .call();

        assertThat(Ctx.fromThread()).describedAs("thread has bound context").isPresent();
        assertThat(Ctx.fromThread().get()).describedAs("bound context is same as expected").isSameAs(existing);
    }

    @Test
    public void testPropagateAnyFunctionalInterface() throws Exception {
        final Ctx.Key<String> greeting = Ctx.key("greeting", String.class);
        final Ctx ctx = Ctx.empty().with(greeting, "bonjour");

        final Function<String, String> f2 = ctx.propagate((s) -> Ctx.fromThread()
                                                                    .flatMap((c) -> c.get(greeting))
                                                                    .orElse("hello") + ", " + s);

        final String rs = f2.apply("Brian");
        assertThat(rs).isEqualTo("bonjour, Brian");
    }
}
