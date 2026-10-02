package cn.jia.mmd;

import cn.jia.JiaApplication;
import cn.jia.agent.api.AgentTaskCreationOperationController;
import cn.jia.agent.api.AgentTaskDeliberationOperationController;
import cn.jia.agent.api.AgentTaskRequirementSnapshotController;
import cn.jia.agent.api.ControlledImagePointAndStartController;
import cn.jia.agent.api.PersonalWorkspaceConversationRuntimeController;
import cn.jia.agent.config.AgentSelectedOutputFinalizationSchemaInitializer;
import cn.jia.agent.config.AgentTaskBountyBootstrapOutboxSchemaInitializer;
import cn.jia.agent.config.AgentTaskCreationOperationSchemaInitializer;
import cn.jia.agent.config.AgentTaskExecutionGrantSchemaInitializer;
import cn.jia.agent.config.AgentTaskProviderCostConsentSchemaInitializer;
import cn.jia.agent.config.AgentTaskRequirementSnapshotSchemaInitializer;
import cn.jia.agent.config.ControlledImageBridgeSchemaInitializer;
import cn.jia.agent.config.ControlledImageExecutionSchemaInitializer;
import cn.jia.agent.config.ControlledImageFollowupV3SchemaInitializer;
import cn.jia.agent.config.PersonalWorkspaceExecutionSchemaInitializer;
import cn.jia.chat.api.ChatBountyInteractionController;
import cn.jia.chat.api.AgentTaskControlledImageCapabilityController;
import cn.jia.chat.api.AgentTaskPointAndStartCapabilityController;
import cn.jia.chat.api.ChatBountyInteractionV3Controller;
import cn.jia.chat.api.ChatBountyMediaController;
import cn.jia.chat.api.ChatBountyRequestIndexController;
import cn.jia.chat.api.ChatConversationAssetController;
import cn.jia.chat.api.ChatTypedDeliberationController;
import cn.jia.chat.api.ChatTypedInspectionController;
import cn.jia.chat.config.ChatConversationArchiveSchemaInitializer;
import cn.jia.chat.config.ChatDeliberationSchemaInitializer;
import cn.jia.chat.config.ChatSelectedOutputFinalizationSchemaInitializer;
import cn.jia.chat.config.ChatTypedDeliberationSchemaInitializer;
import cn.jia.chat.service.ChatBountyAssetProjector;
import cn.jia.chat.service.ChatBountyAssetRelay;
import cn.jia.chat.service.ChatBountyBootstrapRelay;
import cn.jia.chat.service.ChatBountyExecutionRelay;
import cn.jia.chat.service.ChatBountyRequestIndexService;
import cn.jia.chat.service.ChatDeliberationOutboxRelay;
import cn.jia.chat.service.ControlledImagePointAndStartCapabilityService;
import cn.jia.chat.service.PointAndStartCapabilityService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.scheduling.config.TaskManagementConfigUtils;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One opt-in, no-Provider fixture for the exact deployed 49a931 source catalog to 29ce.
 * It creates one frozen database name and drops it only after this process recorded creation.
 */
class Mmd29ceUpgradeWiringMySqlTest {
    private static final String BASE = "49a931357fe6b067c47a2dda425e31be8257193d";
    private static final String TARGET = "29ce5e130c20e213266ecc2cd7bde0a13a6a92f3";
    private static final String TARGET_TREE = "9ed6012a3a11d5e3fcef2ab5906d9db7a3881ead";
    private static final String PREFIX = "mmd29ce_20261002_01a0f2bb_";
    private static final String DATABASE = "mmd29ce_20261002_01a0f2bb_wiring_r4";
    private static final String ISOLATED_SCHEDULE_BEAN = "wxSchedule";
    private static final String OUTBOX_RELAY_REPAIR_PATH =
            "chat/jia-chat-service/src/main/java/cn/jia/chat/service/ChatDeliberationOutboxRelay.java";
    private static final String OUTBOX_RELAY_REGRESSION_PATH =
            "chat/jia-chat-service/src/chatDeliberationTest/java/cn/jia/chat/service/ChatDeliberationOutboxRelayTest.java";
    private static final Map<String, String> PRODUCTION_REPAIR_HASHES = Map.of(
            OUTBOX_RELAY_REPAIR_PATH,
            "d7c9b76ed7217fe376af8986dca5d15959151905e2f17be0af746ff7703a8bb1",
            OUTBOX_RELAY_REGRESSION_PATH,
            "b65d901686fa151144497ce7970b3501a6c3c357b56bea73a896d138d0cf65ad");
    private static final Set<String> AUTHORIZED_CANDIDATE_PATHS = Set.of(
            OUTBOX_RELAY_REPAIR_PATH,
            OUTBOX_RELAY_REGRESSION_PATH,
            "starter/build.gradle",
            "starter/src/mmd29ceUpgradeWiringTest/java/cn/jia/mmd/Mmd29ceUpgradeWiringMySqlTest.java");

    private static final List<DdlSource> OLD_SOURCE_DDL = List.of(
            new DdlSource("agent/jia-agent-mapper/src/main/resources/db/schema.sql", "b71aede84364dd8382a8e36552dbdce33aa042b88e0551cc168954676adff474"),
            new DdlSource("agent/jia-agent-mapper/src/main/resources/db/agent-personal-workspace-v1.sql", "07ff16f896378b1c76da6fe0b2e2e28e32b2bd624ec41a36cde3f8e73666baae"),
            new DdlSource("agent/jia-agent-mapper/src/main/resources/db/agent-personal-workspace-v1_11-executions.sql", "43dc0a2f4089830c2460427becb003190b5e8484951436d67f56e4e5417f6e26"),
            new DdlSource("agent/jia-agent-service/src/main/resources/db/agent-personal-workspace-v1_11-task-links.sql", "e665ad33168f9886a3c4fba9b31c534d9056975ddcace07ba5a2296de00b3abd"),
            new DdlSource("agent/jia-agent-service/src/main/resources/db/agent-personal-workspace-v1_13-task-execution.sql", "3b5eb2706a2b5a3e66c73ea875b955eb3b9e97812f2db47afd0088fbf3f53267"),
            new DdlSource("agent/jia-agent-service/src/main/resources/db/agent-personal-workspace-v1_13-task-publication.sql", "0d13d5b4deebf2950ca1460631f9ed71c475023bef382396d96f7d593c78d960"),
            new DdlSource("agent/jia-agent-service/src/main/resources/db/agent-personal-workspace-v1_13-conversation-links.sql", "485631af6797727aec2e078d9c388c8531b77ab18d198671f59ccd060a50f413"));

