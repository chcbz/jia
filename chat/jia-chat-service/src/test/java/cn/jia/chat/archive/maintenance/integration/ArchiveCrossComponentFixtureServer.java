package cn.jia.chat.archive.maintenance.integration;

import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import cn.jia.chat.archive.config.ArchiveSchemaInitializer;
import cn.jia.chat.archive.maintenance.config.ArchiveMaintenanceProperties;
import cn.jia.chat.archive.maintenance.config.ArchiveMaintenanceSchemaInitializer;
import cn.jia.chat.archive.maintenance.store.ArchiveMaintenanceStore;
import cn.jia.chat.archive.service.ArchiveMySqlTestGuard;
import cn.jia.chat.archive.service.ArchiveTransactions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServer;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.filter.CharacterEncodingFilter;
import org.springframework.web.servlet.DispatcherServlet;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.sql.Statement;
import java.time.Duration;
import java.util.Base64;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Disposable bounded fixture main. It proves real HTTP/controller/JDBC/private-artifact behavior
 * and delegates only registration, identity, admission transport and install transport prerequisites.
 */
public final class ArchiveCrossComponentFixtureServer {
    private static final byte[] SOURCE = "第一章\n夹具正文。\n".getBytes(StandardCharsets.UTF_8);
    private static final String COLLECTION = "platform-classics";
    private static final String ADMIN_AUTH_PREFIX = "Bearer ";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(20);

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .version(HttpClient.Version.HTTP_1_1).build();
    private String origin;
    private String adminAuthorization;

    public static void main(String[] args) throws Exception {
        new ArchiveCrossComponentFixtureServer().run();
    }

