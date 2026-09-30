package cn.jia.agent.api;

import cn.jia.agent.entity.AgentTaskDTO;
import cn.jia.agent.service.AgentTaskCreationOperationService;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentTaskCreationOperationControllerTest {
    private AgentTaskCreationOperationService operations;
    private MockMvc mvc;
    private ObjectMapper json;

    @BeforeEach
    void setUp() {
        operations = mock(AgentTaskCreationOperationService.class);
        json = JsonMapper.builder().build();
        mvc = MockMvcBuilders.standaloneSetup(
                new AgentTaskCreationOperationController(operations)).build();
        context("owner-a", "client-a");
    }

    @AfterEach
    void tearDown() {
        EsContextHolder.clearContext();
    }

    @Test
    void initial201Replay200AndGetExposeOnlyFrozenReceiptFieldsWithNoStore() throws Exception {
        AgentTaskCreationOperationService.Receipt receipt = receipt();
        when(operations.create(any(), eq("creation-key"), any())).thenReturn(
                new AgentTaskCreationOperationService.Result(receipt, false),
                new AgentTaskCreationOperationService.Result(receipt, true));
        when(operations.getByIdempotencyKey(any(), eq("creation-key"))).thenReturn(receipt);
        String body = """
                {"title":"完整🚀标题","description":null,"requiredAbilities":[],
                 "reward":null,"inputRefs":[
                   {"fileId":"file-b","version":2,"purpose":"REFERENCE"},
                   {"fileId":"file-a","version":1,"purpose":"REFERENCE"}]}
                """;

        MvcResult initial = mvc.perform(post("/agent/tasks/creation-operations")
                        .principal(jwt("owner-a", "client-a"))
                        .header("Idempotency-Key", "creation-key")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(jsonPath("$.schemaVersion").value(1))
                .andExpect(jsonPath("$.operationId").value("atco_1"))
                .andExpect(jsonPath("$.taskId").value("42"))
                .andExpect(jsonPath("$.requirementRevision").value(1))
                .andExpect(jsonPath("$.state").value("COMMITTED"))
                .andExpect(jsonPath("$.task.id").value("42"))
                .andReturn();
        assertReceiptFields(initial);

        MvcResult replay = mvc.perform(post("/agent/tasks/creation-operations")
                        .principal(jwt("owner-a", "client-a"))
                        .header("Idempotency-Key", "creation-key")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andReturn();
        assertReceiptFields(replay);

        MvcResult read = mvc.perform(get("/agent/tasks/creation-operations/request")
                        .principal(jwt("owner-a", "client-a"))
                        .header("Idempotency-Key", "creation-key"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andReturn();
        assertEquals(json.readTree(replay.getResponse().getContentAsString()),
                json.readTree(read.getResponse().getContentAsString()));

        var command = org.mockito.ArgumentCaptor.forClass(
                AgentTaskCreationOperationService.CreateCommand.class);
        verify(operations, org.mockito.Mockito.times(2)).create(
                eq(new AgentTaskCreationOperationService.Scope("0", "client-a", "owner-a")),
                eq("creation-key"), command.capture());
        assertEquals(null, command.getAllValues().getFirst().description());
        assertEquals(List.of(), command.getAllValues().getFirst().requiredAbilities());
        assertEquals(null, command.getAllValues().getFirst().reward());
        assertEquals("REFERENCE", command.getAllValues().getFirst().inputRefs().getFirst().purpose());
    }

    @Test
    void rawAllowlistDuplicateHeadersTrailingTokensAndReferenceShapeFailBeforeService() throws Exception {
        for (String body : List.of(
                "{\"title\":\"x\",\"title\":\"y\",\"inputRefs\":[]}",
                "{\"title\":\"x\",\"funding\":1,\"inputRefs\":[]}",
                "{\"title\":\"x\",\"inputRefs\":[{\"fileId\":\"f\",\"version\":1,\"purpose\":\"INPUT\"}]}",
                "{\"title\":\"x\",\"inputRefs\":[]} {}")) {
            mvc.perform(post("/agent/tasks/creation-operations")
                            .principal(jwt("owner-a", "client-a"))
                            .header("Idempotency-Key", "bad-key")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                    .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        }
        mvc.perform(post("/agent/tasks/creation-operations")
                        .principal(jwt("owner-a", "client-a"))
                        .header("Idempotency-Key", "one")
                        .header("Idempotency-Key", "two")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\",\"inputRefs\":[]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/agent/tasks/creation-operations/request?owner=victim")
                        .principal(jwt("owner-a", "client-a"))
                        .header("Idempotency-Key", "key"))
                .andExpect(status().isBadRequest());
        verify(operations, never()).create(any(), any(), any());
    }

    @Test
    void unauthenticatedAndJwtContextDriftAreDistinctAndNoStore() throws Exception {
        mvc.perform(get("/agent/tasks/creation-operations/request")
                        .header("Idempotency-Key", "key"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        mvc.perform(get("/agent/tasks/creation-operations/request")
                        .principal(jwt("owner-b", "client-a"))
                        .header("Idempotency-Key", "key"))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(jsonPath("$.code").value("TASK_CREATION_FORBIDDEN"));
        verify(operations, never()).getByIdempotencyKey(any(), any());
    }

    @Test
    void unknownAndStorageErrorsAreGenericNoStoreAndDoNotExposePrivateInputs() throws Exception {
        when(operations.getByIdempotencyKey(any(), eq("missing"))).thenThrow(
                new AgentTaskCreationOperationService.Failure(
                        AgentTaskCreationOperationService.Reason.NOT_FOUND));
        mvc.perform(get("/agent/tasks/creation-operations/request")
                        .principal(jwt("owner-a", "client-a"))
                        .header("Idempotency-Key", "missing"))
                .andExpect(status().isNotFound())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(jsonPath("$.code").value("TASK_CREATION_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Task creation operation is unavailable"));
    }

    private void assertReceiptFields(MvcResult result) throws Exception {
        JsonNode root = json.readTree(result.getResponse().getContentAsString());
        Set<String> fields = new java.util.HashSet<>();
        root.propertyNames().forEach(fields::add);
        assertEquals(Set.of("schemaVersion", "operationId", "taskId", "requirementRevision",
                "state", "inputRefs", "task"), fields);
        assertEquals(root.get("taskId").textValue(), root.get("task").get("id").textValue());
    }

    private static AgentTaskCreationOperationService.Receipt receipt() {
        AgentTaskDTO task = new AgentTaskDTO();
        task.setId("42");
        task.setTenantId("0");
        task.setClientId("client-a");
        task.setTitle("完整🚀标题");
        return new AgentTaskCreationOperationService.Receipt(1, "atco_1", "42", 1,
                "COMMITTED", List.of(new AgentTaskCreationOperationService.InputReference(
                        "file-a", 1, "REFERENCE")), task);
    }

    private static JwtAuthenticationToken jwt(String owner, String client) {
        Jwt token = Jwt.withTokenValue("fixture").header("alg", "none")
                .claim("tenant_id", "0").claim("jiacn", owner).claim("client_id", client)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).build();
        JwtAuthenticationToken authentication = new JwtAuthenticationToken(token, List.of());
        authentication.setAuthenticated(true);
        return authentication;
    }

    private static void context(String owner, String client) {
        EsContext context = new EsContext();
        context.setTenantId("0");
        context.setClientId(client);
        context.setJiacn(owner);
        EsContextHolder.setContext(context);
    }
}