    private static final Map<String, String> TARGET_DDL = Map.ofEntries(
            Map.entry("agent/jia-agent-mapper/src/main/resources/db/agent-controlled-image-bridge-v1.sql", "da42613b14dcb82132c4c41f9ff188a5c00bc1ccb5eb50e8ac998d4a0f2fcfd0"),
            Map.entry("agent/jia-agent-mapper/src/main/resources/db/agent-controlled-image-followup-v3.sql", "a9be26f622ced29249409b44f83b13a3f7e296b2aed5d93db36fdf7cc6094932"),
            Map.entry("agent/jia-agent-mapper/src/main/resources/db/agent-selected-output-finalization-v1.sql", "4e1ab0212364e339289973dfc3f44b02e1d217740a2c923621dc206018dd8c4e"),
            Map.entry("agent/jia-agent-mapper/src/main/resources/db/agent-task-bounty-bootstrap-outbox-discovery-v2.sql", "c826f133b8146909015cb0133094b45ce54c95ed2099e053159f1f48ba930ef8"),
            Map.entry("agent/jia-agent-mapper/src/main/resources/db/agent-task-bounty-bootstrap-outbox-v1.sql", "8388446b4dffeffdb307a191ccc4a4bf0e1e475a876ce3a8c3056eb862791c46"),
            Map.entry("agent/jia-agent-mapper/src/main/resources/db/agent-task-creation-operation-v1.sql", "b7f5dbf3ed68a5002bf401728ada2f38cf8a9998b92dca0715db830965040b2d"),
            Map.entry("agent/jia-agent-mapper/src/main/resources/db/agent-task-execution-grant-v1.sql", "f51442ece80056d13f14e397d71e5eb85a13d6ec44821706a331f1c4de00598f"),
            Map.entry("agent/jia-agent-mapper/src/main/resources/db/agent-task-provider-cost-consent-v1.sql", "94676d1d40c483ff1c5f458662cfacb632a38204a27eeba9fe8dd94f2ec44192"),
            Map.entry("agent/jia-agent-mapper/src/main/resources/db/agent-task-requirement-snapshot-v1.sql", "0657860554c5406ebeffa9cbdff8dbe85cce141a2846ae7d1b5f38b5085cdd04"),
            Map.entry("agent/jia-agent-service/src/main/resources/db/agent-personal-workspace-v1_14-conversation-execution.sql", "959981bfccd8b000df35c01b3f27be2fe31ea808839c56b10ac3d4c1ac3da87e"),
            Map.entry("agent/jia-agent-service/src/main/resources/db/agent-personal-workspace-v1_14-conversation-output.sql", "1b878e4b9b714aaf907a1f15c8aa701589029e70ff9e634ad6cd180a20041d9b"),
            Map.entry("agent/jia-agent-service/src/main/resources/db/agent-personal-workspace-v1_15-conversation-lease.sql", "c66ddcc2492f611dcbfb9407a56754a6636595221a4644a02835a9374dd638e8"),
            Map.entry("agent/jia-agent-service/src/main/resources/db/agent-personal-workspace-v1_16-conversation-provider-start.sql", "2e4961b1a3bf83d568d2731ab5a767097cc51bde98b437c827af801177461af9"),
            Map.entry("agent/jia-agent-service/src/main/resources/db/agent-personal-workspace-v1_17-controlled-image.sql", "0f12d5908dfdf237f1cfcca30698c3203a912b496b519373bee70c0c7ff77e97"),
            Map.entry("chat/jia-chat-mapper/src/main/resources/db/chat-conversation-archive-schema.sql", "9218927c33ea31232c84ba822e616144179478625f0c7a71a8514fa71a1672a9"),
            Map.entry("chat/jia-chat-mapper/src/main/resources/db/chat-deliberation-schema.sql", "ff5f6b22ab5fc0e97afb3a39d9834124abc923b010e444a8228181c44d10bb3c"),
            Map.entry("chat/jia-chat-mapper/src/main/resources/db/chat-deliberation-v2-migration.sql", "4093d2c020767dde0476acc54696d83c0011e11158e3aad62c87c714e3804df2"),
            Map.entry("chat/jia-chat-mapper/src/main/resources/db/chat-selected-output-finalization-v1.sql", "0a7c93a85b6e89408448da53b3d2c2659c9907c1a0f6388b9f3645c837c962a1"),
            Map.entry("chat/jia-chat-mapper/src/main/resources/db/chat-typed-deliberation-schema-v1.sql", "98af3d88d5a1212e52764e308b2361e99dbffbb7595d1196123465a0481a41a4"));

