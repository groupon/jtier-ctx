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
import org.skife.clocked.ClockedExecutorService;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

public class TimeoutTest {

    @Test
    public void testTimeoutCancels() throws Exception {
        final ClockedExecutorService clock = new ClockedExecutorService();

        final Ctx p = Ctx.empty();
        Ctx ctx = p.withTimeout(1, TimeUnit.SECONDS, clock);

        clock.advance(2, TimeUnit.SECONDS).get();
        assertThat(ctx.isCancelled()).isTrue();
    }

    @Test
    public void testTimeoutMilliseconds() throws Exception {
        Ctx.empty().withTimeout(1, TimeUnit.MILLISECONDS);
    }

    @Test
    public void testTimeRemaining() throws Exception {
        final ClockedExecutorService clock = new ClockedExecutorService();

        final Ctx p = Ctx.empty();
        Ctx ctx = p.withTimeout(100, TimeUnit.DAYS, clock);

        Thread.sleep(10);
        final Optional<Duration> tr = ctx.getApproximateTimeRemaining();
        assertThat(tr).isPresent();
        final Duration d = tr.get();

        assertThat(d.toDays()).isLessThanOrEqualTo(99);

    }

}
