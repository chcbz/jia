package cn.jia.oauth;

import cn.jia.oauth.mapper.OauthApiKeyMapper;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class OauthApiKeyManagedDisableContractTest {
    @Test
    void casSqlBindsEveryExactNonSecretAssociationAndOnlyActiveStatus() throws Exception {
        Update update = OauthApiKeyMapper.class.getMethod("disableManagedKey",
                String.class, String.class, String.class, String.class, String.class, long.class)
                .getAnnotation(Update.class);
        String sql = String.join(" ", update.value()).replaceAll("\\s+", " ");
        for (String predicate : new String[]{
                "BINARY id=BINARY #{keyId}",
                "BINARY tenant_id=BINARY #{tenantId}",
                "BINARY client_id=BINARY #{clientId}",
                "BINARY jiacn=BINARY #{jiacn}",
                "BINARY key_name=BINARY #{keyName}",
                "status=1"}) {
            assertTrue(sql.contains(predicate), predicate + " missing from " + sql);
        }
        assertTrue(sql.contains("SET status=0"));
        assertTrue(!sql.contains("api_key=#{"), "CAS must never carry the API-key secret");
    }
}
