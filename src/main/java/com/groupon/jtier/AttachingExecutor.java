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

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

class AttachingExecutor extends AbstractExecutorService {

    private final ExecutorService target;

    private AttachingExecutor(final ExecutorService target) {
        this.target = target;
    }

    static ExecutorService infect(final ExecutorService target) {
        return new AttachingExecutor(target);
    }

    @Override
    public void shutdown() {
        this.target.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
        return this.target.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
        return this.target.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return this.target.isTerminated();
    }

    @Override
    public boolean awaitTermination(final long timeout, final TimeUnit unit) throws InterruptedException {
        return this.target.awaitTermination(timeout, unit);
    }

    @Override
    public void execute(final Runnable command) {
        this.target.execute(Ctx.current().propagate(command));
    }
}