    private static final Set<String> NEW_TABLES = Set.of(
            "agent_task_creation_operation", "agent_task_execution_grant",
            "agent_task_bounty_bootstrap_outbox", "agent_task_requirement_snapshot",
            "agent_task_provider_cost_consent", "agent_controlled_image_bridge_operation",
            "agent_controlled_image_intent_operation_grant", "agent_controlled_image_execution_source_v3",
            "agent_selected_output_finalization", "chat_context_snapshot", "chat_request",
            "chat_turn", "chat_dispatch_outbox", "chat_conversation_event",
            "chat_deliberation_schema_version",
            "chat_interaction_step", "chat_step_execution_link", "chat_bounty_binding",
            "chat_conversation_asset", "chat_typed_outcome", "chat_typed_pending_question",
            "chat_typed_proposal", "chat_typed_admission", "chat_conversation_archive_operation",
            "chat_selected_output_finalization", "chat_selected_output_finalization_item");
    private static final Set<String> NEW_BUSINESS_TABLES = NEW_TABLES.stream()
            .filter(table -> !"chat_deliberation_schema_version".equals(table))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    @Test
    void upgradesOldCatalogTwiceAndRegistersActualApplicationBeansAndEndpoints() throws Exception {
        Fixture fixture = Fixture.fromEnvironment();
        assertEquals(PREFIX, fixture.prefix());
        assertTargetSourceAndDdlHashes(fixture.repo());
        try (OwnedRedisServer redis = OwnedRedisServer.start()) {
            fixture.createEmptyDatabase();
            try {
                fixture.seedOldSourceCatalog();
                assertEquals(Set.of(), fixture.presentTables(NEW_TABLES));

                Catalog first;
                try (ConfigurableApplicationContext context = startApplication(fixture, redis, false)) {
                    forceMigrationBeans(context);
                    first = fixture.catalog();
                }
                assertEquals(NEW_TABLES, fixture.presentTables(NEW_TABLES));
                fixture.assertSchemaVersions();

                Catalog second;
                try (ConfigurableApplicationContext context = startApplication(fixture, redis, false)) {
                    forceMigrationBeans(context);
                    second = fixture.catalog();
                }
                assertEquals(first, second, "second application start must not drift the catalog");
                fixture.assertSchemaVersions();

                Catalog fullyWired;
                try (ConfigurableApplicationContext context = startApplication(fixture, redis, true)) {
                    forceMigrationBeans(context);
                    assertFeatureBeansRunning(context);
                    assertEndpointMappings(context);
                    fullyWired = fixture.catalog();
                }
                assertEquals(second, fullyWired,
                        "enabling the full feature wiring must not drift the catalog");
                fixture.assertSchemaVersions();
                fixture.assertNoBusinessRows();
            } finally {
                fixture.dropOwnedDatabase();
            }
        }
    }

    private static ConfigurableApplicationContext startApplication(
            Fixture fixture, OwnedRedisServer redis, boolean fullFeatureWiring) {
        redis.assertExactOwnedChildAlive();
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("spring.profiles.active", "mmd29ce-isolated");
        properties.put("spring.datasource.dbUrl", fixture.jdbcUrl());
        properties.put("spring.datasource.url", fixture.jdbcUrl());
        properties.put("spring.datasource.username", fixture.user());
        properties.put("spring.datasource.password", fixture.password());
        properties.put("spring.datasource.driverClassName", "com.mysql.cj.jdbc.Driver");
        properties.put("spring.datasource.initial-size", "0");
        properties.put("spring.datasource.min-idle", "0");
        properties.put("spring.datasource.max-active", "4");
        properties.put("spring.datasource.max-wait", "10000");
        properties.put("spring.datasource.time-between-eviction-runs-millis", "60000");
        properties.put("spring.datasource.min-evictable-idle-time-millis", "300000");
        properties.put("spring.datasource.validation-query", "SELECT 1");
        properties.put("spring.datasource.test-while-idle", "false");
        properties.put("spring.datasource.test-on-borrow", "false");
        properties.put("spring.datasource.test-on-return", "false");
        properties.put("spring.datasource.pool-prepared-statements", "false");
        properties.put("spring.datasource.max-pool-prepared-statement-per-connection-size", "0");
        properties.put("spring.sql.init.mode", "never");
        properties.put("spring.main.lazy-initialization", "true");
        properties.put("spring.main.banner-mode", "off");
        properties.put("server.address", "127.0.0.1");
        properties.put("server.port", "0");
        properties.put("server.tomcat.threads.max", "2");
        properties.put("server.tomcat.threads.min-spare", "1");
        properties.put("server.tomcat.max-connections", "4");
        properties.put("spring.session.store-type", "none");
        properties.put("spring.jmx.enabled", "false");
        properties.put("management.endpoints.enabled-by-default", "false");
        properties.put("spring.lifecycle.timeout-per-shutdown-phase", "5s");
        properties.put("spring.task.scheduling.enabled", "false");
        properties.put("jia.chat.service.websocket.enable", Boolean.toString(fullFeatureWiring));
        properties.put("spring.rabbitmq.listener.simple.auto-startup", "false");
        properties.put("spring.rabbitmq.listener.direct.auto-startup", "false");
        properties.put("spring.data.redis.repositories.enabled", "false");
        properties.put("spring.data.redis.host", redis.host());
        properties.put("spring.data.redis.port", Integer.toString(redis.port()));
        properties.put("spring.data.redis.database", "0");
        properties.put("spring.data.redis.password", redis.password());
        properties.put("spring.redis.host", redis.host());
        properties.put("spring.redis.port", Integer.toString(redis.port()));
        properties.put("spring.redis.database", "0");
        properties.put("spring.redis.password", redis.password());
        properties.put("spring.ai.model.chat", "none");
        properties.put("spring.ai.model.embedding", "none");
        properties.put("spring.ai.model.audio.transcription", "none");
        properties.put("spring.ai.model.audio.speech", "none");
        properties.put("spring.ai.mcp.client.enabled", "false");
        properties.put("spring.ai.mcp.client.toolcallback.enabled", "false");
        properties.put("spring.autoconfigure.exclude", String.join(",",
                "org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration",
                "org.springframework.boot.amqp.autoconfigure.health.RabbitHealthContributorAutoConfiguration",
                "org.springframework.boot.amqp.autoconfigure.metrics.RabbitMetricsAutoConfiguration",
                "org.springframework.ai.mcp.client.common.autoconfigure.McpClientAutoConfiguration",
                "org.springframework.ai.mcp.client.common.autoconfigure.McpToolCallbackAutoConfiguration",
                "org.springframework.ai.mcp.client.common.autoconfigure.annotations.McpClientAnnotationScannerAutoConfiguration"));
        properties.put("management.health.elasticsearch.enabled", "false");
        properties.put("management.health.redis.enabled", "false");
        properties.put("management.health.rabbit.enabled", "false");
        properties.put("camunda.bpm.enabled", "false");
        properties.put("archive.reader.enabled", "false");
        properties.put("archive.question.enabled", "false");
        properties.put("agent.task-events.enabled", "false");
        properties.put("agent.command-outbox.enabled", "false");
        properties.put("agent.rabbit-topology.enabled", "false");
        properties.put("agent.rabbit-publish.enabled", "false");
        properties.put("agent.rabbit-consume.enabled", "false");
        properties.put("agent.rabbit-dispatch.enabled", "false");
        properties.put("economy.preview.enabled", "false");
        properties.put("economy.onboarding-grant.enabled", "false");

        properties.put("agent.personal-workspace-storage.enabled", "true");
        properties.put("agent.personal-workspace-storage.root-directory", fixture.workspace().toString());
        properties.put("agent.personal-workspace-storage.max-content-bytes", "16777216");
        properties.put("agent.personal-workspace-storage.allowed-mime-types[0]", "image/png");
        properties.put("agent.personal-workspace-storage.allowed-mime-types[1]", "image/jpeg");
        properties.put("jia.agent.conversation-execution.enabled", "true");
        properties.put("jia.agent.formal-delivery.enabled", "true");
        properties.put("jia.agent.selected-output-finalization.enabled", "true");
        properties.put("agent.controlled-image-provider.bridge-enabled", "true");
        properties.put("agent.controlled-image-provider.enabled", "true");
        properties.put("agent.controlled-image-provider.followup-v3-enabled", "true");
        properties.put("agent.task-deliberation-operation.read-enabled", "true");
        properties.put("agent.task-requirement-snapshot.read-enabled", "true");
        properties.put("agent.task-reference-inputs.enabled", Boolean.toString(fullFeatureWiring));
        properties.put("cyf.chat.deliberation-schema.allow-additive-migration", "true");
        properties.put("chat.typed-deliberation.enabled", "true");
        properties.put("cyf.chat.typed-deliberation-schema.allow-additive-migration", "true");
        properties.put("chat.typed-inspection.enabled", "true");
        properties.put("chat.bounty-interactions.enabled", "true");
        properties.put("chat.bounty-media.enabled", Boolean.toString(fullFeatureWiring));
        properties.put("chat.bounty-asset.enabled", Boolean.toString(fullFeatureWiring));
        properties.put("chat.bounty-bootstrap.enabled", Boolean.toString(fullFeatureWiring));
        properties.put("chat.bounty-execution.enabled", Boolean.toString(fullFeatureWiring));
        properties.put("chat.conversation-archive.enabled", "true");
        properties.put("chat.selected-output-finalization.enabled", "true");
        properties.put("chat.native-bounty-capability.enabled", Boolean.toString(fullFeatureWiring));

        String[] arguments = properties.entrySet().stream()
                .map(entry -> "--" + entry.getKey() + "=" + entry.getValue())
                .toArray(String[]::new);
        ConfigurableApplicationContext context = new SpringApplicationBuilder(JiaApplication.class)
                .web(WebApplicationType.SERVLET)
                .initializers(applicationContext -> applicationContext.addBeanFactoryPostProcessor(beanFactory -> {
                    if (!(beanFactory instanceof BeanDefinitionRegistry registry)) {
                        throw new IllegalStateException("Application BeanFactory cannot isolate scheduling");
                    }
                    String scheduledProcessor =
                            TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME;
                    if (!registry.containsBeanDefinition(scheduledProcessor)) {
                        throw new IllegalStateException(
                                "JiaApplication scheduling processor was not registered as expected");
                    }
                    registry.removeBeanDefinition(scheduledProcessor);
                    if (!registry.containsBeanDefinition(ISOLATED_SCHEDULE_BEAN)) {
                        throw new IllegalStateException(
                                "JiaApplication did not scan the expected wxSchedule component");
                    }
                    registry.getBeanDefinition(ISOLATED_SCHEDULE_BEAN).setLazyInit(true);
                }))
                .run(arguments);
        assertFalse(context.containsBean(
                TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME),
                "isolated fixture must not start unrelated @Scheduled business jobs");
        assertTrue(context.containsBeanDefinition(ISOLATED_SCHEDULE_BEAN),
                "isolated fixture must retain JiaApplication component scanning");
        assertFalse(context.getBeanFactory().containsSingleton(ISOLATED_SCHEDULE_BEAN),
                "isolated fixture must not instantiate unrelated wxSchedule or access shared Redis");
        return context;
    }

