package cn.jia.agent.config;
import cn.jia.oauth.entity.OauthApiKeyEntity;
import cn.jia.oauth.service.ApiKeyService;
import cn.jia.user.security.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class SkillPackageSecurityConfigurationTest {
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }
    @Test void exactCurrentKeyAuthenticatesWithoutExposingSecretInPrincipal() throws Exception {
        var key=new OauthApiKeyEntity();key.setId("key");key.setTenantId("Tenant");key.setJiacn("Tenant");key.setClientId("Client");key.setApiKey("secret-fixture");key.setStatus(1);
        var keys=mock(ApiKeyService.class);when(keys.findByApiKey("secret-fixture")).thenReturn(key);when(keys.get("key")).thenReturn(key);
        var accounts=mock(AccountSecurityService.class);when(accounts.findUniqueByExactJiacn("Tenant")).thenReturn(Optional.of(new AccountSecuritySnapshot(1,"Tenant",AccountState.ACTIVE,1)));
        var filter=new SkillPackageSecurityConfiguration.PackageKeyFilter(provider(keys),provider(accounts));
        var request=new MockHttpServletRequest("GET","/internal/agent/skill-installations/i/package");request.addHeader("X-API-Key","secret-fixture");
        filter.doFilter(request,new MockHttpServletResponse(),(r,s)->{
            var principal=(OauthApiKeyEntity)SecurityContextHolder.getContext().getAuthentication().getPrincipal();
            assertEquals("key",principal.getId());assertNull(principal.getApiKey());
        });
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        key.setStatus(0);var denied=new MockHttpServletResponse();
        filter.doFilter(newRequest(),denied,(r,s)->fail("revoked key reached handler"));assertEquals(403,denied.getStatus());
    }
    @Test void duplicateHeaderQueryAndWrongMethodDenyBeforeCredentialLookup() throws Exception {
        var keys=mock(ApiKeyService.class);var accounts=mock(AccountSecurityService.class);
        var filter=new SkillPackageSecurityConfiguration.PackageKeyFilter(provider(keys),provider(accounts));
        for(int n=0;n<3;n++) {
            var request=newRequest();if(n==0)request.addHeader("X-API-Key","other");if(n==1)request.addParameter("key","secret");if(n==2)request.setMethod("POST");
            var response=new MockHttpServletResponse();filter.doFilter(request,response,(r,s)->fail("invalid request admitted"));assertEquals(403,response.getStatus());
        }
        verifyNoInteractions(keys,accounts);
    }
    private static MockHttpServletRequest newRequest() { var r=new MockHttpServletRequest("GET","/internal/agent/skill-installations/i/package");r.addHeader("X-API-Key","secret-fixture");return r; }
    @SuppressWarnings("unchecked") private static <T> ObjectProvider<T> provider(T value) { ObjectProvider<T> p=mock(ObjectProvider.class);when(p.getObject()).thenReturn(value);when(p.getIfAvailable()).thenReturn(value);return p; }
}
