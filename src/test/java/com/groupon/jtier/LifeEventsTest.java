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

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

public class LifeEventsTest {

    @Test
    public void testCancelEventFires() throws Exception {
        final Ctx d = Ctx.empty();
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean canceled = new AtomicBoolean(false);

        d.onCancel(() -> {
            canceled.set(true);
            latch.countDown();
        });

        d.cancel();

        assertThat(latch.await(20, TimeUnit.MILLISECONDS)).isTrue();
        assertThat(canceled.get()).isTrue();
    }

    @Test
    public void testCancelEventDisposes() throws Exception {
        final Ctx d = Ctx.empty();
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean canceled = new AtomicBoolean(false);

        Disposable disposable = d.onCancel(() -> {
            canceled.set(true);
            latch.countDown();
        });
        
        disposable.dispose();
        
        d.cancel();

        assertThat(latch.await(20, TimeUnit.MILLISECONDS)).isFalse();
        assertThat(canceled.get()).isFalse();
    }

    @Test
    public void testCancelParentCancelsChildren() throws Exception {
        final Ctx p = Ctx.empty();
        final Ctx c = p.createChild();

        final AtomicBoolean canceled = new AtomicBoolean(false);

        c.onCancel(() -> canceled.set(true));

        p.cancel();
        assertThat(canceled.get()).isTrue();
    }

    @Test
    public void testOnCancelAfterCancelExecutesImmediately() throws Exception {
        final Ctx c = Ctx.empty();
        c.cancel();
        final boolean[] canceled = {false};
        c.onCancel(() -> canceled[0] = true );
        assertThat(canceled[0]).isTrue();
    }
}
