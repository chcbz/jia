package cn.jia.agent.api;

import cn.jia.agent.skill.SkillPackagePublicationService;
import cn.jia.agent.skill.SkillMarketplaceException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SkillPackagePublicationControllerTest {
    private static JwtAuthenticationToken actor(boolean admin) {
        var jwt=Jwt.withTokenValue("fixture").header("alg","none").subject("publisher")
                .claim("client_id","client").claim("jiacn","owner").build();
        return new JwtAuthenticationToken(jwt,admin?List.of(new SimpleGrantedAuthority("ROLE_ADMIN")):List.of());
    }
    private static String metadata(String extra) {
        return """
            {"productId":"product","productVersionId":"target","name":"Images","description":"images",
             "skillKey":"image-generate","skillVersion":"1.3.0","priceMicro":"5000000","deploymentRestriction":"NONE",
             "approvedPermissionsManifest":"[]","packageSha256":"%s","manifestSha256":null,"expectedProductVersion":"2"%s}
            """.formatted("a".repeat(64),extra);
    }
    @Test void coveredSourceIsExplicitOptionalAndOnlyAdminCanPublish() throws Exception {
        var service=mock(SkillPackagePublicationService.class);var controller=new SkillPackagePublicationController(service);
        var request=new MockHttpServletRequest("POST","/agent/skill-maintenance/product-versions");
        var file=new MockMultipartFile("package","skill.zip","application/zip",new byte[]{1});
        controller.publish(actor(true),request,metadata(",\"coveredUpgradeFromProductVersionId\":\"source\""),file);
        verify(service).publish(any(),eq(true),argThat(c->"source".equals(c.coveredUpgradeFromProductVersionId())),any());
        controller.publish(actor(true),request,metadata(""),file);
        verify(service).publish(any(),eq(true),argThat(c->c.coveredUpgradeFromProductVersionId()==null),any());
        assertEquals(403,assertThrows(SkillMarketplaceException.class,()->controller.publish(actor(false),request,metadata(""),file)).status());
        verifyNoMoreInteractions(service);
    }
    @Test void coveredSourceDoesNotCoerceNumericOrUnknownPolicyInput() {
        var service=mock(SkillPackagePublicationService.class);var controller=new SkillPackagePublicationController(service);
        var request=new MockHttpServletRequest("POST","/agent/skill-maintenance/product-versions");
        var file=new MockMultipartFile("package",new byte[]{1});
        assertThrows(IllegalArgumentException.class,()->controller.publish(actor(true),request,metadata(",\"coveredUpgradeFromProductVersionId\":123"),file));
        assertThrows(IllegalArgumentException.class,()->controller.publish(actor(true),request,metadata(",\"policy\":\"PAID\""),file));
        verifyNoInteractions(service);
    }
}
