package cn.jia.agent.api;

import cn.jia.agent.entity.PersonalWorkspaceViews;
import cn.jia.agent.service.PersonalWorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PersonalWorkspaceControllerPreviewTest {
    private static final String CLIENT = "client-a";
    private static final String OWNER = "owner-a";
    private static final String FILE = "file-a";

    private PersonalWorkspaceService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(PersonalWorkspaceService.class);
        mvc = MockMvcBuilders.standaloneSetup(new PersonalWorkspaceController(service)).build();
    }

    @Test
    void previewMetadataRequiresExplicitPartsOptInAndKeepsLegacyCatalogByDefault()
            throws Exception {
        PersonalWorkspaceViews.PreviewView multipart = new PersonalWorkspaceViews.PreviewView(
                "READY",
                List.of(
                        new PersonalWorkspaceViews.PreviewPart("slide-1", "image/png"),
                        new PersonalWorkspaceViews.PreviewPart("slide-2", "image/png"),
                        new PersonalWorkspaceViews.PreviewPart("content", "text/plain")),
                false, null);
        PersonalWorkspaceService.Scope scope =
                new PersonalWorkspaceService.Scope("0", CLIENT, OWNER);
        when(service.preview(scope, FILE, 1)).thenReturn(multipart);

        mvc.perform(get("/agent/personal-workspace/files/{fileId}/versions/{version}/preview",
                        FILE, 1).principal(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parts.length()").value(1))
                .andExpect(jsonPath("$.parts[0].partId").value("content"))
                .andExpect(jsonPath("$.parts[0].contentMimeType").value("text/plain"));

        mvc.perform(get("/agent/personal-workspace/files/{fileId}/versions/{version}/preview",
                        FILE, 1).queryParam("view", "parts").principal(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parts.length()").value(3))
                .andExpect(jsonPath("$.parts[0].partId").value("slide-1"))
                .andExpect(jsonPath("$.parts[1].partId").value("slide-2"))
                .andExpect(jsonPath("$.parts[2].partId").value("content"));

        mvc.perform(get("/agent/personal-workspace/files/{fileId}/versions/{version}/preview",
                        FILE, 1).queryParam("view", "legacy").principal(jwt()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/agent/personal-workspace/files/{fileId}/versions/{version}/preview",
                        FILE, 1).queryParam("view", "parts")
                        .queryParam("actorAgentId", "foreign").principal(jwt()))
                .andExpect(status().isBadRequest());

        verify(service, times(2)).preview(scope, FILE, 1);
    }

    private static JwtAuthenticationToken jwt() {
        Jwt token = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("jiacn", OWNER)
                .claim("client_id", CLIENT)
                .claim("sub", "subject-a")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        return new JwtAuthenticationToken(token, List.of());
    }
}
