package dev.hippodid.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SseAuthFilter")
class SseAuthFilterTest {

    private static final String API_KEY = "hd_sk_test_1234567890";
    private final SseAuthFilter filter = new SseAuthFilter(API_KEY);

    @Test
    @DisplayName("allows request with valid Bearer token")
    void allowsValidToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mcp/sse");
        request.addHeader("Authorization", "Bearer " + API_KEY);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        // Chain was invoked — request passed through
        assertTrue(chain.getRequest() != null);
    }

    @Test
    @DisplayName("rejects request with missing Authorization header")
    void rejectsMissingAuth() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mcp/sse");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        // Chain was NOT invoked
        assertTrue(chain.getRequest() == null);
    }

    @Test
    @DisplayName("rejects request with invalid Bearer token")
    void rejectsInvalidToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mcp/sse");
        request.addHeader("Authorization", "Bearer wrong_key");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertTrue(chain.getRequest() == null);
    }

    @Test
    @DisplayName("rejects request with non-Bearer auth scheme")
    void rejectsNonBearerScheme() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mcp/sse");
        request.addHeader("Authorization", "Basic dXNlcjpwYXNz");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertTrue(chain.getRequest() == null);
    }

    @Test
    @DisplayName("handles Bearer token with extra whitespace")
    void handlesExtraWhitespace() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mcp/sse");
        request.addHeader("Authorization", "Bearer   " + API_KEY + "  ");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertTrue(chain.getRequest() != null);
    }
}
