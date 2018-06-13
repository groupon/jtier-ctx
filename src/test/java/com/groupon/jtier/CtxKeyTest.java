package com.groupon.jtier;

import org.junit.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

public class CtxKeyTest {
    @Test
    public void testSimpleSetGet() throws Exception {
        final Ctx.Key key = Ctx.key("greeting", String.class);
        Ctx.empty().attach();

        key.set("hola");
        assertThat(key.get()).isEqualTo(Optional.of("hola"));
    }

    @Test
    public void testCtxAfterSetIsPeerOfOriginal() throws Exception {
        final Ctx.Key key = Ctx.key("greeting", String.class);
        Ctx root = Ctx.empty().attach();

        Ctx peer = key.set("hola");
        assertThat(key.get()).isEqualTo(Optional.of("hola"));

        assertThat(Ctx.fromThread()).isPresent();

        root.cancel();
        assertThat(peer.isCancelled()).isTrue();
    }
}
