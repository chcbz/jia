package cn.jia.core.filter;

import cn.jia.core.context.EsContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

class EsContextFilterTest {
    @AfterEach
    void clear() {
        EsContextHolder.clearContext();
    }

    @Test
    void clearsBeforeRequestAndRemovesAfterNormalCompletion() throws Exception {
        EsContextHolder.getContext().setJiacn("stale");
        new EsContextFilter().doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), (request, response) -> {
            assertNull(EsContextHolder.getContext().getJiacn());
            EsContextHolder.getContext().setJiacn("request-user");
        });
        assertNull(EsContextHolder.getContext().getJiacn());
    }

    @Test
    void removesThreadLocalEvenWhenDownstreamThrows() {
        EsContextHolder.getContext().setJiacn("stale");
        assertThrows(IllegalStateException.class, () -> new EsContextFilter().doFilter(
                new MockHttpServletRequest(), new MockHttpServletResponse(),
                (request, response) -> {
                    EsContextHolder.getContext().setJiacn("request-user");
                    throw new IllegalStateException("boom");
                }));
        assertNull(EsContextHolder.getContext().getJiacn());
    }
}