    private static void forceMigrationBeans(ConfigurableApplicationContext context) {
        for (Class<?> type : List.of(
                PersonalWorkspaceExecutionSchemaInitializer.class,
                AgentTaskExecutionGrantSchemaInitializer.class,
                AgentTaskBountyBootstrapOutboxSchemaInitializer.class,
                AgentTaskRequirementSnapshotSchemaInitializer.class,
                AgentTaskCreationOperationSchemaInitializer.class,
                ControlledImageExecutionSchemaInitializer.class,
                ControlledImageBridgeSchemaInitializer.class,
                AgentTaskProviderCostConsentSchemaInitializer.class,
                ControlledImageFollowupV3SchemaInitializer.class,
                AgentSelectedOutputFinalizationSchemaInitializer.class,
                ChatDeliberationSchemaInitializer.class,
                ChatTypedDeliberationSchemaInitializer.class,
                ChatConversationArchiveSchemaInitializer.class,
                ChatSelectedOutputFinalizationSchemaInitializer.class)) {
            assertNotNull(context.getBean(type), "missing production initializer " + type.getName());
        }
    }

    private static void assertFeatureBeansRunning(ConfigurableApplicationContext context) {
        for (Class<?> type : List.of(
                AgentTaskCreationOperationController.class,
                AgentTaskDeliberationOperationController.class,
                AgentTaskRequirementSnapshotController.class,
                ControlledImagePointAndStartController.class,
                PersonalWorkspaceConversationRuntimeController.class,
                ChatBountyInteractionController.class,
                ChatBountyInteractionV3Controller.class,
                ChatTypedDeliberationController.class,
                ChatTypedInspectionController.class,
                ChatBountyRequestIndexController.class,
                ChatBountyMediaController.class,
                ChatConversationAssetController.class,
                AgentTaskPointAndStartCapabilityController.class,
                AgentTaskControlledImageCapabilityController.class,
                ChatBountyRequestIndexService.class,
                ChatBountyAssetProjector.class,
                PointAndStartCapabilityService.class,
                ControlledImagePointAndStartCapabilityService.class)) {
            assertNotNull(context.getBean(type), "JiaApplication did not construct " + type.getName());
        }
        assertTrue(context.getBean(ChatBountyBootstrapRelay.class).isRunning(),
                "bounty bootstrap relay did not start");
        assertTrue(context.getBean(ChatBountyAssetRelay.class).isRunning(),
                "bounty asset relay did not start");
        assertTrue(context.getBean(ChatBountyExecutionRelay.class).isRunning(),
                "bounty execution relay did not start");
        assertTrue(context.getBean(ChatDeliberationOutboxRelay.class).isRunning(),
                "deliberation outbox relay did not start");
    }

