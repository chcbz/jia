package cn.jia.chat.service;

import cn.jia.chat.api.ChatBountyInteractionController;
import cn.jia.chat.api.ChatBountyInteractionV3Controller;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChatBountyInteractionV3AdmissionServiceTest {
    @Test void onlyExistingControllerOwnsInteractionPostAndItDispatchesSchemaTwoOrThree() throws Exception {
        List<Method> mappings=java.util.stream.Stream.of(
                        ChatBountyInteractionController.class,ChatBountyInteractionV3Controller.class)
                .flatMap(type->Arrays.stream(type.getDeclaredMethods()))
                .filter(method->{
                    PostMapping mapping=method.getAnnotation(PostMapping.class);
                    return mapping!=null&&Arrays.asList(mapping.value()).contains("/{conversationId}/interactions");
                }).toList();
        assertEquals(1,mappings.size());
        assertEquals(ChatBountyInteractionController.class,mappings.getFirst().getDeclaringClass());
        assertEquals("interact",mappings.getFirst().getName());
        assertNotNull(ChatBountyInteractionController.class.getDeclaredMethod("interact",String.class,
                String.class,String.class,org.springframework.security.core.Authentication.class,
                jakarta.servlet.http.HttpServletRequest.class));
    }
}
