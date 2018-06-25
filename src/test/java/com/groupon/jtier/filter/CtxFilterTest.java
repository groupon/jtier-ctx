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
package com.groupon.jtier.filter;

import com.groupon.jtier.Ctx;

import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.GenericServlet;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

import static org.assertj.core.api.Assertions.assertThat;


public class CtxFilterTest {

    private MockHttpServletRequest req;
    private MockHttpServletResponse res;
    private AtomicReference<Ctx> ctx;

    @Before
    public void setUp() {
        req = new MockHttpServletRequest();
        res = new MockHttpServletResponse();
        ctx = new AtomicReference<>();
    }

    @Test
    public void supportsSharedDefaultContext() throws Exception {
        // Configure
        final Ctx.Key<String> KEY = Ctx.key("foo", String.class);
        final String VALUE = "bar";
        final Ctx defaultCtx = Ctx.empty().with(KEY, VALUE);

        final CtxFilter filter = new CtxFilter(defaultCtx);

        final MockFilterChain chain = new MockFilterChain(new GenericServlet() {
            @Override
            public void service(final ServletRequest req,
                                final ServletResponse res) throws ServletException, IOException {
                ctx.set(Ctx.fromThread().get());
            }
        });

        // Run
        filter.doFilter(req, res, chain);

        // Verify
        assertThat(ctx.get()).isNotNull();
        assertThat(ctx.get().get(KEY).get()).isEqualTo(VALUE);
    }

    @Test
    public void supportsEmptyDefaultSharedContext() throws Exception {
        // Configure
        final CtxFilter filter = new CtxFilter();

        final MockFilterChain chain = new MockFilterChain(new GenericServlet() {
            @Override
            public void service(final ServletRequest req,
                                final ServletResponse res) throws ServletException, IOException {
                ctx.set(Ctx.fromThread().get());
            }
        });

        // Run
        filter.doFilter(req, res, chain);

        // Verify
        assertThat(ctx.get()).isNotNull();
    }

    @Test
    public void cleansUpTheRequestContextAfterTheRequest() throws Exception {
        // Configure
        final CtxFilter filter = new CtxFilter();

        final MockFilterChain chain = new MockFilterChain(new GenericServlet() {
            @Override
            public void service(final ServletRequest req,
                                final ServletResponse res) throws ServletException, IOException {
                ctx.set(Ctx.fromThread().get());

                assertThat(ctx.get().isCancelled()).isFalse();
            }
        });

        // Run
        filter.doFilter(req, res, chain);

        // Verify
        assertThat(ctx.get().isCancelled()).isTrue();
    }

    @Test
    public void leavesTheDefaultContextIntactAfterTheRequest() throws Exception {
        // Configure
        final Ctx defaultCtx = Ctx.empty();

        final CtxFilter filter = new CtxFilter(defaultCtx);

        final MockFilterChain chain = new MockFilterChain(new GenericServlet() {
            @Override
            public void service(final ServletRequest req,
                                final ServletResponse res) throws ServletException, IOException {
                ctx.set(Ctx.fromThread().get());
            }
        });

        // Run
        filter.doFilter(req, res, chain);

        // Verify
        assertThat(defaultCtx).isNotNull();
    }

    @Test
    public void cleansUpRequestContextOnAsyncRequests() throws Exception {
        // Configure
        final CtxFilter filter = new CtxFilter();
        final MockAsyncContext asyncContext = new MockAsyncContext(req, res);

        final MockFilterChain chain = new MockFilterChain(new GenericServlet() {
            @Override
            public void service(final ServletRequest req,
                                final ServletResponse res) throws ServletException, IOException {
                ctx.set(Ctx.fromThread().get());

                assertThat(ctx.get().isCancelled()).isFalse();
            }
        });

        // Run
        req.setAsyncStarted(true);
        req.setAsyncContext(asyncContext);
        filter.doFilter(req, res, chain);
        req.getAsyncContext().complete();

        // Verify
        assertThat(ctx.get().isCancelled()).isTrue();
    }
}