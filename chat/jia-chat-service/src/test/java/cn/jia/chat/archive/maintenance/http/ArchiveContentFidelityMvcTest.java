package cn.jia.chat.archive.maintenance.http;

import cn.jia.agent.security.AgentRuntimeAuthentication;
import cn.jia.chat.archive.config.ArchiveReaderAccessPolicy;
import cn.jia.chat.archive.config.ArchiveReaderProperties;
import cn.jia.chat.archive.dto.ArchiveActiveEditionDTO;
import cn.jia.chat.archive.dto.ArchiveBlockDTO;
import cn.jia.chat.archive.dto.ArchiveBlockSummaryDTO;
import cn.jia.chat.archive.dto.ArchiveCatalogDTO;
import cn.jia.chat.archive.dto.ArchiveParagraphDTO;
import cn.jia.chat.archive.dto.ArchivePageDTO;
import cn.jia.chat.archive.http.ArchiveController;
import cn.jia.chat.archive.maintenance.dto.ArchiveDraftBlockDTO;
import cn.jia.chat.archive.maintenance.dto.ArchiveDraftBlockInput;
import cn.jia.chat.archive.maintenance.dto.ArchiveDraftDTO;
import cn.jia.chat.archive.maintenance.dto.ArchiveDraftParagraphInput;
import cn.jia.chat.archive.maintenance.dto.ArchiveDraftUpdateRequest;
import cn.jia.chat.archive.maintenance.dto.ArchiveRuntimeContextDTO;
import cn.jia.chat.archive.maintenance.dto.ArchiveSkillRef;
import cn.jia.chat.archive.maintenance.dto.ArchiveSourceExclusionInput;
import cn.jia.chat.archive.maintenance.dto.ArchiveSourceRangeInput;
import cn.jia.chat.archive.maintenance.dto.ArchiveSourceSnapshotDTO;
import cn.jia.chat.archive.maintenance.dto.ArchiveWorkSummaryDTO;
import cn.jia.chat.archive.maintenance.dto.ArchiveWorksDTO;
import cn.jia.chat.archive.maintenance.service.ArchiveMaintenanceService;
import cn.jia.chat.archive.maintenance.service.ArchiveMaintenanceException;
import cn.jia.chat.archive.service.ArchiveReaderService;
import cn.jia.chat.archive.service.ArchiveRepresentation;
import cn.jia.core.security.SensitiveResponseBodyAdvice;
import cn.jia.core.security.SensitiveResponseProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ArchiveContentFidelityMvcTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String MANUSCRIPT_TEXT =
            "正文 token=fixture_literal；password=author_literal，均为底本原文。";
    private static final String TITLE = "题名 token=fixture_literal";
    private static final String EXCLUSION_REASON = "编校说明 secret=fixture_literal";

    @Test
    void nativeDraftAndSourceMetadataRemainExactThroughDefaultAdviceAndJackson3() throws Exception {
        ArchiveMaintenanceService service = mock(ArchiveMaintenanceService.class);
        ArchiveDraftDTO draft = draft();
        when(service.runtimeDraft(any(), eq("job-a"), eq("run-a"))).thenReturn(draft);
        when(service.runtimeContext(any(), eq("job-a"), eq("run-a"))).thenReturn(
                new ArchiveRuntimeContextDTO("job-a", "run-a", "platform-classics", "work-a",
                        "ADD_WORK", "4", null, "appointment-a", "2", "agent-a", "3",
                        "DRAFT_ONLY", "MANUAL", "RUNNING", null,
                        new ArchiveSkillRef("archive-maintainer", "1", "a".repeat(64)),
                        "source-a", "b".repeat(64), "来源 token=fixture_literal / v1",
                        "授权说明 secret=fixture_literal", "draft-a", "8"));

        AgentRuntimeAuthentication authentication = runtimeAuthentication();
        MockMvc mvc = mvc(new ArchiveNativeController(service, new ObjectMapper()));

        var draftResponse = mvc.perform(runtimeGet(
                        "/internal/archive/v1/jobs/job-a/runs/run-a/draft", authentication))
                .andExpect(status().isOk()).andReturn().getResponse();
        JsonNode draftJson = JSON.readTree(draftResponse.getContentAsByteArray());

        assertEquals("\"v8\"", draftResponse.getHeader(HttpHeaders.ETAG));
        assertEquals(Set.of("code", "data", "msg", "status"), names(draftJson));
        assertEquals(TITLE, draftJson.at("/data/content/blocks/0/title").asText());
        assertEquals(MANUSCRIPT_TEXT,
                draftJson.at("/data/content/blocks/0/paragraphs/0/text").asText());
        assertEquals(23,
                draftJson.at("/data/content/blocks/0/paragraphs/0/sourceRanges/0/startByte").asInt());
        assertEquals(71,
                draftJson.at("/data/content/blocks/0/paragraphs/0/sourceRanges/0/endByte").asInt());
        assertEquals(EXCLUSION_REASON,
                draftJson.at("/data/content/excludedSourceRanges/0/reason").asText());
        assertFalse(draftJson.at("/data/content/blocks/0/paragraphs/0/sourceRanges/0").isNull());

        var contextResponse = mvc.perform(runtimeGet(
                        "/internal/archive/v1/jobs/job-a/runs/run-a/context", authentication))
                .andExpect(status().isOk()).andReturn().getResponse();
        JsonNode contextJson = JSON.readTree(contextResponse.getContentAsByteArray());
        assertEquals("来源 token=fixture_literal / v1", contextJson.at("/data/sourceSummary").asText());
        assertEquals("授权说明 secret=fixture_literal", contextJson.at("/data/rightsBasis").asText());
    }

    @Test
    void adminDraftBlockWorkTitleAndSourceMetadataRemainExactThroughDefaultAdviceAndJackson3()
            throws Exception {
        ArchiveMaintenanceService service = mock(ArchiveMaintenanceService.class);
        ArchiveDraftDTO draft = draft();
        ArchiveDraftBlockInput block = draft.content().blocks().getFirst();
        when(service.getDraft(any(), eq("job-a"))).thenReturn(draft);
        when(service.getDraftBlock(any(), eq("draft-a"), eq("chapter-1"))).thenReturn(
                new ArchiveDraftBlockDTO("draft-a", "job-a", "8", "EDITABLE", block));
        when(service.listWorks(any(), eq("platform-classics"), isNull(), eq(100))).thenReturn(
                new ArchiveWorksDTO(List.of(new ArchiveWorkSummaryDTO("work-a", TITLE, "edition-a")), null));
        when(service.source(any(), eq("source-a"))).thenReturn(new ArchiveSourceSnapshotDTO(
                "source-a", "platform-classics", "来源 token=fixture_literal", "版本 secret=fixture_literal",
                "授权说明 password=fixture_literal", "b".repeat(64), "71", "UTF8_EXACT_V1", "READY"));

        MockMvc mvc = mvc(new ArchiveAdminController(service, new ObjectMapper()));
        JwtAuthenticationToken authentication = managerAuthentication();

        var draftResponse = mvc.perform(get("/archive/admin/v1/jobs/job-a/draft")
                        .principal(authentication))
                .andExpect(status().isOk()).andReturn().getResponse();
        JsonNode draftJson = JSON.readTree(draftResponse.getContentAsByteArray());
        assertEquals("\"v8\"", draftResponse.getHeader(HttpHeaders.ETAG));
        assertEquals(MANUSCRIPT_TEXT,
                draftJson.at("/data/content/blocks/0/paragraphs/0/text").asText());
        assertEquals(23,
                draftJson.at("/data/content/blocks/0/paragraphs/0/sourceRanges/0/startByte").asInt());
        assertEquals(EXCLUSION_REASON,
                draftJson.at("/data/content/excludedSourceRanges/0/reason").asText());

        var blockResponse = mvc.perform(get("/archive/admin/v1/drafts/draft-a/blocks/chapter-1")
                        .principal(authentication))
                .andExpect(status().isOk()).andReturn().getResponse();
        JsonNode blockJson = JSON.readTree(blockResponse.getContentAsByteArray());
        assertEquals("\"v8\"", blockResponse.getHeader(HttpHeaders.ETAG));
        assertEquals(TITLE, blockJson.at("/data/block/title").asText());
        assertEquals(MANUSCRIPT_TEXT, blockJson.at("/data/block/paragraphs/0/text").asText());
        assertEquals(71, blockJson.at("/data/block/paragraphs/0/sourceRanges/0/endByte").asInt());

        JsonNode worksJson = responseJson(mvc, get("/archive/admin/v1/collections/platform-classics/works")
                .principal(authentication));
        assertEquals(TITLE, worksJson.at("/data/items/0/title").asText());

        JsonNode sourceJson = responseJson(mvc, get("/archive/admin/v1/source-snapshots/source-a")
                .principal(authentication));
        assertEquals("来源 token=fixture_literal", sourceJson.at("/data/sourceName").asText());
        assertEquals("版本 secret=fixture_literal", sourceJson.at("/data/sourceVersion").asText());
        assertEquals("授权说明 password=fixture_literal", sourceJson.at("/data/rightsBasis").asText());
    }

    @Test
    void readerCatalogPrefaceChapterAndHashesRemainExactThroughDefaultAdviceAndJackson3()
            throws Exception {
        ArchiveReaderService service = mock(ArchiveReaderService.class);
        String manifestSha = "c".repeat(64);
        String sourceSha = "d".repeat(64);
        String prefaceSha = "e".repeat(64);
        String chapterSha = "f".repeat(64);
        String prefaceEtag = "\"preface-content-fidelity\"";
        String chapterEtag = "\"chapter-content-fidelity\"";
        String catalogEtag = "W/\"catalog-content-fidelity\"";
        String worksEtag = "W/\"works-content-fidelity\"";

        ArchiveBlockSummaryDTO prefaceSummary = new ArchiveBlockSummaryDTO(
                "PREFACE", "preface", null, TITLE, 1, 71, prefaceEtag);
        ArchiveBlockSummaryDTO chapterSummary = new ArchiveBlockSummaryDTO(
                "CHAPTER", "chapter-1", 1, TITLE, 1, 71, chapterEtag);
        ArchiveActiveEditionDTO active = new ArchiveActiveEditionDTO(
                "edition-a", manifestSha, sourceSha, 1, 1, 2, 142,
                prefaceSummary, List.of(chapterSummary));
        when(service.catalog()).thenReturn(new ArchiveRepresentation<>(catalogEtag,
                new ArchiveCatalogDTO(1, "work-a", TITLE, active)));
        when(service.works(eq("tenant-a"), eq("client-a"), isNull(), eq(100))).thenReturn(new ArchiveRepresentation<>(worksEtag,
                new ArchiveWorksDTO(List.of(new ArchiveWorkSummaryDTO("work-a", TITLE, "edition-a")), null)));
        when(service.preface("edition-a")).thenReturn(new ArchiveRepresentation<>(prefaceEtag,
                block("PREFACE", "preface", null, manifestSha, prefaceSha)));
        when(service.chapter("edition-a", "chapter-1")).thenReturn(new ArchiveRepresentation<>(chapterEtag,
                block("CHAPTER", "chapter-1", 1, manifestSha, chapterSha)));

        MockMvc mvc = mvc(new ArchiveController(service, enabledReaderPolicy()));
        JwtAuthenticationToken authentication = readerAuthentication();

        var catalogResponse = mvc.perform(get("/archive/v1/catalog").principal(authentication))
                .andExpect(status().isOk()).andReturn().getResponse();
        JsonNode catalogJson = JSON.readTree(catalogResponse.getContentAsByteArray());
        assertEquals(catalogEtag, catalogResponse.getHeader(HttpHeaders.ETAG));
        assertEquals(TITLE, catalogJson.at("/data/title").asText());
        assertEquals(TITLE, catalogJson.at("/data/activeEdition/preface/title").asText());
        assertEquals(TITLE, catalogJson.at("/data/activeEdition/chapters/0/title").asText());
        assertEquals(manifestSha, catalogJson.at("/data/activeEdition/manifestSha256").asText());
        assertEquals(sourceSha, catalogJson.at("/data/activeEdition/sourceSha256").asText());
        assertEquals(prefaceEtag, catalogJson.at("/data/activeEdition/preface/etag").asText());
        assertEquals(chapterEtag, catalogJson.at("/data/activeEdition/chapters/0/etag").asText());

        var worksResponse = mvc.perform(get("/archive/v1/works").principal(authentication))
                .andExpect(status().isOk()).andReturn().getResponse();
        JsonNode worksJson = JSON.readTree(worksResponse.getContentAsByteArray());
        assertEquals(worksEtag, worksResponse.getHeader(HttpHeaders.ETAG));
        assertEquals(TITLE, worksJson.at("/data/items/0/title").asText());

        assertReaderBlock(mvc, authentication, "/archive/v1/editions/edition-a/preface",
                prefaceEtag, manifestSha, prefaceSha);
        assertReaderBlock(mvc, authentication,
                "/archive/v1/editions/edition-a/chapters/chapter-1",
                chapterEtag, manifestSha, chapterSha);
    }

    @Test
    void pageQueryContractsPassExactScopeFilterCursorAndRejectMalformedLimits() throws Exception {
        ArchiveMaintenanceService admin = mock(ArchiveMaintenanceService.class);
        when(admin.appointments(any(), eq("platform-classics"), eq("cursor-a"), eq(7)))
                .thenReturn(new ArchivePageDTO<>(List.of(), null));
        when(admin.listJobs(any(), eq("platform-classics"), eq("FAILED"), eq("cursor-j"), eq(8)))
                .thenReturn(new ArchivePageDTO<>(List.of(), null));
        when(admin.listWorks(any(), eq("platform-classics"), eq("cursor-w"), eq(9)))
                .thenReturn(new ArchiveWorksDTO(List.of(), null));
        MockMvc adminMvc = mvc(new ArchiveAdminController(admin, new ObjectMapper()));
        JwtAuthenticationToken manager = managerAuthentication();

        responseJson(adminMvc, get("/archive/admin/v1/collections/platform-classics/appointments")
                .param("cursor", "cursor-a").param("limit", "7").principal(manager));
        responseJson(adminMvc, get("/archive/admin/v1/collections/platform-classics/jobs")
                .param("state", "FAILED").param("cursor", "cursor-j").param("limit", "8")
                .principal(manager));
        responseJson(adminMvc, get("/archive/admin/v1/collections/platform-classics/works")
                .param("cursor", "cursor-w").param("limit", "9").principal(manager));
        JsonNode invalidAdmin = responseJson(adminMvc,
                get("/archive/admin/v1/collections/platform-classics/jobs")
                        .param("limit", "01").principal(manager), 400);
        assertEquals("INVALID_ARCHIVE_PAGE_LIMIT", invalidAdmin.path("code").asText());
        when(admin.listJobs(any(), eq("platform-classics"), isNull(), eq("bad-admin"), eq(50)))
                .thenThrow(new ArchiveMaintenanceException(400, "INVALID_ARCHIVE_PAGE_CURSOR",
                        "Archive page cursor is invalid for this list scope"));
        JsonNode invalidAdminCursor = responseJson(adminMvc,
                get("/archive/admin/v1/collections/platform-classics/jobs")
                        .param("cursor", "bad-admin").principal(manager), 400);
        assertEquals("INVALID_ARCHIVE_PAGE_CURSOR", invalidAdminCursor.path("code").asText());

        ArchiveReaderService reader = mock(ArchiveReaderService.class);
        when(reader.works("tenant-a", "client-a", "cursor-r", 6)).thenReturn(
                new ArchiveRepresentation<>("W/\"page\"", new ArchiveWorksDTO(List.of(), null)));
        MockMvc readerMvc = mvc(new ArchiveController(reader, enabledReaderPolicy()));
        responseJson(readerMvc, get("/archive/v1/works").param("cursor", "cursor-r")
                .param("limit", "6").principal(readerAuthentication()));
        JsonNode invalidReader = responseJson(readerMvc, get("/archive/v1/works")
                .param("limit", "0").principal(readerAuthentication()), 400);
        assertEquals("INVALID_ARCHIVE_PAGE_LIMIT", invalidReader.path("code").asText());
        when(reader.works("tenant-a", "client-a", "bad-reader", 100))
                .thenThrow(new IllegalArgumentException("Invalid archive page cursor"));
        JsonNode invalidReaderCursor = responseJson(readerMvc, get("/archive/v1/works")
                .param("cursor", "bad-reader").principal(readerAuthentication()), 400);
        assertEquals("INVALID_ARCHIVE_PAGE_CURSOR", invalidReaderCursor.path("code").asText());
    }

    private static ArchiveDraftDTO draft() {
        ArchiveDraftParagraphInput paragraph = new ArchiveDraftParagraphInput(
                1, MANUSCRIPT_TEXT, List.of(new ArchiveSourceRangeInput(23, 71)));
        ArchiveDraftBlockInput block = new ArchiveDraftBlockInput(
                "CHAPTER", "chapter-1", 1, TITLE,
                List.of(new ArchiveSourceRangeInput(0, 22)), List.of(paragraph));
        ArchiveDraftUpdateRequest content = new ArchiveDraftUpdateRequest(
                List.of(block), List.of(new ArchiveSourceExclusionInput(72, 80, EXCLUSION_REASON)));
        return new ArchiveDraftDTO("draft-a", "job-a", "8", "EDITABLE", content,
                "a".repeat(64), null, null);
    }

    private static ArchiveBlockDTO block(String type, String id, Integer number,
            String manifestSha, String paragraphSha) {
        return new ArchiveBlockDTO(1, "edition-a", manifestSha, type, id, number, TITLE,
                1, 71, List.of(new ArchiveParagraphDTO(
                        id + "-p1", 1, MANUSCRIPT_TEXT, 71, paragraphSha)));
    }

    private static void assertReaderBlock(MockMvc mvc, JwtAuthenticationToken authentication,
            String path, String expectedEtag, String manifestSha, String paragraphSha) throws Exception {
        var response = mvc.perform(get(path).principal(authentication))
                .andExpect(status().isOk()).andReturn().getResponse();
        JsonNode json = JSON.readTree(response.getContentAsByteArray());
        assertEquals(expectedEtag, response.getHeader(HttpHeaders.ETAG));
        assertEquals(TITLE, json.at("/data/title").asText());
        assertEquals(MANUSCRIPT_TEXT, json.at("/data/paragraphs/0/text").asText());
        assertEquals(manifestSha, json.at("/data/manifestSha256").asText());
        assertEquals(paragraphSha, json.at("/data/paragraphs/0/sha256").asText());
        assertTrue(json.at("/data/paragraphs/0/utf8ByteLength").asLong() > 0);
    }

    private static JsonNode responseJson(MockMvc mvc, MockHttpServletRequestBuilder request) throws Exception {
        return responseJson(mvc, request, 200);
    }

    private static JsonNode responseJson(MockMvc mvc, MockHttpServletRequestBuilder request,
            int expectedStatus) throws Exception {
        return JSON.readTree(mvc.perform(request).andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsByteArray());
    }

    private static MockMvc mvc(Object... controllers) {
        return org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controllers)
                .setControllerAdvice(new SensitiveResponseBodyAdvice(new SensitiveResponseProperties()),
                        new ArchiveMaintenanceExceptionHandler())
                .setMessageConverters(
                        new ByteArrayHttpMessageConverter(),
                        new JacksonJsonHttpMessageConverter(
                                tools.jackson.databind.json.JsonMapper.builder().build()))
                .build();
    }

    private static MockHttpServletRequestBuilder runtimeGet(
            String path, AgentRuntimeAuthentication authentication) {
        return get(path).principal(authentication)
                .header("X-Archive-Grant-Ref", "grant-a")
                .header("X-Archive-Execution-Ref", "execution-a")
                .header("X-Archive-Command-Id", "command-a")
                .header("X-Archive-Command-Attempt", "2")
                .header("X-Archive-Execution-Epoch", "3");
    }

    private static AgentRuntimeAuthentication runtimeAuthentication() {
        AgentRuntimeAuthentication authentication = mock(AgentRuntimeAuthentication.class);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getPrincipal()).thenReturn(new AgentRuntimeAuthentication.Scope(
                "0", "client-a", "owner-a", "agent-a", "runtime-a"));
        return authentication;
    }

    private static JwtAuthenticationToken managerAuthentication() {
        Jwt jwt = Jwt.withTokenValue("manager-token").header("alg", "none")
                .claim("jiacn", "owner-a").claim("client_id", "client-a")
                .issuedAt(Instant.EPOCH).expiresAt(Instant.EPOCH.plusSeconds(60)).build();
        return new JwtAuthenticationToken(jwt,
                List.of(new SimpleGrantedAuthority("ROLE_ARCHIVE_MANAGER")));
    }

    private static JwtAuthenticationToken readerAuthentication() {
        Jwt jwt = Jwt.withTokenValue("reader-token").header("alg", "none")
                .claim("jiacn", "tenant-a").claim("client_id", "client-a")
                .issuedAt(Instant.EPOCH).expiresAt(Instant.EPOCH.plusSeconds(60)).build();
        return new JwtAuthenticationToken(jwt);
    }

    private static ArchiveReaderAccessPolicy enabledReaderPolicy() {
        ArchiveReaderProperties properties = new ArchiveReaderProperties();
        properties.setEnabled(true);
        ArchiveReaderProperties.AllowedScope scope = new ArchiveReaderProperties.AllowedScope();
        scope.setTenantId("tenant-a");
        scope.setClientId("client-a");
        properties.setAllowedScopes(new java.util.ArrayList<>(List.of(scope)));
        return ArchiveReaderAccessPolicy.from(properties);
    }

    private static Set<String> names(JsonNode node) {
        java.util.HashSet<String> names = new java.util.HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
