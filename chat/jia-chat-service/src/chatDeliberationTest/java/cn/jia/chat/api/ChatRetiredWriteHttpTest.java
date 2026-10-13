package cn.jia.chat.api;
import cn.jia.chat.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class ChatRetiredWriteHttpTest {
    @Test void legacyImagePreviewConsentIssueAndRevokeAreGoneWithoutAuthorityWork() throws Exception {
        var authority=mock(ChatBountyInteractionV3AuthorityService.class);var previews=mock(ChatBountyInteractionV3PreviewService.class);
        var identities=mock(HumanSenderIdentityResolver.class);var tenants=mock(TenantScopeResolver.class);
        var mvc=MockMvcBuilders.standaloneSetup(new ChatBountyInteractionV3Controller(authority,previews,identities,tenants)).build();
        for(String suffix:new String[]{"preview","provider-consents","provider-consents/consent/revoke"})
            mvc.perform(post("/chat/conversations/1/interactions/"+suffix).header("Idempotency-Key","old-key").content("{\"schemaVersion\":3}"))
                    .andExpect(status().isGone()).andExpect(header().string("Cache-Control","no-store"));
        verifyNoInteractions(authority,previews,identities,tenants);
    }
    @Test void independentInspectionAndOldInteractionWritesNeverCreateRoundsOrReplayEffects() throws Exception {
        var inspection=mock(ChatTypedInspectionController.class,CALLS_REAL_METHODS);
        var interaction=mock(ChatBountyInteractionController.class,CALLS_REAL_METHODS);
        var mvc=MockMvcBuilders.standaloneSetup(inspection,interaction).build();
        for(String suffix:new String[]{"/interactions/inspection","/interactions"})
            mvc.perform(post("/chat/conversations/1"+suffix).header("Idempotency-Key","old-key").content("{}"))
                    .andExpect(status().isGone()).andExpect(jsonPath("$.code").value("LEGACY_SKILL_WRITE_RETIRED"));
    }
}