    private void run() throws Exception {
        requireEnvironment();
        ArchiveMySqlTestGuard.requireDisposable(System.getenv());
        Path readyFile = path("CYF_FIXTURE_READY_FILE");
        Path commandFile = path("CYF_FIXTURE_COMMAND_FILE");
        Path clientResultFile = path("CYF_FIXTURE_CLIENT_RESULT_FILE");
        Path finalResultFile = path("CYF_FIXTURE_FINAL_RESULT_FILE");
        String host = required("CYF_FIXTURE_HOST");
        InetAddress address = InetAddress.getByName(host);
        if (address.isAnyLocalAddress()
                || !(address.isLoopbackAddress() || address.isSiteLocalAddress())) {
            throw new IllegalStateException(
                    "CYF_FIXTURE_HOST must resolve to loopback or an explicit private address");
        }
        adminAuthorization = ADMIN_AUTH_PREFIX + required("CYF_FIXTURE_ADMIN_TOKEN");
        Files.deleteIfExists(readyFile);
        Files.deleteIfExists(commandFile);
        Files.deleteIfExists(clientResultFile);
        Files.deleteIfExists(finalResultFile);

        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory(0);
        factory.setAddress(address);
        Path tomcatRoot = path("CYF_FIXTURE_ARTIFACT_ROOT").resolveSibling("archive-fixture-tomcat");
        Files.createDirectories(tomcatRoot);
        factory.setBaseDirectory(tomcatRoot.toFile());
        WebServer server = factory.getWebServer(servlets -> {
            context.setServletContext(servlets);
            context.register(ArchiveCrossComponentFixtureConfiguration.class);
            context.refresh();
            servlets.addFilter("fixture-encoding", new CharacterEncodingFilter("UTF-8", true))
                    .addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), false, "/*");
            AgentRuntimeAuthenticationService runtime = context.getBean(AgentRuntimeAuthenticationService.class);
            servlets.addFilter("fixture-runtime-auth",
                            ArchiveCrossComponentFixtureConfiguration.runtimeFilter(runtime))
                    .addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), false, "/internal/archive/*");
            servlets.addFilter("fixture-admin-auth",
                            ArchiveCrossComponentFixtureConfiguration.bearerFilter(adminAuthorization))
                    .addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), false, "/archive/*");
            servlets.addFilter("fixture-security-context-request",
                            ArchiveCrossComponentFixtureConfiguration.securityContextRequestFilter())
                    .addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), true,
                            "/archive/*", "/internal/archive/*");
            servlets.addServlet("fixture-dispatcher", new DispatcherServlet(context))
                    .addMapping("/");
        });

        try {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            initializeDisposableSchema(jdbc, context);
            bindRuntime(context.getBean(AgentRuntimeAuthenticationService.class));
            server.start();
            origin = "http://" + host + ":" + server.getPort();

            String sourcePath = "/archive/admin/v1/collections/" + COLLECTION + "/source-snapshots";
            Map<String, Object> sourceRequest = Map.of(
                    "sourceName", "fixture-source", "sourceVersion", "v1",
                    "rightsBasis", "authorized fixture text", "declaredSha256", sha256(SOURCE),
                    "contentBase64", Base64.getEncoder().encodeToString(SOURCE));
            assertWrongBearerRejected(sourcePath, sourceRequest);
            JsonNode source = postAdmin(sourcePath, "fixture-source", null, sourceRequest);
            String sourceId = text(source, "sourceId");

            JsonNode appointment = postAdmin("/archive/admin/v1/collections/" + COLLECTION + "/appointments",
                    "fixture-appointment", "\"v0\"", Map.of(
                            "agentId", ArchiveCrossComponentFixtureConfiguration.AGENT,
                            "expectedBindingVersion", Long.toString(ArchiveCrossComponentFixtureConfiguration.BINDING),
                            "workScopeMode", "COLLECTION", "workIds", java.util.List.of(),
                            "permissionProfile", "PUBLISH_VALIDATED",
                            "requiredSkill", Map.of("key", FixtureArchiveExecutionPort.SKILL_KEY,
                                    "version", FixtureArchiveExecutionPort.SKILL_VERSION,
                                    "packageSha256", FixtureArchiveExecutionPort.PACKAGE_SHA256)));
            String appointmentId = text(appointment, "appointmentId");

            JsonNode manualJob = createJob(sourceId, "manual", "MANUAL");
            JsonNode autoJob = createJob(sourceId, "auto", "AUTO");
            JsonNode manualExecution = execute(text(manualJob, "jobId"), text(manualJob, "revision"), "manual");
            JsonNode autoExecution = execute(text(autoJob, "jobId"), text(autoJob, "revision"), "auto");

            FixtureArchiveExecutionPort port = context.getBean(FixtureArchiveExecutionPort.class);
            JsonNode manualWire = mapper.readTree(port.wireForJob(text(manualJob, "jobId")));
            JsonNode autoWire = mapper.readTree(port.wireForJob(text(autoJob, "jobId")));
            writeJson(commandFile, Map.of(
                    "schemaVersion", 1,
                    "installationId", FixtureArchiveExecutionPort.INSTALLATION_ID,
                    "packageSha256", FixtureArchiveExecutionPort.PACKAGE_SHA256,
                    "manual", manualWire,
                    "auto", autoWire));
            writeJson(readyFile, Map.of(
                    "status", "ready", "origin", origin, "sourceSha256", sha256(SOURCE),
                    "sourceId", sourceId, "appointmentId", appointmentId,
                    "manualJobId", text(manualJob, "jobId"), "autoJobId", text(autoJob, "jobId"),
                    "manualCommandId", text(manualExecution, "commandId"),
                    "autoCommandId", text(autoExecution, "commandId")));

            JsonNode clientResult = waitForClientResult(clientResultFile);
            require("completed".equals(clientResult.path("manualStatus").asText()),
                    "Client MANUAL outcome was not terminal completed");
            require("completed".equals(clientResult.path("autoStatus").asText()),
                    "Client AUTO outcome was not terminal completed");
            require("completed".equals(clientResult.path("autoReplayStatus").asText()),
                    "Client AUTO replay was not terminal completed");
            require(FixtureArchiveExecutionPort.PACKAGE_SHA256.equals(
                    clientResult.path("packageSha256").asText()), "Client package proof changed");

            String manualJobId = text(manualJob, "jobId");
            String autoJobId = text(autoJob, "jobId");
            require(count(jdbc, "SELECT COUNT(*) FROM archive_publication WHERE job_id=?", manualJobId) == 0,
                    "MANUAL runner published without explicit administrator action");
            String manualValidation = jdbc.queryForObject(
                    "SELECT validation_id FROM archive_draft WHERE job_id=?", String.class, manualJobId);
            long manualDraftRevision = jdbc.queryForObject(
                    "SELECT revision FROM archive_draft WHERE job_id=?", Long.class, manualJobId);
            JsonNode manualPublication = postAdmin("/archive/admin/v1/jobs/" + manualJobId + "/publish",
                    "fixture-manual-publish", "\"v" + manualDraftRevision + "\"", nullableMap(
                            "validationId", manualValidation, "expectedActiveEditionId", null,
                            "expectedWorkRevision", "0"));

            require(count(jdbc, "SELECT COUNT(*) FROM archive_publication WHERE job_id=?", manualJobId) == 1,
                    "MANUAL explicit publication was not committed exactly once");
            require(count(jdbc, "SELECT COUNT(*) FROM archive_publication WHERE job_id=?", autoJobId) == 1,
                    "AUTO publication replay created zero or multiple publications");
            require(count(jdbc, "SELECT COUNT(*) FROM archive_event WHERE job_id=? AND event_type='PUBLICATION_COMMITTED'",
                    autoJobId) == 1, "AUTO replay duplicated publication event");

            JsonNode autoPublication = one(jdbc, autoJobId);
            JsonNode manualReadback = readback(text(manualPublication, "workId"),
                    text(manualPublication, "editionId"));
            JsonNode autoReadback = readback(text(autoPublication, "workId"),
                    text(autoPublication, "editionId"));

            writeJson(finalResultFile, Map.of(
                    "status", "passed", "sourceSha256", sha256(SOURCE),
                    "packageSha256", FixtureArchiveExecutionPort.PACKAGE_SHA256,
                    "manualPublicationId", text(manualPublication, "publicationId"),
                    "autoPublicationId", text(autoPublication, "publicationId"),
                    "manualReaderChapter", manualReadback.path("chapterTitle").asText(),
                    "autoReaderChapter", autoReadback.path("chapterTitle").asText(),
                    "autoPublicationCount", 1, "autoPublicationEventCount", 1));
        } catch (Throwable failure) {
            writeJson(finalResultFile, Map.of("status", "failed",
                    "errorType", failure.getClass().getSimpleName()));
            throw failure;
        } finally {
            try { server.stop(); } finally { context.close(); }
        }
    }

    private void initializeDisposableSchema(JdbcTemplate jdbc,
            AnnotationConfigWebApplicationContext context) {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET FOREIGN_KEY_CHECKS=0");
                try {
                    for (String table : new String[]{"archive_admin_operation_receipt", "archive_operation", "archive_event", "archive_publication",
                            "archive_validation", "archive_draft", "archive_execution_grant", "archive_job_run",
                            "archive_maintenance_job", "archive_confirmed_request", "archive_source_snapshot",
                            "archive_appointment", "archive_appointment_slot", "archive_collection_work",
                            "archive_collection_manager", "archive_collection", "archive_paragraph",
                            "archive_chapter", "archive_edition", "archive_work"}) {
                        statement.execute("DROP TABLE IF EXISTS " + table);
                    }
                } finally { statement.execute("SET FOREIGN_KEY_CHECKS=1"); }
            }
            return null;
        });
        new ArchiveSchemaInitializer(jdbc).initialize();
        ArchiveMaintenanceProperties properties = context.getBean(ArchiveMaintenanceProperties.class);
        new ArchiveMaintenanceSchemaInitializer(jdbc,
                context.getBean(ArchiveMaintenanceStore.class), properties,
                context.getBean(ArchiveTransactions.class)).initialize();
        jdbc.update("INSERT INTO archive_collection_manager(collection_id,tenant_id,client_id,owner_jiacn,"
                        + "permissions,state,revision) VALUES (?,?,?,?,?,'ACTIVE',3)", COLLECTION,
                ArchiveCrossComponentFixtureConfiguration.TENANT,
                ArchiveCrossComponentFixtureConfiguration.CLIENT,
                ArchiveCrossComponentFixtureConfiguration.OWNER,
                "appoint,source.prepare,job.create,job.manage,draft.write,validate,publish");
    }

    private void bindRuntime(AgentRuntimeAuthenticationService authentication) {
        String token = required("CYF_FIXTURE_RUNTIME_TOKEN");
        authentication.bind(ArchiveCrossComponentFixtureConfiguration.SESSION_ID,
                ArchiveCrossComponentFixtureConfiguration.CLIENT,
                ArchiveCrossComponentFixtureConfiguration.OWNER,
                ArchiveCrossComponentFixtureConfiguration.AGENT,
                ArchiveCrossComponentFixtureConfiguration.RUNTIME,
                ArchiveCrossComponentFixtureConfiguration.API_KEY_ID, token, () -> true);
        authentication.registerCommandProtocols(ArchiveCrossComponentFixtureConfiguration.SESSION_ID,
                ArchiveCrossComponentFixtureConfiguration.AGENT,
                java.util.List.of(FixtureArchiveExecutionPort.REQUIRED_PROTOCOL));
    }

    private JsonNode createJob(String sourceId, String suffix, String mode) throws Exception {
        return postAdmin("/archive/admin/v1/collections/" + COLLECTION + "/jobs",
                "fixture-job-" + suffix, null, nullableMap(
                        "operation", "ADD_WORK",
                        "newWork", Map.of("canonicalKey", "fixture-" + suffix,
                                "title", "Fixture " + suffix, "language", "zh-CN"),
                        "workId", null, "sourceId", sourceId,
                        "publicationMode", mode, "requestIntentId", "fixture-intent-" + suffix));
    }

    private JsonNode execute(String jobId, String revision, String suffix) throws Exception {
        return request("POST", "/archive/admin/v1/jobs/" + jobId + "/execute", null,
                Map.of("Idempotency-Key", "fixture-execute-" + suffix,
                        "If-Match", "\"v" + revision + "\""), true);
    }

    private void assertWrongBearerRejected(String path, Map<String, ?> body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(origin + path))
                .timeout(HTTP_TIMEOUT)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer fixture-wrong-token")
                .header("Idempotency-Key", "fixture-wrong-bearer")
                .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body)))
                .build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        require(response.statusCode() == 401, "Wrong fixture bearer was not rejected with 401");
        JsonNode rejection = mapper.readTree(response.body());
        require("AUTH_CONTEXT_INCOMPLETE".equals(rejection.path("code").asText()),
                "Wrong fixture bearer returned an unexpected error");
    }

    private JsonNode postAdmin(String path, String key, String ifMatch, Map<String, ?> body)
            throws Exception {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Idempotency-Key", key);
        if (ifMatch != null) headers.put("If-Match", ifMatch);
        return request("POST", path, mapper.writeValueAsBytes(body), headers, true);
    }

    private JsonNode request(String method, String path, byte[] body, Map<String, String> headers,
            boolean admin) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(origin + path))
                .timeout(HTTP_TIMEOUT).header("Accept", "application/json")
                .header("Authorization", admin ? adminAuthorization : adminAuthorization);
        headers.forEach(builder::header);
        if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
        else builder.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofByteArray(body));
        HttpResponse<byte[]> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Fixture HTTP " + method + " " + path
                    + " failed with status " + response.statusCode());
        }
        JsonNode envelope = mapper.readTree(response.body());
        if (!"E0".equals(envelope.path("code").asText()) || envelope.path("status").asInt() != 200) {
            throw new IllegalStateException("Fixture HTTP response envelope was not successful");
        }
        return envelope.path("data");
    }

    private JsonNode readback(String workId, String editionId) throws Exception {
        JsonNode catalog = request("GET", "/archive/v1/works/" + workId + "/catalog", null,
                Map.of(), true);
        JsonNode chapters = catalog.path("activeEdition").path("chapters");
        require(chapters.isArray() && chapters.size() == 1, "Reader catalog did not expose one chapter");
        String blockId = chapters.get(0).path("blockId").asText();
        JsonNode chapter = request("GET", "/archive/v1/editions/" + editionId
                + "/chapters/" + blockId, null, Map.of(), true);
        require(chapter.path("paragraphs").isArray() && chapter.path("paragraphs").size() == 1,
                "Reader chapter did not expose the fixture paragraph");
        require("夹具正文。".equals(chapter.path("paragraphs").get(0).path("text").asText()),
                "Reader text did not match the exact fixture source");
        return mapper.valueToTree(Map.of("chapterTitle", chapter.path("title").asText()));
    }

    private JsonNode one(JdbcTemplate jdbc, String jobId) throws Exception {
        Map<String, Object> row = jdbc.queryForMap("SELECT publication_id,work_id,edition_id FROM archive_publication WHERE job_id=?",
                jobId);
        return mapper.valueToTree(Map.of("publicationId", row.get("publication_id"),
                "workId", row.get("work_id"), "editionId", row.get("edition_id")));
    }

    private JsonNode waitForClientResult(Path file) throws Exception {
        long waitMillis = Long.parseLong(System.getenv().getOrDefault("CYF_FIXTURE_WAIT_MILLIS", "600000"));
        if (waitMillis < 1_000 || waitMillis > 1_800_000) {
            throw new IllegalStateException("CYF_FIXTURE_WAIT_MILLIS must be between 1000 and 1800000");
        }
        long deadline = System.nanoTime() + waitMillis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(file)) return mapper.readTree(Files.readAllBytes(file));
            Thread.sleep(100L);
        }
        throw new IllegalStateException("Timed out waiting for the bounded Client fixture result");
    }

    private static int count(JdbcTemplate jdbc, String sql, String jobId) {
        return jdbc.queryForObject(sql, Integer.class, jobId);
    }

    private void writeJson(Path target, Object value) throws Exception {
        Path parent = target.toAbsolutePath().normalize().getParent();
        if (parent == null) throw new IllegalStateException("Fixture output must have a parent directory");
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".fixture-", ".tmp");
        Files.write(temporary, mapper.writeValueAsBytes(value));
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Map<String, Object> nullableMap(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put((String) values[index], values[index + 1]);
        }
        return result;
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (value.isBlank()) throw new IllegalStateException("Missing fixture response field " + field);
        return value;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static Path path(String name) { return Path.of(required(name)).toAbsolutePath().normalize(); }
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalStateException(name + " is required for the disposable fixture");
        }
        return value;
    }
    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }
    private static void requireEnvironment() {
        for (String name : new String[]{"CYF_H02_MYSQL_ISOLATED", "CYF_H02_MYSQL_URL",
                "CYF_H02_MYSQL_DATABASE_CONFIRM", "CYF_FIXTURE_HOST", "CYF_FIXTURE_ARTIFACT_ROOT",
                "CYF_FIXTURE_READY_FILE", "CYF_FIXTURE_COMMAND_FILE", "CYF_FIXTURE_CLIENT_RESULT_FILE",
                "CYF_FIXTURE_FINAL_RESULT_FILE", "CYF_FIXTURE_RUNTIME_TOKEN", "CYF_FIXTURE_ADMIN_TOKEN"}) {
            required(name);
        }
        if (!"true".equals(required("CYF_H02_MYSQL_ISOLATED"))) {
            throw new IllegalStateException("CYF_H02_MYSQL_ISOLATED must be literal true");
        }
        if (!required("CYF_FIXTURE_RUNTIME_TOKEN").matches("[0-9a-f]{32}")) {
            throw new IllegalStateException("CYF_FIXTURE_RUNTIME_TOKEN must be a native runtime token");
        }
    }
}
