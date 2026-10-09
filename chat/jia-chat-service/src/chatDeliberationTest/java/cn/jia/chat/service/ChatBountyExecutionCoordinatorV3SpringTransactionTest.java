package cn.jia.chat.service;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

class ChatBountyExecutionCoordinatorV3SpringTransactionTest {
    @Test void readMethodsAreReadOnlyWhileIssueAndAdmitDelegateTheRootFirstAggregate() throws Exception {
        for(String method:new String[]{"preview","getIssue","getInteraction"}) {
            var candidate=java.util.Arrays.stream(ChatBountyInteractionV3AuthorityService.class
                    .getDeclaredMethods()).filter(value->method.equals(value.getName())).findFirst().orElseThrow();
            Transactional annotation=candidate.getAnnotation(Transactional.class);
            assertNotNull(annotation,method);assertTrue(annotation.readOnly(),method);
        }
        assertNull(java.util.Arrays.stream(ChatBountyInteractionV3AuthorityService.class.getDeclaredMethods())
                .filter(value->"admit".equals(value.getName())).findFirst().orElseThrow()
                .getAnnotation(Transactional.class));
        assertFalse(java.lang.reflect.Modifier.isFinal(
                ChatBountyInteractionV3AuthorityService.class.getModifiers()));
    }
}
