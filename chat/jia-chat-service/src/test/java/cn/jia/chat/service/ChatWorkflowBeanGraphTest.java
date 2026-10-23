package cn.jia.chat.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;

/** Real workflow services, boundary stores/IO mocked. Boot's no-circular-reference policy
 * must hold with both production gates enabled, regardless of singleton creation order. */
class ChatWorkflowBeanGraphTest {
    private static final List<Class<?>> SERVICES=List.of(ChatDeliberationService.class,
            ChatActionFinalService.class,ChatDeliverySubmissionService.class,
            ChatSelectedOutputFinalizationService.class,ChatCompletedMessageSourceService.class,
            ChatDeliveryRevisionService.class,ChatTypedReviewContextService.class,
            ChatFormalDeliveryRetentionService.class);

    @Test void workflowStartsWithReviewEnabledAndCircularReferencesDisabled() { start(false); }
    @Test void workflowStartsWhenFinalizationIsCreatedBeforeDeliberation() { start(true); }

    private void start(boolean reverse) {
        try(var context=new AnnotationConfigApplicationContext()) {
            context.setAllowCircularReferences(false);
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("workflow-gates",Map.of(
                    "chat.delivery-review.enabled","true","chat.selected-output-finalization.enabled","true")));
            var boundaries=new LinkedHashSet<Class<?>>();
            for(var type:SERVICES) {
                for(var constructor:type.getConstructors()) Collections.addAll(boundaries,constructor.getParameterTypes());
                for(var method:type.getMethods()) if(method.isAnnotationPresent(Autowired.class))
                    Collections.addAll(boundaries,method.getParameterTypes());
            }
            boundaries.removeAll(SERVICES);boundaries.remove(ObjectProvider.class);
            for(var type:boundaries) registerBoundary(context,type);
            var order=new ArrayList<>(SERVICES);if(reverse) Collections.reverse(order);
            context.register(order.toArray(Class<?>[]::new));
            context.refresh();
            for(var type:SERVICES) {
                var service=context.getBean(type);
                assertNotNull(service);assertFalse(mockingDetails(service).isMock());
            }
        }
    }
    private <T> void registerBoundary(AnnotationConfigApplicationContext context,Class<T> type) {
        // Boundary mocks must not undergo annotation injection into their own production setters.
        // Core SERVICES remain ordinary real bean definitions and are fully autowired.
        context.getBeanFactory().registerSingleton("boundary:"+type.getName(),mock(type));
    }
}
