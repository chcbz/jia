package cn.jia.core.context;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;

class EsContextHolderTest {
    @AfterEach
    void clear() {
        EsContextHolder.clearContext();
    }

    @Test
    void malformedOrAbsentCookieStartsFreshAndNeverRetainsPriorIdentity() {
        EsContextHolder.getContext().setJiacn("stale");
        MockHttpServletRequest malformed = new MockHttpServletRequest();
        malformed.setCookies(new Cookie("CTX", "not-ciphertext"));
        assertNull(EsContextHolder.getContext(malformed).getJiacn());

        EsContextHolder.getContext().setJiacn("second-stale");
        assertNull(EsContextHolder.getContext(new MockHttpServletRequest()).getJiacn());
    }

    @Test
    void generatedCookieHasRequiredBrowserSecurityAttributes() {
        EsContextHolder.getContext().setJiacn("Jia-A");
        Cookie cookie = EsContextHolder.genCookie();
        assertEquals("CTX", cookie.getName());
        assertTrue(cookie.isHttpOnly());
        assertTrue(cookie.getSecure());
        assertEquals("/", cookie.getPath());
        assertEquals("Lax", cookie.getAttribute("SameSite"));
    }
}