    private static void assertEndpointMappings(ConfigurableApplicationContext context) {
        RequestMappingHandlerMapping mappings = context.getBean(RequestMappingHandlerMapping.class);
        Set<String> paths = new TreeSet<>();
        mappings.getHandlerMethods().keySet().forEach(info -> paths.addAll(info.getPatternValues()));
        for (String required : Set.of(
                "/agent/tasks/creation-operations",
                "/agent/tasks/creation-operations/request",
                "/agent/tasks/{taskId}/assignment-operation",
                "/agent/tasks/{taskId}/requirements/current",
                "/agent/tasks/{taskId}/point-and-start-controlled-image",
                "/internal/agent/tasks/conversation-executions/commands",
                "/internal/agent/tasks/conversation-executions/controlled-image-v3-commands",
                "/chat/conversations/{conversationId}/interactions",
                "/chat/conversations/{conversationId}/interactions/context",
                "/chat/conversations/{conversationId}/interactions/preview",
                "/chat/conversations/{conversationId}/interactions/provider-consents",
                "/chat/conversations/{conversationId}/interactions/provider-consents/request",
                "/chat/conversations/{conversationId}/interactions/provider-consents/{consentId}/revoke",
                "/chat/conversations/{conversationId}/interactions/request",
                "/chat/conversations/{conversationId}/interactions/discussion",
                "/chat/conversations/{conversationId}/interactions/inspection",
                "/chat/conversations/{conversationId}/interactions/inspection/request",
                "/chat/conversations/{conversationId}/requests/{requestId}/typed-outcome",
                "/chat/conversations/{conversationId}/requests/{requestId}/inspection-outcome",
                "/chat/conversations/{conversationId}/requests",
                "/chat/requests/{requestId}/steps/{stepId}/outputs",
                "/chat/requests/{requestId}/steps/{stepId}/outputs/{outputId}",
                "/chat/conversations/{conversationId}/assets/{assetId}",
                "/chat/conversations/{conversationId}/assets/{assetId}/content",
                "/agent/tasks/{taskId}/point-and-start-capability",
                "/agent/tasks/{taskId}/point-and-start-controlled-image-capability")) {
            assertTrue(paths.contains(required), "missing endpoint mapping " + required + " in " + paths);
        }
    }

    private static void assertTargetSourceAndDdlHashes(Path repo) throws Exception {
        git(repo, "merge-base", "--is-ancestor", TARGET, "HEAD");
        assertEquals(TARGET, git(repo, "rev-parse", TARGET + "^{commit}").strip(),
                "fixture history must retain the exact production source commit");
        assertEquals(TARGET_TREE, git(repo, "rev-parse", TARGET + "^{tree}").strip());
        assertEquals(AUTHORIZED_CANDIDATE_PATHS,
                Set.copyOf(git(repo, "diff", "--name-only", TARGET + "..HEAD", "--")
                .lines().filter(line -> !line.isBlank()).toList()),
                "candidate may contain only the frozen fixture and exact audited production repair");
        assertTrue(git(repo, "status", "--porcelain", "--untracked-files=no").isBlank(),
                "tracked fixture source must be clean");
        for (Map.Entry<String, String> entry : PRODUCTION_REPAIR_HASHES.entrySet()) {
            assertEquals(entry.getValue(), sha256(Files.readAllBytes(repo.resolve(entry.getKey()))),
                    "production repair drift: " + entry.getKey());
        }
        for (Map.Entry<String, String> entry : TARGET_DDL.entrySet()) {
            assertEquals(entry.getValue(), sha256(Files.readAllBytes(repo.resolve(entry.getKey()))), entry.getKey());
        }
        for (DdlSource source : OLD_SOURCE_DDL) {
            assertEquals(source.sha256(), sha256(gitBytes(repo, BASE, source.path())),
                    BASE + ":" + source.path());
        }
    }

    private record DdlSource(String path, String sha256) { }

    private record Catalog(List<String> columns, List<String> indexes,
            List<String> checks, List<String> foreignKeys) { }

