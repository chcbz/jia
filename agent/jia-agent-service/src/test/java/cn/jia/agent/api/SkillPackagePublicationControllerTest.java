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
                .claim("tenant_id","0").claim("client_id","client").claim("jiacn","owner").build();
        return new JwtAuthenticationToken(jwt,admin?List.of(new SimpleGrantedAuthority("ROLE_ADMIN")):List.of());
    }
    private static String metadata(String extra) {
        return """
            {"productId":"product","productVersionId":"target","name":"Images","description":"images",
             "skillKey":"image-generate","skillVersion":"1.3.0","priceMicro":"5000000","deploymentRestriction":"NONE",
             "approvedPermissionsManifest":"[]","packageSha256":"%s","manifestSha256":null,"expectedProductVersion":"2",
             "positionAuthority":{"appointmentId":"appointment","appointmentRevision":"1","positionRevision":"2","agentId":"wuyong","bindingId":"7"}%s}
            """.formatted("a".repeat(64),extra);
    }
    @Test void coveredSourceAndPositionGrantAreForwardedWithoutAdminRole() throws Exception {
        var service=mock(SkillPackagePublicationService.class);var controller=new SkillPackagePublicationController(service);
        var request=new MockHttpServletRequest("POST","/agent/skill-maintenance/product-versions");
        var file=new MockMultipartFile("package","skill.zip","application/zip",new byte[]{1});
        controller.publish(actor(false),request,metadata(",\"coveredUpgradeFromProductVersionId\":\"source\""),file);
        verify(service).publish(any(),argThat(g->g.agentId().equals("wuyong")&&g.bindingId()==7&&g.publish()),argThat(c->"source".equals(c.coveredUpgradeFromProductVersionId())),any());
        controller.publish(actor(false),request,metadata(""),file);
        verify(service).publish(any(),argThat(g->g.agentId().equals("wuyong")&&g.bindingId()==7&&g.publish()),argThat(c->c.coveredUpgradeFromProductVersionId()==null),any());

        verifyNoMoreInteractions(service);
    }
    @Test void adminRoleDoesNotReplaceRequiredAppointment() {
        var service=mock(SkillPackagePublicationService.class);var controller=new SkillPackagePublicationController(service);
        var request=new MockHttpServletRequest("POST","/agent/skill-maintenance/product-versions");
        var metadata=metadata("").replace("\"positionAuthority\":{", "\"legacyGrant\":{");
        assertThrows(IllegalArgumentException.class,()->controller.publish(actor(true),request,metadata,new MockMultipartFile("package",new byte[]{1})));
        verifyNoInteractions(service);
    }
    @Test void positionDenialKeepsForbiddenStatus() {
        var controller=new SkillPackagePublicationController(mock(SkillPackagePublicationService.class));
        assertEquals(403,controller.failure(new cn.jia.agent.position.PositionSkillException(403,"POSITION_AUTHORITY_REVOKED")).getStatusCode().value());
    }
    @Test void coveredSourceDoesNotCoerceNumericOrUnknownPolicyInput() {
        var service=mock(SkillPackagePublicationService.class);var controller=new SkillPackagePublicationController(service);
        var request=new MockHttpServletRequest("POST","/agent/skill-maintenance/product-versions");
        var file=new MockMultipartFile("package",new byte[]{1});
        assertThrows(IllegalArgumentException.class,()->controller.publish(actor(false),request,metadata(",\"coveredUpgradeFromProductVersionId\":123"),file));
        assertThrows(IllegalArgumentException.class,()->controller.publish(actor(false),request,metadata(",\"policy\":\"PAID\""),file));
        verifyNoInteractions(service);
    }
    @Test void metadataUsesPositionAuthorityAndRejectsExecutableOrPricingFields() {
        var service=mock(SkillPackagePublicationService.class);var controller=new SkillPackagePublicationController(service);
        var request=new MockHttpServletRequest("PUT","/agent/skill-maintenance/product-metadata");
        String body="""
            {"productId":"product","name":"图片生成","description":"从文字生成新图片","expectedProductVersion":"2",
            "positionAuthority":{"appointmentId":"appointment","appointmentRevision":"1","positionRevision":"2","agentId":"wuyong","bindingId":"7"}}
            """;
        controller.updateMetadata(actor(false),request,body);
        verify(service).updateMetadata(any(),argThat(g->g.publish()&&g.agentId().equals("wuyong")),
                argThat(c->c.name().equals("图片生成")&&c.expectedProductVersion()==2));
        assertThrows(IllegalArgumentException.class,()->controller.updateMetadata(actor(false),request,body.replace("\"name\":","\"priceMicro\":\"0\",\"name\":")));
        assertThrows(IllegalArgumentException.class,()->controller.updateMetadata(actor(true),request,body.replace("positionAuthority","legacyGrant")));
        verifyNoMoreInteractions(service);
    }

}
