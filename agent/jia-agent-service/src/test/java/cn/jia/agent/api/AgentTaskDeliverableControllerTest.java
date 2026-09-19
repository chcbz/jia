package cn.jia.agent.api;

import cn.jia.agent.entity.AgentTaskArtifactContentDTO;
import cn.jia.agent.entity.AgentTaskArtifactQueryDTO;
import cn.jia.agent.entity.AgentTaskArtifactViewDTO;
import cn.jia.agent.exception.AgentTaskCollaborationException;
import cn.jia.agent.exception.AgentTaskCollaborationException.Reason;
import cn.jia.agent.service.AgentTaskArtifactContentService;
import cn.jia.agent.service.AgentTaskArtifactService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentTaskDeliverableControllerTest {
    private static final String TENANT = "tenant-a";
    private static final String CLIENT = "client-a";
    private static final String TASK = "task-1";
    private static final String ACTOR = "agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String OTHER = "agt_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String ARTIFACT = "artifact-1";
    private static final byte[] BYTES = "exact deliverable bytes".getBytes(StandardCharsets.UTF_8);

    private AgentTaskArtifactService artifactService;
    private AgentTaskArtifactContentService contentService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        artifactService = mock(AgentTaskArtifactService.class);
        contentService = mock(AgentTaskArtifactContentService.class);
        mvc = MockMvcBuilders.standaloneSetup(
                new AgentTaskDeliverableController(artifactService, contentService)).build();
    }

    @Test
    void listBindsTenantClientAndActorOnlyFromValidatedJwtAndFiltersResponse() throws Exception {
        when(artifactService.listForTaskOwner(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(List.of(view()));

        mvc.perform(get("/agent/tasks/{taskId}/deliverables", TASK)
                        .queryParam("workItemId", "work-1")
                        .queryParam("limit", "25")
                        .principal(jwt(TENANT, CLIENT, ACTOR)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(jsonPath("$.items[0].artifactId").value(ARTIFACT))
                .andExpect(jsonPath("$.items[0].artifactVersion").value(3))
                .andExpect(jsonPath("$.items[0].contentHash").value(sha256(BYTES)))
                .andExpect(jsonPath("$.items[0].contentByteLength").value(BYTES.length))
                .andExpect(jsonPath("$.items[0].storageUri").doesNotExist())
                .andExpect(jsonPath("$.items[0].content").doesNotExist())
                .andExpect(jsonPath("$.items[0].metadata").doesNotExist())
                .andExpect(jsonPath("$.items[0].tenantId").doesNotExist())
                .andExpect(jsonPath("$.items[0].clientId").doesNotExist());

        ArgumentCaptor<AgentTaskArtifactQueryDTO> query =
                ArgumentCaptor.forClass(AgentTaskArtifactQueryDTO.class);
        verify(artifactService).listForTaskOwner(eq("0"), eq(CLIENT), eq(TENANT), eq(TASK), query.capture());
        assertEquals("work-1", query.getValue().getWorkItemId());
        assertEquals(25, query.getValue().getLimit());
    }

    @Test
    void listRejectsActorScopeCursorAndDuplicateQueryInjectionBeforeService() throws Exception {
        JwtAuthenticationToken auth = jwt(TENANT, CLIENT, ACTOR);
        for (String uri : List.of(
                "/agent/tasks/task-1/deliverables?actorAgentId=" + OTHER,
                "/agent/tasks/task-1/deliverables?tenantId=tenant-b",
                "/agent/tasks/task-1/deliverables?clientId=client-b",
                "/agent/tasks/task-1/deliverables?cursor=opaque",
                "/agent/tasks/task-1/deliverables?workItemId=",
                "/agent/tasks/task-1/deliverables?limit=0",
                "/agent/tasks/task-1/deliverables?limit=01",
                "/agent/tasks/task-1/deliverables?limit=101",
                "/agent/tasks/task-1/deliverables?limit=1&limit=2")) {
            mvc.perform(get(uri).principal(auth))
                    .andExpect(status().isBadRequest())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"));
        }
        verifyNoInteractions(artifactService, contentService);
    }

    @Test
    void anonymousNonJwtMalformedClaimsAndNameMismatchFailBeforeService() throws Exception {
        mvc.perform(get("/agent/tasks/%20bad%20/deliverables"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/agent/tasks/%20bad%20/deliverables")
                        .principal(UsernamePasswordAuthenticationToken.authenticated(
                                "user", "n/a", List.of())))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/agent/tasks/%20bad%20/deliverables")
                        .principal(jwtClaims(TENANT, 7, ACTOR, ACTOR)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/agent/tasks/%20bad%20/deliverables")
                        .principal(jwtClaims(TENANT, CLIENT, ACTOR, OTHER)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(artifactService, contentService);
    }

    @Test
    void ownerScopeDoesNotNeedPersonaBindingAndTaskNotFoundRemainsOpaque() throws Exception {
        when(artifactService.listForTaskOwner(eq("0"), eq(CLIENT), eq(TENANT), eq(TASK), any()))
                .thenReturn(List.of(view()));
        mvc.perform(get("/agent/tasks/{taskId}/deliverables", TASK)
                        .principal(jwt(TENANT, CLIENT, OTHER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].artifactId").value(ARTIFACT));

        when(artifactService.listForTaskOwner(eq("0"), eq(CLIENT), eq("tenant-b"), eq(TASK), any()))
                .thenThrow(new AgentTaskCollaborationException(Reason.NOT_FOUND, "outside scope"));
        MvcResult foreign = list(jwt("tenant-b", CLIENT, OTHER));
        assertEquals(404, foreign.getResponse().getStatus());
        assertFalse(foreign.getResponse().getContentAsString().contains("outside scope"));
    }

    @Test
    void contentDownloadUsesTaskOwnerScopeAndReturnsExactSafeBytes() throws Exception {
        when(contentService.readContentForTaskOwner("0", CLIENT, TENANT, TASK, ARTIFACT, 3))
                .thenReturn(new AgentTaskArtifactContentDTO(ARTIFACT, 3, sha256(BYTES),
                        (long) BYTES.length, "application/octet-stream", BYTES));

        MvcResult result = mvc.perform(get(
                        "/agent/tasks/{taskId}/deliverables/{artifactId}/versions/{version}/content",
                        TASK, ARTIFACT, 3)
                        .principal(jwt(TENANT, CLIENT, ACTOR)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"deliverable-v3.bin\""))
                .andExpect(content().contentType(MediaType.APPLICATION_OCTET_STREAM))
                .andReturn();

        assertArrayEquals(BYTES, result.getResponse().getContentAsByteArray());
        verify(contentService).readContentForTaskOwner("0", CLIENT, TENANT, TASK, ARTIFACT, 3);
    }

    @Test
    void previewUsesOwnerScopedTrustedContentAndReturnsOrderedMultiPartMetadata() throws Exception {
        List<PreviewCase> cases = List.of(
                new PreviewCase(1, "image/png", png(), "ORIGINAL_IMAGE", "ORIGINAL_IMAGE",
                        List.of(new ExpectedPart("content", "image/png")),
                        "content", null, true, null),
                new PreviewCase(2, "text/plain", "plain preview".getBytes(StandardCharsets.UTF_8),
                        "PLAIN_TEXT", "PLAIN_TEXT", List.of(new ExpectedPart("content", "text/plain")),
                        "content", "plain preview", false, null),
                new PreviewCase(3,
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        docx(), "EXTRACTED_TEXT", "EXTRACTED_TEXT",
                        List.of(new ExpectedPart("content", "text/plain")),
                        "content", "Word preview", false, null),
                new PreviewCase(4,
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        xlsx(), "SHEET_TEXT", "EXTRACTED_TEXT",
                        List.of(new ExpectedPart("sheet-1", "text/plain"),
                                new ExpectedPart("sheet-2", "text/plain"),
                                new ExpectedPart("content", "text/plain")),
                        "sheet-2", "[公式未执行] =Inputs!A1*2", false, "工作表：Inputs"),
                new PreviewCase(5,
                        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                        pptx(), "PAGED_IMAGE", "EXTRACTED_TEXT",
                        List.of(new ExpectedPart("slide-1", "image/png"),
                                new ExpectedPart("slide-2", "image/png"),
                                new ExpectedPart("content", "text/plain")),
                        "slide-2", null, false, "Second slide preview"),
                new PreviewCase(6, "application/pdf", pdf(), "PAGED_TEXT", "EXTRACTED_TEXT",
                        List.of(new ExpectedPart("page-1", "text/plain"),
                                new ExpectedPart("page-2", "text/plain"),
                                new ExpectedPart("content", "text/plain")),
                        "page-2", "Second PDF preview", false, "First PDF preview"));

        for (PreviewCase sample : cases) {
            when(contentService.readContentForTaskOwner(
                    "0", CLIENT, TENANT, TASK, ARTIFACT, sample.version()))
                    .thenReturn(artifactContent(sample.version(), sample.mimeType(), sample.bytes()));

            var metadata = mvc.perform(get(
                            "/agent/tasks/{taskId}/deliverables/{artifactId}/versions/{version}/preview",
                            TASK, ARTIFACT, sample.version())
                            .queryParam("view", "parts")
                            .principal(jwt(TENANT, CLIENT, ACTOR)))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(jsonPath("$.state").value("READY"))
                    .andExpect(jsonPath("$.representation").value(sample.representation()))
                    .andExpect(jsonPath("$.parts.length()").value(sample.parts().size()))
                    .andExpect(jsonPath("$.partial").value(false))
                    .andExpect(jsonPath("$.storageUri").doesNotExist())
                    .andExpect(jsonPath("$.lease").doesNotExist())
                    .andExpect(jsonPath("$.prompt").doesNotExist())
                    .andExpect(jsonPath("$.content").doesNotExist())
                    .andReturn();
            String metadataJson = metadata.getResponse().getContentAsString();
            int previousPart = -1;
            for (ExpectedPart expected : sample.parts()) {
                int partIndex = metadataJson.indexOf(
                        "\"partId\":\"" + expected.partId() + "\"");
                int mimeIndex = metadataJson.indexOf(
                        "\"contentMimeType\":\"" + expected.mimeType() + "\"",
                        partIndex);
                assertTrue(partIndex > previousPart, "part order changed: " + metadataJson);
                assertTrue(mimeIndex > partIndex, "part MIME changed: " + metadataJson);
                previousPart = partIndex;
            }

            ExpectedPart legacyContent = sample.parts().stream()
                    .filter(part -> part.partId().equals("content"))
                    .findFirst().orElseThrow();
            mvc.perform(get(
                            "/agent/tasks/{taskId}/deliverables/{artifactId}/versions/{version}/preview",
                            TASK, ARTIFACT, sample.version())
                            .principal(jwt(TENANT, CLIENT, ACTOR)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.representation").value(
                            sample.legacyRepresentation()))
                    .andExpect(jsonPath("$.parts.length()").value(1))
                    .andExpect(jsonPath("$.parts[0].partId").value("content"))
                    .andExpect(jsonPath("$.parts[0].contentMimeType").value(
                            legacyContent.mimeType()));

            ExpectedPart requested = sample.parts().stream()
                    .filter(part -> part.partId().equals(sample.requestPartId()))
                    .findFirst().orElseThrow();
            MvcResult part = mvc.perform(get(
                            "/agent/tasks/{taskId}/deliverables/{artifactId}/versions/{version}"
                                    + "/preview/parts/{partId}",
                            TASK, ARTIFACT, sample.version(), sample.requestPartId())
                            .principal(jwt(TENANT, CLIENT, ACTOR)))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(content().contentType(requested.mimeType()))
                    .andReturn();

            if (sample.expectOriginalBytes()) {
                assertArrayEquals(sample.bytes(), part.getResponse().getContentAsByteArray());
            } else if (sample.expectedPartText() != null) {
                assertTrue(part.getResponse().getContentAsString(StandardCharsets.UTF_8)
                        .contains(sample.expectedPartText()));
            } else {
                assertTrue(part.getResponse().getContentAsByteArray().length > 0);
                assertTrue(ImageIO.read(new ByteArrayInputStream(
                        part.getResponse().getContentAsByteArray())) != null);
            }

            int expectedReads = 3;
            if (sample.legacyContentText() != null) {
                mvc.perform(get(
                                "/agent/tasks/{taskId}/deliverables/{artifactId}/versions/{version}"
                                        + "/preview/parts/content",
                                TASK, ARTIFACT, sample.version())
                                .principal(jwt(TENANT, CLIENT, ACTOR)))
                        .andExpect(status().isOk())
                        .andExpect(content().contentType("text/plain"))
                        .andExpect(content().string(org.hamcrest.Matchers.containsString(
                                sample.legacyContentText())));
                expectedReads++;
            }
            verify(contentService, org.mockito.Mockito.times(expectedReads)).readContentForTaskOwner(
                    "0", CLIENT, TENANT, TASK, ARTIFACT, sample.version());
        }
    }

    @Test
    void previewRejectsMalformedRequestsBeforeReadButChecksAclBeforeUnknownPartLookup() throws Exception {
        JwtAuthenticationToken auth = jwt(TENANT, CLIENT, ACTOR);
        mvc.perform(get(
                        "/agent/tasks/{taskId}/deliverables/{artifactId}/versions/{version}/preview",
                        "task%bad", ARTIFACT, 1).principal(auth))
                .andExpect(status().isBadRequest());
        mvc.perform(get(
                        "/agent/tasks/{taskId}/deliverables/{artifactId}/versions/{version}/preview",
                        TASK, "artifact;bad", 1).principal(auth))
                .andExpect(status().isBadRequest());
        mvc.perform(get(
                        "/agent/tasks/{taskId}/deliverables/{artifactId}/versions/{version}/preview",
                        TASK, ARTIFACT, "01").principal(auth))
                .andExpect(status().isBadRequest());
        mvc.perform(get(
                        "/agent/tasks/{taskId}/deliverables/{artifactId}/versions/{version}/preview",
                        TASK, ARTIFACT, 1).queryParam("actorAgentId", OTHER).principal(auth))
                .andExpect(status().isBadRequest());
        mvc.perform(get(
                        "/agent/tasks/{taskId}/deliverables/{artifactId}/versions/{version}/preview",
                        TASK, ARTIFACT, 1).queryParam("view", "legacy").principal(auth))
                .andExpect(status().isBadRequest());
        mvc.perform(get(
                        "/agent/tasks/{taskId}/deliverables/{artifactId}/versions/{version}/preview",
                        TASK, ARTIFACT, 1).queryParam("view", "parts", "parts").principal(auth))
                .andExpect(status().isBadRequest());
        mvc.perform(get(
                        "/agent/tasks/{taskId}/deliverables/{artifactId}/versions/{version}/preview",
                        TASK, ARTIFACT, 1).queryParam("view", "parts")
                        .queryParam("actorAgentId", OTHER).principal(auth))
                .andExpect(status().isBadRequest());
        mvc.perform(get(
                        "/agent/tasks/{taskId}/deliverables/{artifactId}/versions/{version}"
                                + "/preview/parts/content",
                        TASK, ARTIFACT, 1).queryParam("partId", "content").principal(auth))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(contentService);

        when(contentService.readContentForTaskOwner(
                "0", CLIENT, TENANT, TASK, ARTIFACT, 1))
                .thenReturn(artifactContent(1, "text/plain", BYTES));
        mvc.perform(get(
                        "/agent/tasks/{taskId}/deliverables/{artifactId}/versions/{version}"
                                + "/preview/parts/prompt",
                        TASK, ARTIFACT, 1).principal(auth))
                .andExpect(status().isNotFound())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(jsonPath("$.code").value("DELIVERABLE_PREVIEW_PART_NOT_FOUND"));
        verify(contentService).readContentForTaskOwner(
                "0", CLIENT, TENANT, TASK, ARTIFACT, 1);

        when(contentService.readContentForTaskOwner(
                "0", CLIENT, TENANT, TASK, ARTIFACT, 2))
                .thenThrow(new AgentTaskCollaborationException(Reason.NOT_FOUND, "foreign"));
        mvc.perform(get(
                        "/agent/tasks/{taskId}/deliverables/{artifactId}/versions/{version}"
                                + "/preview/parts/prompt",
                        TASK, ARTIFACT, 2).principal(auth))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DELIVERABLE_NOT_FOUND"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("foreign"))));
    }

    @Test
    void previewFailsClosedForHashMimeAndImageContentMismatch() throws Exception {
        when(contentService.readContentForTaskOwner(
                "0", CLIENT, TENANT, TASK, ARTIFACT, 1))
                .thenReturn(new AgentTaskArtifactContentDTO(ARTIFACT, 1, "0".repeat(64),
                        (long) BYTES.length, "text/plain", BYTES));
        mvc.perform(get(
                        "/agent/tasks/{taskId}/deliverables/{artifactId}/versions/{version}/preview",
                        TASK, ARTIFACT, 1).principal(jwt(TENANT, CLIENT, ACTOR)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DELIVERABLE_UNAVAILABLE"));

        when(contentService.readContentForTaskOwner(
                "0", CLIENT, TENANT, TASK, ARTIFACT, 2))
                .thenReturn(artifactContent(2, "text/plain; charset=utf-8", BYTES));
        mvc.perform(get(
                        "/agent/tasks/{taskId}/deliverables/{artifactId}/versions/{version}/preview",
                        TASK, ARTIFACT, 2).principal(jwt(TENANT, CLIENT, ACTOR)))
                .andExpect(status().isServiceUnavailable());

        byte[] notAnImage = "not a png".getBytes(StandardCharsets.UTF_8);
        when(contentService.readContentForTaskOwner(
                "0", CLIENT, TENANT, TASK, ARTIFACT, 3))
                .thenReturn(artifactContent(3, "image/png", notAnImage));
        mvc.perform(get(
                        "/agent/tasks/{taskId}/deliverables/{artifactId}/versions/{version}"
                                + "/preview/parts/content",
                        TASK, ARTIFACT, 3).principal(jwt(TENANT, CLIENT, ACTOR)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("not a png"))));
    }

    @Test
    void unsupportedPreviewIsHonestMetadataAndHasNoContentPart() throws Exception {
        byte[] source = new byte[] {9};
        when(contentService.readContentForTaskOwner(
                "0", CLIENT, TENANT, TASK, ARTIFACT, 7))
                .thenReturn(artifactContent(7, "application/octet-stream", source));

        mvc.perform(get(
                        "/agent/tasks/{taskId}/deliverables/{artifactId}/versions/{version}/preview",
                        TASK, ARTIFACT, 7).principal(jwt(TENANT, CLIENT, ACTOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("UNSUPPORTED"))
                .andExpect(jsonPath("$.representation").value("UNSUPPORTED"))
                .andExpect(jsonPath("$.parts").isEmpty())
                .andExpect(jsonPath("$.reason").value("该文件类型暂不支持预览"));

        mvc.perform(get(
                        "/agent/tasks/{taskId}/deliverables/{artifactId}/versions/{version}"
                                + "/preview/parts/content",
                        TASK, ARTIFACT, 7).principal(jwt(TENANT, CLIENT, ACTOR)))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("DELIVERABLE_PREVIEW_UNSUPPORTED"));
    }

    @Test
    void contentDownloadRejectsQueryAndInvalidVersionsBeforeContentService() throws Exception {
        JwtAuthenticationToken auth = jwt(TENANT, CLIENT, ACTOR);
        for (String uri : List.of(
                "/agent/tasks/task-1/deliverables/artifact-1/versions/0/content",
                "/agent/tasks/task-1/deliverables/artifact-1/versions/01/content",
                "/agent/tasks/task-1/deliverables/artifact-1/versions/1/content?actorAgentId=" + OTHER,
                "/agent/tasks/task-1/deliverables/artifact-1/versions/1/content?workItemId=work-1")) {
            mvc.perform(get(uri).principal(auth)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(contentService);
    }

    @Test
    void corruptArtifactMetadataAndBytesFailClosedWithoutLeakingFields() throws Exception {
        AgentTaskArtifactViewDTO corrupt = view();
        corrupt.setContentMimeType("text/plain\r\nX-Secret: yes");
        when(artifactService.listForTaskOwner(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(List.of(corrupt));
        mvc.perform(get("/agent/tasks/{taskId}/deliverables", TASK)
                        .principal(jwt(TENANT, CLIENT, ACTOR)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("X-Secret"))));

        when(contentService.readContentForTaskOwner(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyInt()))
                .thenReturn(new AgentTaskArtifactContentDTO(ARTIFACT, 1, "0".repeat(64),
                        (long) BYTES.length, "text/plain", BYTES));
        mvc.perform(get(
                        "/agent/tasks/{taskId}/deliverables/{artifactId}/versions/{version}/content",
                        TASK, ARTIFACT, 1).principal(jwt(TENANT, CLIENT, ACTOR)))
                .andExpect(status().isServiceUnavailable());
    }

    private MvcResult list(JwtAuthenticationToken authentication) throws Exception {
        return mvc.perform(get("/agent/tasks/{taskId}/deliverables", TASK)
                .principal(authentication)).andReturn();
    }

    private static AgentTaskArtifactViewDTO view() {
        AgentTaskArtifactViewDTO view = new AgentTaskArtifactViewDTO();
        view.setArtifactId(ARTIFACT);
        view.setTaskId(TASK);
        view.setWorkItemId("work-1");
        view.setProducerAgentId(ACTOR);
        view.setArtifactType("document");
        view.setTitle("交付报告");
        view.setContent("legacy bytes never leave catalog");
        view.setStorageUri("cyf-artifact://internal-secret");
        view.setContentHash(sha256(BYTES));
        view.setContentByteLength((long) BYTES.length);
        view.setContentMimeType("application/octet-stream");
        view.setManagedStorage(true);
        view.setArtifactVersion(3);
        view.setVisibility("task_members");
        view.setMetadata(java.util.Map.of("secret", "must-not-leak"));
        view.setCreatedAt(123L);
        return view;
    }

    private static AgentTaskArtifactContentDTO artifactContent(int version, String mimeType, byte[] bytes) {
        return new AgentTaskArtifactContentDTO(ARTIFACT, version, sha256(bytes),
                (long) bytes.length, mimeType, bytes);
    }

    private static byte[] png() throws Exception {
        BufferedImage image = new BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0x112233);
        image.setRGB(1, 0, 0x445566);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            assertTrue(ImageIO.write(image, "png", output));
            return output.toByteArray();
        }
    }

    private static byte[] docx() throws Exception {
        try (XWPFDocument document = new XWPFDocument();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("Word preview");
            document.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] xlsx() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.createSheet("Inputs").createRow(0).createCell(0).setCellValue(7);
            workbook.createSheet("Summary").createRow(0).createCell(0)
                    .setCellFormula("Inputs!A1*2");
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] pptx() throws Exception {
        try (XMLSlideShow slideShow = new XMLSlideShow();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            slideShow.createSlide().createTextBox().setText("First slide preview");
            slideShow.createSlide().createTextBox().setText("Second slide preview");
            slideShow.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] pdf() throws Exception {
        try (PDDocument document = new PDDocument();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            addPdfPage(document, "First PDF preview");
            addPdfPage(document, "Second PDF preview");
            document.save(output);
            return output.toByteArray();
        }
    }

    private static void addPdfPage(PDDocument document, String text) throws Exception {
        PDPage page = new PDPage();
        document.addPage(page);
        try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
            stream.beginText();
            stream.setFont(PDType1Font.HELVETICA, 12);
            stream.newLineAtOffset(72, 720);
            stream.showText(text);
            stream.endText();
        }
    }

    private record ExpectedPart(String partId, String mimeType) {
    }

    private record PreviewCase(int version, String mimeType, byte[] bytes,
            String representation, String legacyRepresentation, List<ExpectedPart> parts,
            String requestPartId, String expectedPartText, boolean expectOriginalBytes,
            String legacyContentText) {
    }

    private static JwtAuthenticationToken jwt(String tenant, String client, String actor) {
        return jwtClaims(tenant, client, actor, actor);
    }

    private static JwtAuthenticationToken jwtClaims(
            String tenant, Object client, String subject, String authenticationName) {
        Jwt token = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("jiacn", tenant)
                .claim("client_id", client)
                .claim("sub", subject)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        return new JwtAuthenticationToken(token, List.of()) {
            @Override
            public String getName() {
                return authenticationName;
            }
        };
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }
}