    private record Fixture(Path repo, String host, int port, String user, String password,
            String database, String prefix, Path workspace, AtomicBoolean databaseCreated) {
        static Fixture fromEnvironment() throws Exception {
            Path repo = Path.of(requiredProperty("cyf.mmd29ce.repo-root")).toRealPath();
            String host = requiredEnv("CYF_MMD29CE_MYSQL_HOST");
            assertEquals("127.0.0.1", host, "fixture only accepts its owned loopback adapter");
            int port = Integer.parseInt(requiredEnv("CYF_MMD29CE_MYSQL_PORT"));
            assertTrue(port > 0 && port <= 65535);
            String database = requiredEnv("CYF_MMD29CE_MYSQL_DATABASE");
            assertEquals(DATABASE, database, "fixture only accepts its frozen unique database name");
            assertTrue(database.startsWith(PREFIX));
            assertTrue(database.matches("[a-z0-9_]+"));
            Path workspace = Files.createTempDirectory("cyf-mmd29ce-workspace-").toRealPath();
            return new Fixture(repo, host, port, env("CYF_MMD29CE_MYSQL_USER", "root"),
                    env("CYF_MMD29CE_MYSQL_PASSWORD", ""), database, PREFIX, workspace,
                    new AtomicBoolean(false));
        }

        String jdbcUrl() {
            return "jdbc:mysql://" + host + ":" + port + "/" + database
                    + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia%2FShanghai"
                    + "&characterEncoding=UTF-8&connectionCollation=utf8mb4_0900_bin";
        }

        String adminUrl() {
            return "jdbc:mysql://" + host + ":" + port
                    + "/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia%2FShanghai";
        }

        void createEmptyDatabase() throws Exception {
            assertFalse(databaseCreated.get(), "fixture database was already created by this run");
            try (Connection connection = DriverManager.getConnection(adminUrl(), user, password);
                    Statement statement = connection.createStatement()) {
                statement.execute("CREATE DATABASE `" + database
                        + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
                databaseCreated.set(true);
            } catch (SQLException failure) {
                if (failure.getErrorCode() == 1007) {
                    throw new IllegalStateException(
                            "Fixture database already exists; refusing an unowned drop: " + database, failure);
                }
                throw failure;
            }
        }

        void dropOwnedDatabase() throws Exception {
            assertTrue(database.startsWith(prefix));
            try {
                if (!databaseCreated.get()) return;
                try (Connection connection = DriverManager.getConnection(adminUrl(), user, password);
                        Statement statement = connection.createStatement()) {
                    statement.execute("DROP DATABASE `" + database + "`");
                }
                assertTrue(databaseCreated.compareAndSet(true, false),
                        "fixture database ownership record changed during cleanup");
            } finally {
                deleteTree(workspace);
            }
        }

        void seedOldSourceCatalog() throws Exception {
            try (Connection connection = DriverManager.getConnection(jdbcUrl(), user, password)) {
                for (DdlSource source : OLD_SOURCE_DDL) {
                    executeScript(connection, gitBytes(repo, BASE, source.path()),
                            BASE + ":" + source.path());
                }
                executeScript(connection, oldChatCatalog(), "old-live-chat-support-catalog");
            }
        }

        Set<String> presentTables(Set<String> wanted) throws Exception {
            if (wanted.isEmpty()) return Set.of();
            String placeholders = String.join(",", java.util.Collections.nCopies(wanted.size(), "?"));
            try (Connection connection = DriverManager.getConnection(jdbcUrl(), user, password);
                    PreparedStatement statement = connection.prepareStatement(
                            "SELECT table_name FROM information_schema.tables WHERE table_schema=? "
                                    + "AND table_name IN (" + placeholders + ") ORDER BY table_name")) {
                statement.setString(1, database);
                int index = 2;
                for (String table : new TreeSet<>(wanted)) statement.setString(index++, table);
                Set<String> found = new LinkedHashSet<>();
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) found.add(result.getString(1));
                }
                return Set.copyOf(found);
            }
        }

        Catalog catalog() throws Exception {
            try (Connection connection = DriverManager.getConnection(jdbcUrl(), user, password)) {
                return new Catalog(queryLines(connection, """
                        SELECT CONCAT(table_name,'|',ordinal_position,'|',column_name,'|',column_type,'|',
                                      is_nullable,'|',COALESCE(column_default,'<NULL>'),'|',
                                      COALESCE(collation_name,'<NULL>'),'|',extra)
                        FROM information_schema.columns WHERE table_schema=?
                        ORDER BY table_name,ordinal_position
                        """), queryLines(connection, """
                        SELECT CONCAT(table_name,'|',index_name,'|',non_unique,'|',seq_in_index,'|',
                                      COALESCE(column_name,'<NULL>'),'|',COALESCE(sub_part,'<NULL>'),'|',
                                      index_type,'|',is_visible)
                        FROM information_schema.statistics WHERE table_schema=?
                        ORDER BY table_name,index_name,seq_in_index
                        """), queryLines(connection, """
                        SELECT CONCAT(tc.table_name,'|',tc.constraint_name,'|',tc.enforced,'|',cc.check_clause)
                        FROM information_schema.table_constraints tc
                        JOIN information_schema.check_constraints cc
                          ON cc.constraint_catalog=tc.constraint_catalog
                         AND cc.constraint_schema=tc.constraint_schema
                         AND cc.constraint_name=tc.constraint_name
                        WHERE tc.constraint_schema=? AND tc.constraint_type='CHECK'
                        ORDER BY tc.table_name,tc.constraint_name
                        """), queryLines(connection, """
                        SELECT CONCAT(table_name,'|',constraint_name,'|',referenced_table_name,'|',
                                      update_rule,'|',delete_rule)
                        FROM information_schema.referential_constraints WHERE constraint_schema=?
                        ORDER BY table_name,constraint_name
                        """));
            }
        }

        void assertSchemaVersions() throws Exception {
            try (Connection connection = DriverManager.getConnection(jdbcUrl(), user, password);
                    Statement statement = connection.createStatement();
                    ResultSet result = statement.executeQuery(
                            "SELECT version,stage FROM chat_deliberation_schema_version ORDER BY version")) {
                List<String> versions = new ArrayList<>();
                while (result.next()) versions.add(result.getInt(1) + ":" + result.getString(2));
                assertEquals(List.of("2:APPLIED", "3:APPLIED"), versions,
                        "production initializers must durably record both migration stages");
            }
        }

        void assertNoBusinessRows() throws Exception {
            try (Connection connection = DriverManager.getConnection(jdbcUrl(), user, password)) {
                for (String table : NEW_BUSINESS_TABLES) {
                    try (Statement statement = connection.createStatement();
                            ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM `" + table + "`")) {
                        assertTrue(result.next());
                        assertEquals(0L, result.getLong(1), "fixture unexpectedly created business rows in " + table);
                    }
                }
            }
        }

