package cn.jia.chat.service;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class ChatBountyExecutionCoordinatorV3Test {
    @Test void retiredAuthorityHasNoAdmissionOrReplayEntryButKeepsHistoryLookup() {
        var methods=Arrays.stream(ChatBountyInteractionV3AuthorityService.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName).toList();
        assertFalse(methods.contains("admit"));
        assertFalse(methods.contains("persistChat"));
        assertTrue(methods.contains("getInteraction"));
        assertTrue(methods.contains("getIssue"));
    }
}
