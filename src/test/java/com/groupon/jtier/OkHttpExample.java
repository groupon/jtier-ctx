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

import okhttp3.Call;
import okhttp3.Dispatcher;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

public class OkHttpExample {

    private static final Ctx.Key<UUID> REQUEST_ID = Ctx.key("X-Request-Id", UUID.class);

    @Rule
    public MockWebServer web = new MockWebServer();

    private OkHttpClient ok;

    @Before
    public void setUp() throws Exception {
        final Dispatcher d = new Dispatcher(AttachingExecutor.infect(Executors.newCachedThreadPool()));
        this.ok = new OkHttpClient.Builder()
                .dispatcher(d)
                .addInterceptor(new ExampleInterceptor())
                .build();
    }

    @Test
    public void testOkRequestIdPropagation() throws Exception {
        this.web.enqueue(new MockResponse().setBody("hello world")
                                           .setResponseCode(200)
                                           .addHeader("Content-Type", "text/plain"));

        final UUID id = UUID.randomUUID();

        try (Ctx _i = Ctx.empty().with(REQUEST_ID, id).attachToThread()) {
            final Call call = this.ok.newCall(new Request.Builder().url(this.web.url("/")).build());
            final Response response = call.execute();
            assertThat(response.code()).isEqualTo(200);
        }

        assertThat(this.web.takeRequest().getHeader("X-Request-Id")).isEqualTo(id.toString());

    }

    public static class ExampleInterceptor implements Interceptor {

        @Override
        public Response intercept(final Chain chain) throws IOException {
            final Request req = chain.request();
            return chain.proceed(Ctx.fromThread()
                                    .map((ctx) -> ctx.get(REQUEST_ID)
                                                     .map((id) -> req.newBuilder()
                                                                     .addHeader("X-Request-Id", ctx.get(REQUEST_ID)
                                                                                                   .get()
                                                                                                   .toString())
                                                                     .build())
                                                     .orElse(req))
                                    .orElse(req));
        }
    }
}
