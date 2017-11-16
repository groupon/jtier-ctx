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

/**
 * Objects of this type are returned from the {@link Ctx#onAttach(Runnable)},
 * {@link Ctx#onCancel(Runnable)} and {@link Ctx#onDetach(Runnable)} methods of {@link Ctx}.
 * <p>
 * The {@link #dispose()} method will remove that callback from the Ctx.
 */
@FunctionalInterface
public interface Disposable {
    /**
     * Disposes a callback. Behavior is undefined if invoked more than once.
     */
    void dispose();
}
