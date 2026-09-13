package cn.jia.agent.api;

import cn.jia.agent.output.OutputDeliveryService;
import cn.jia.agent.service.AgentTaskArtifactContentService;
import cn.jia.agent.service.AgentTaskArtifactService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

class OutputPublicationMappingCoexistenceTest {
    @Test
    void actualSpringRegistryKeepsJwtArtifactAndRunTicketPublicationPostsDistinct() {
        try (AnnotationConfigWebApplicationContext context =
                     new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.register(TestConfig.class);
            context.refresh();

            RequestMappingHandlerMapping mappings =
                    context.getBean(RequestMappingHandlerMapping.class);
            var artifact = mappings.getHandlerMethods().entrySet().stream()
                    .filter(entry -> entry.getKey().getPatternValues()
                            .contains("/agent/tasks/{taskId}/artifacts"))
                    .filter(entry -> entry.getKey().getMethodsCondition().getMethods()
                            .contains(RequestMethod.POST))
                    .toList();
            var publication = mappings.getHandlerMethods().entrySet().stream()
                    .filter(entry -> entry.getKey().getPatternValues()
                            .contains("/agent/tasks/{taskId}/output-publications"))
                    .filter(entry -> entry.getKey().getMethodsCondition().getMethods()
                            .contains(RequestMethod.POST))
                    .toList();

            assertEquals(1, artifact.size());
            assertEquals(1, publication.size());
            assertSame(AgentTaskArtifactController.class,
                    artifact.getFirst().getValue().getBeanType());
            assertSame(OutputDeliveryController.class,
                    publication.getFirst().getValue().getBeanType());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    static class TestConfig {
        @Bean
        OutputDeliveryService outputDeliveryService() {
            return mock(OutputDeliveryService.class);
        }

        @Bean
        AgentTaskArtifactService agentTaskArtifactService() {
            return mock(AgentTaskArtifactService.class);
        }

        @Bean
        AgentTaskArtifactContentService agentTaskArtifactContentService() {
            return mock(AgentTaskArtifactContentService.class);
        }

        @Bean
        OutputDeliveryController outputDeliveryController(OutputDeliveryService service) {
            return new OutputDeliveryController(service);
        }

        @Bean
        AgentTaskArtifactController agentTaskArtifactController(
                AgentTaskArtifactService artifactService,
                AgentTaskArtifactContentService contentService) {
            return new AgentTaskArtifactController(artifactService, contentService);
        }
    }
}
