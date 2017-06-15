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

import com.google.common.collect.ImmutableList;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import uk.org.lidalia.slf4jtest.LoggingEvent;
import uk.org.lidalia.slf4jtest.TestLogger;
import uk.org.lidalia.slf4jtest.TestLoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

public class LoggingExample {

    @Test
    public void testDiagnosticContextOnInfection() throws Exception {

        final Ctx c = Ctx.empty();
        c.onAttach(() -> MDC.put("name", "grumbly"));
        c.onDetach(MDC::clear);

        try (Ctx ignored = c.attachToThread()) {
            final Logger logger = LoggerFactory.getLogger(LoggingExample.class);
            logger.debug("log");
        }

        assertThat(MDC.get("name")).isNull();

        final TestLogger logger = TestLoggerFactory.getTestLogger(LoggingExample.class);
        final ImmutableList<LoggingEvent> events = logger.getLoggingEvents();

        assertThat(events).hasSize(1);
        assertThat(events.get(0).getMdc()).containsEntry("name", "grumbly");

        logger.clearAll();
    }
}