        private List<String> queryLines(Connection connection, String sql) throws Exception {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, database);
                List<String> rows = new ArrayList<>();
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) rows.add(result.getString(1));
                }
                return List.copyOf(rows);
            }
        }
    }

    private static byte[] oldChatCatalog() {
        return """
                CREATE TABLE chat_conversation (
                  id BIGINT NOT NULL AUTO_INCREMENT,
                  title VARCHAR(500) DEFAULT NULL,
                  jiacn VARCHAR(50) DEFAULT NULL,
                  status INT DEFAULT 0,
                  conversation_type VARCHAR(20) DEFAULT 'normal',
                  conversation_scope_type VARCHAR(20) DEFAULT NULL,
                  conversation_scope_key VARCHAR(120) DEFAULT NULL,
                  task_id VARCHAR(64) DEFAULT NULL,
                  target_agent_id VARCHAR(100) DEFAULT NULL,
                  create_time BIGINT DEFAULT NULL,
                  update_time BIGINT DEFAULT NULL,
                  client_id VARCHAR(50) DEFAULT NULL,
                  tenant_id VARCHAR(50) DEFAULT '0',
                  PRIMARY KEY (id),
                  KEY idx_jiacn (jiacn),
                  KEY idx_tenant_id (tenant_id),
                  KEY idx_status (status),
                  KEY idx_create_time (create_time),
                  KEY idx_conversation_type (conversation_type),
                  KEY idx_chat_conversation_scope (conversation_type,conversation_scope_type,conversation_scope_key)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
                CREATE TABLE chat_message (
                  id BIGINT NOT NULL AUTO_INCREMENT,
                  conversation_id VARCHAR(100) NOT NULL,
                  message_type VARCHAR(20) DEFAULT NULL,
                  content TEXT,
                  metadata TEXT,
                  create_time BIGINT DEFAULT NULL,
                  update_time BIGINT DEFAULT NULL,
                  client_id VARCHAR(50) DEFAULT NULL,
                  tenant_id VARCHAR(50) DEFAULT '0',
                  jiacn VARCHAR(50) DEFAULT NULL,
                  sync_status VARCHAR(20) DEFAULT NULL,
                  conversation_type VARCHAR(20) DEFAULT NULL,
                  sender_type VARCHAR(20) DEFAULT NULL,
                  sender_name VARCHAR(100) DEFAULT NULL,
                  PRIMARY KEY (id), KEY idx_conversation_id (conversation_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
                """.getBytes(StandardCharsets.UTF_8);
    }

    private static void executeScript(Connection connection, byte[] bytes, String name) {
        ScriptUtils.executeSqlScript(connection, new EncodedResource(
                new ByteArrayResource(bytes, name), StandardCharsets.UTF_8));
    }

    private static byte[] gitBytes(Path repo, String commit, String path) throws Exception {
        Process process = new ProcessBuilder("git", "show", commit + ":" + path)
                .directory(repo.toFile()).redirectErrorStream(false).start();
        byte[] output;
        byte[] error;
        try (InputStream stdout = process.getInputStream(); InputStream stderr = process.getErrorStream()) {
            output = stdout.readAllBytes();
            error = stderr.readAllBytes();
        }
        int exit = process.waitFor();
        assertEquals(0, exit, "git show failed for " + path + ": " + new String(error, StandardCharsets.UTF_8));
        return output;
    }

    private static String git(Path repo, String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).directory(repo.toFile()).redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        process.getInputStream().transferTo(output);
        assertEquals(0, process.waitFor(), output.toString(StandardCharsets.UTF_8));
        return output.toString(StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String requiredProperty(String key) {
        String value = System.getProperty(key);
        assertNotNull(value, "missing system property " + key);
        assertFalse(value.isBlank(), "blank system property " + key);
        return value;
    }

    private static String requiredEnv(String key) {
        String value = System.getenv(key);
        assertNotNull(value, "missing environment " + key);
        assertFalse(value.isBlank(), "blank environment " + key);
        return value;
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null ? fallback : value;
    }

    private static void deleteTree(Path root) throws Exception {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static final class OwnedRedisServer implements AutoCloseable {
        private static final String OWNER_MARKER = "MMD29CE-OWNED-REDIS";
        private static final Duration START_TIMEOUT = Duration.ofSeconds(10);
        private static final Duration STOP_TIMEOUT = Duration.ofSeconds(5);
        private static final SecureRandom PASSWORD_RANDOM = new SecureRandom();
        private static final Map<String, String> BINARY_SHA256 = Map.of(
                "redis-server-7.4.1-linux-amd64",
                "ff1628a3c48e4e4e409fac6a72395d1cf181d9c07566f15daf807d7e270259a4",
                "redis-server-7.4.1-linux-arm64",
                "86d14ebaf58d55c2c4513de2617f90b844ddf40c561fd7f350b98648dfa6ebef");

        private final Path root;
        private final Process process;
        private final long pid;
        private final Instant startInstant;
        private final int port;
        private final String password;

        private OwnedRedisServer(
                Path root, Process process, Instant startInstant, int port, String password) {
            this.root = root;
            this.process = process;
            this.pid = process.pid();
            this.startInstant = startInstant;
            this.port = port;
            this.password = password;
        }

        static OwnedRedisServer start() throws Exception {
            Path root = createOwnedRoot();
            Process process = null;
            OwnedRedisServer server = null;
            try {
                Path binary = extractVerifiedBinary(root);
                requireExactVersion(binary, "7.4.1");
                int port = freePort();
                String password = newPassword();
                Path config = root.resolve("redis.conf");
                Files.writeString(config, String.format(Locale.ROOT, """
                        bind 127.0.0.1
                        port %d
                        protected-mode yes
                        requirepass %s
                        save ""
                        appendonly no
                        daemonize no
                        databases 1
                        dir %s
                        dbfilename dump.rdb
                        pidfile %s
                        logfile ""
                        """, port, password, root, root.resolve("redis.pid")),
                        StandardCharsets.US_ASCII, StandardOpenOption.CREATE_NEW);

                ProcessBuilder builder = new ProcessBuilder(binary.toString(), config.toString());
                builder.directory(root.toFile());
                builder.redirectErrorStream(true);
                builder.redirectOutput(root.resolve("redis.log").toFile());
                builder.environment().clear();
                builder.environment().put("PATH",
                        "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
                builder.environment().put("LANG", "C");
                builder.environment().put("LC_ALL", "C");
                builder.environment().put("HOME", root.toString());
                builder.environment().put("TMPDIR", root.resolve("tmp").toString());
                process = builder.start();
                Instant startInstant = process.toHandle().info().startInstant()
                        .orElseThrow(() -> new IOException(
                                "Owned Redis child start identity unavailable"));
                server = new OwnedRedisServer(root, process, startInstant, port, password);
                server.awaitReady();
                System.out.printf(Locale.ROOT,
                        "MMD29CE_OWNED_REDIS_READY pid=%d start=%s host=%s port=%d root=%s%n",
                        server.pid, server.startInstant, server.host(), server.port, server.root);
                return server;
            } catch (Throwable failure) {
                try {
                    if (server != null) {
                        server.close();
                    } else {
                        stopNewChild(process);
                        deleteOwnedRoot(root);
                    }
                } catch (Throwable cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                throw failure;
            }
        }

        String host() {
            return "127.0.0.1";
        }

        int port() {
            return port;
        }

        String password() {
            return password;
        }

        void assertExactOwnedChildAlive() {
            assertTrue(process.isAlive(), "owned Redis child exited before application start");
            assertTrue(isExactOwnedChild(), "owned Redis child identity changed before application start");
        }

        private void awaitReady() throws Exception {
            long deadline = System.nanoTime() + START_TIMEOUT.toNanos();
            IOException lastFailure = null;
            while (System.nanoTime() < deadline) {
                if (!process.isAlive()) {
                    throw new IOException("Owned Redis exited before readiness: " + boundedLog());
                }
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(host(), port), 100);
                    return;
                } catch (IOException exception) {
                    lastFailure = exception;
                    Thread.sleep(25);
                }
            }
            throw new IOException("Owned Redis readiness timed out: " + boundedLog(), lastFailure);
        }

        @Override
        public void close() throws Exception {
            IOException failure = null;
            if (process.isAlive()) {
                if (!isExactOwnedChild()) {
                    failure = new IOException(
                            "Owned Redis child identity changed; refusing process control");
                } else {
                    process.destroy();
                    if (!process.waitFor(STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                        if (!isExactOwnedChild()) {
                            failure = new IOException(
                                    "Owned Redis child identity changed before forced stop");
                        } else {
                            process.destroyForcibly();
                            if (!process.waitFor(STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                                failure = new IOException("Owned Redis child did not stop");
                            }
                        }
                    }
                }
            }
            if (!process.isAlive()) {
                try {
                    deleteOwnedRoot(root);
                    System.out.printf(Locale.ROOT,
                            "MMD29CE_OWNED_REDIS_CLOSED pid=%d start=%s port=%d%n",
                            pid, startInstant, port);
                } catch (IOException cleanupFailure) {
                    if (failure == null) {
                        failure = cleanupFailure;
                    } else {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }

        private boolean isExactOwnedChild() {
            return process.pid() == pid && process.toHandle().info().startInstant()
                    .map(startInstant::equals).orElse(false);
        }

        private String boundedLog() {
            Path log = root.resolve("redis.log");
            if (!Files.exists(log)) {
                return "";
            }
            try (InputStream input = Files.newInputStream(log)) {
                return new String(input.readNBytes(4096), StandardCharsets.UTF_8);
            } catch (IOException exception) {
                return "log unavailable";
            }
        }

        private static Path createOwnedRoot() throws IOException {
            Path root = Files.createTempDirectory("cyf-mmd29ce-redis-");
            Files.writeString(root.resolve(OWNER_MARKER), "owned\n",
                    StandardCharsets.US_ASCII, StandardOpenOption.CREATE_NEW);
            Files.createDirectory(root.resolve("tmp"));
            return root;
        }

        private static Path extractVerifiedBinary(Path root) throws Exception {
            String resource = binaryResource();
            Path binary = root.resolve("redis-server");
            try (InputStream input = Mmd29ceUpgradeWiringMySqlTest.class
                    .getResourceAsStream("/" + resource)) {
                if (input == null) {
                    throw new IOException("Embedded Redis 7.4.1 binary resource is unavailable");
                }
                Files.copy(input, binary);
            }
            if (!BINARY_SHA256.get(resource).equals(sha256(binary))) {
                throw new IOException("Embedded Redis 7.4.1 binary checksum mismatch");
            }
            if (!binary.toFile().setExecutable(true, true)) {
                throw new IOException("Embedded Redis 7.4.1 binary is not executable");
            }
            return binary;
        }

        private static String binaryResource() throws IOException {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (!os.contains("linux")) {
                throw new IOException("Embedded Redis fixture requires Linux");
            }
            String architecture = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
            String suffix = switch (architecture) {
                case "amd64", "x86_64" -> "amd64";
                case "aarch64", "arm64" -> "arm64";
                default -> throw new IOException(
                        "Embedded Redis fixture does not support architecture " + architecture);
            };
            return "redis-server-7.4.1-linux-" + suffix;
        }

        private static void requireExactVersion(Path binary, String expected) throws Exception {
            Process probe = new ProcessBuilder(binary.toString(), "--version")
                    .redirectErrorStream(true).start();
            if (!probe.waitFor(STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                probe.destroyForcibly();
                probe.waitFor(STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                throw new IOException("Owned Redis version probe timed out");
            }
            String output = new String(probe.getInputStream().readAllBytes(),
                    StandardCharsets.US_ASCII);
            if (probe.exitValue() != 0) {
                throw new IOException("Owned Redis version probe failed");
            }
            Matcher matcher = Pattern.compile("(?:v=)?(\\d+\\.\\d+(?:\\.\\d+)?)")
                    .matcher(output);
            if (!matcher.find() || !expected.equals(matcher.group(1))) {
                throw new IOException("Expected Redis " + expected + " but version output differed");
            }
        }

        private static int freePort() throws IOException {
            try (ServerSocket socket = new ServerSocket()) {
                socket.bind(new InetSocketAddress("127.0.0.1", 0));
                return socket.getLocalPort();
            }
        }

        private static String newPassword() {
            byte[] bytes = new byte[32];
            PASSWORD_RANDOM.nextBytes(bytes);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        }

        private static String sha256(Path file) throws Exception {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DigestInputStream input = new DigestInputStream(
                    Files.newInputStream(file), digest)) {
                input.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        }

        private static void stopNewChild(Process process) throws Exception {
            if (process == null || !process.isAlive()) {
                return;
            }
            process.destroy();
            if (!process.waitFor(STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                if (!process.waitFor(STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new IOException("New owned Redis child did not stop");
                }
            }
        }

        private static void deleteOwnedRoot(Path root) throws IOException {
            if (!Files.isRegularFile(root.resolve(OWNER_MARKER))) {
                throw new IOException("Owned Redis fixture marker missing");
            }
            try (Stream<Path> paths = Files.walk(root)) {
                IOException[] failure = new IOException[1];
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException exception) {
                        if (failure[0] == null) {
                            failure[0] = exception;
                        } else {
                            failure[0].addSuppressed(exception);
                        }
                    }
                });
                if (failure[0] != null) {
                    throw failure[0];
                }
            }
        }
    }

}
