package cn.jia.agent.service.recommendation;

import cn.jia.agent.entity.AssigneeRecommendation.Workload;
import cn.jia.economy.pricing.TaskQuoteCalculator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AssigneeQuoteRoutesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(AssigneeQuoteRoutes.class);
    @Test void noConfigurationMeansNoImplicitRoute() {
        runner.run(context -> { assertNull(context.getStartupFailure()); assertNull(context.getBean(AssigneeQuoteRoutes.class).find("a", Workload.Kind.DOCUMENT)); });
    }
    @Test void bindsExactRouteAndToolUnitsWithoutDisplayModelGuessing() {
        runner.withPropertyValues("jia.agent.task-pricing.routes[0].agent-id=a", "jia.agent.task-pricing.routes[0].kind=MEDIA",
                "jia.agent.task-pricing.routes[0].channel=real-channel", "jia.agent.task-pricing.routes[0].model=real-model",
                "jia.agent.task-pricing.routes[0].tier=standard", "jia.agent.task-pricing.routes[0].tools[0].channel=tool-channel",
                "jia.agent.task-pricing.routes[0].tools[0].tool=render", "jia.agent.task-pricing.routes[0].tools[0].unit=image",
                "jia.agent.task-pricing.routes[0].tools[0].units-per-deliverable=1.5").run(context -> {
            assertNull(context.getStartupFailure()); var routes = context.getBean(AssigneeQuoteRoutes.class);
            var route = routes.find("a", Workload.Kind.MEDIA); assertNotNull(route);
            assertEquals("real-channel", route.modelKey().channel()); assertNull(routes.find("a", Workload.Kind.DOCUMENT));
            assertNull(routes.find("other", Workload.Kind.MEDIA));
            var work = route.workload(new Workload(Workload.Kind.MEDIA, Workload.Complexity.COMPLEX, 2), 4);
            assertEquals(4, work.inputFiles()); assertEquals(2, work.deliverables());
            assertEquals(new BigDecimal("3.0"), work.toolUnits().get(new TaskQuoteCalculator.ToolKey("tool-channel", "render", "image")));
        });
    }
    @Test void emptyToolListBindsForTokenOnlyRoute() {
        runner.withPropertyValues("jia.agent.task-pricing.routes[0].agent-id=a", "jia.agent.task-pricing.routes[0].kind=DOCUMENT",
                "jia.agent.task-pricing.routes[0].channel=c", "jia.agent.task-pricing.routes[0].model=m",
                "jia.agent.task-pricing.routes[0].tier=standard", "jia.agent.task-pricing.routes[0].tools=").run(context -> {
            assertNull(context.getStartupFailure()); assertTrue(context.getBean(AssigneeQuoteRoutes.class).find("a", Workload.Kind.DOCUMENT).tools().isEmpty());
        });
    }
    @Test void duplicateRouteOrToolIsConfigurationErrorNotArbitrarySelection() {
        var route = new AssigneeQuoteRoutes.Route("a", Workload.Kind.DOCUMENT, "c", "m", "standard", List.of());
        assertThrows(IllegalArgumentException.class, () -> new AssigneeQuoteRoutes(new AssigneeQuoteRoutes.Properties(List.of(route, route))));
        var tool = new AssigneeQuoteRoutes.Tool("c", "render", "image", BigDecimal.ONE);
        assertThrows(IllegalArgumentException.class, () -> new AssigneeQuoteRoutes.Route("a", Workload.Kind.MEDIA, "c", "m", "standard", List.of(tool, tool)));
        assertThrows(NullPointerException.class, () -> new AssigneeQuoteRoutes.Route("a", Workload.Kind.MEDIA, "c", "m", "standard", null));
    }
}
