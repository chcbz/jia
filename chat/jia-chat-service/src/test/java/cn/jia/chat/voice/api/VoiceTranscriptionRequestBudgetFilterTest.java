package cn.jia.chat.voice.api;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoiceTranscriptionRequestBudgetFilterTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final VoiceTranscriptionRequestBudgetFilter filter =
            new VoiceTranscriptionRequestBudgetFilter(objectMapper);

    @Test
    void rejectsDeclaredLengthOneByteOverBeforeTheChain() throws Exception {
        MockHttpServletRequest request = requestWithDeclaredLength(
                VoiceTranscriptionRequestBudgetFilter.MAX_REQUEST_BYTES + 1);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> invoked.set(true));

        assertFalse(invoked.get());
        assertTooLarge(response);
    }

    @Test
    void permitsExactDeclaredBoundaryAndDoesNotAffectOtherEndpoints() throws Exception {
        MockHttpServletRequest exact = requestWithDeclaredLength(
                VoiceTranscriptionRequestBudgetFilter.MAX_REQUEST_BYTES);
        MockHttpServletResponse exactResponse = new MockHttpServletResponse();
        AtomicBoolean exactInvoked = new AtomicBoolean();
        filter.doFilter(exact, exactResponse,
                (ignoredRequest, ignoredResponse) -> exactInvoked.set(true));
        assertTrue(exactInvoked.get());

        MockHttpServletRequest other = requestWithDeclaredLength(
                VoiceTranscriptionRequestBudgetFilter.MAX_REQUEST_BYTES + 1);
        other.setRequestURI("/material/upload");
        AtomicBoolean otherInvoked = new AtomicBoolean();
        filter.doFilter(other, new MockHttpServletResponse(),
                (ignoredRequest, ignoredResponse) -> otherInvoked.set(true));
        assertTrue(otherInvoked.get());
    }

    @Test
    void rejectsActualStreamOneByteOverWhenLengthIsUnknown() throws Exception {
        byte[] body = new byte[(int) VoiceTranscriptionRequestBudgetFilter.MAX_REQUEST_BYTES + 1];
        HttpServletRequest unknownLength = new MockHttpServletRequest() {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        ((MockHttpServletRequest) unknownLength).setMethod("POST");
        ((MockHttpServletRequest) unknownLength).setRequestURI("/chat/speech/transcriptions");
        ((MockHttpServletRequest) unknownLength).setContent(body);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean fullyRead = new AtomicBoolean();

        filter.doFilter(unknownLength, response, (boundedRequest, ignoredResponse) -> {
            try {
                boundedRequest.getInputStream().readAllBytes();
                fullyRead.set(true);
            } catch (IOException exception) {
                throw exception;
            }
        });

        assertFalse(fullyRead.get());
        assertTooLarge(response);
    }

    private MockHttpServletRequest requestWithDeclaredLength(long declaredLength) {
        MockHttpServletRequest request = new MockHttpServletRequest() {
            @Override
            public long getContentLengthLong() {
                return declaredLength;
            }
        };
        request.setMethod("POST");
        request.setRequestURI("/chat/speech/transcriptions");
        request.setContent(new byte[0]);
        return request;
    }

    private void assertTooLarge(MockHttpServletResponse response) throws Exception {
        JsonNode json = objectMapper.readTree(response.getContentAsByteArray());
        assertEquals(413, response.getStatus());
        assertEquals(413, json.path("status").asInt());
        assertEquals("VOICE_TOO_LARGE", json.path("code").asText());
    }
}
