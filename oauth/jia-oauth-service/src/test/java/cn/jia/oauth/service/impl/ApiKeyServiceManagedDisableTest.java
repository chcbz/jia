package cn.jia.oauth.service.impl;

import cn.jia.oauth.dao.OauthApiKeyDao;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiKeyServiceManagedDisableTest {
    private final OauthApiKeyDao dao = mock(OauthApiKeyDao.class);
    private final ApiKeyServiceImpl service = service();

    @Test
    void exactActiveRowCountIsTheOnlySuccessfulCas() {
        when(dao.disableManagedKey(eq("key-31"), eq("Tenant-A"), eq("Client-A"), eq("Owner-A"),
                eq("hosting:hri-one"), anyLong())).thenReturn(1);
        assertTrue(service.disableManagedKey("key-31", "Tenant-A", "Client-A", "Owner-A", "hosting:hri-one"));

        when(dao.disableManagedKey(eq("key-32"), eq("Tenant-A"), eq("Client-A"), eq("Owner-A"),
                eq("hosting:hri-two"), anyLong())).thenReturn(0);
        assertFalse(service.disableManagedKey("key-32", "Tenant-A", "Client-A", "Owner-A", "hosting:hri-two"));
        verify(dao).disableManagedKey(eq("key-31"), eq("Tenant-A"), eq("Client-A"), eq("Owner-A"),
                eq("hosting:hri-one"), anyLong());
        verify(dao).disableManagedKey(eq("key-32"), eq("Tenant-A"), eq("Client-A"), eq("Owner-A"),
                eq("hosting:hri-two"), anyLong());
    }

    @Test
    void incompleteAssociationNeverReachesTheMapper() {
        assertThrows(IllegalArgumentException.class,
                () -> service.disableManagedKey("key-31", "Tenant-A", "Client-A", "Owner-A", " "));
        verify(dao, never()).disableManagedKey(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), anyLong());
    }

    private ApiKeyServiceImpl service() {
        ApiKeyServiceImpl value = new ApiKeyServiceImpl();
        ReflectionTestUtils.setField(value, "baseDao", dao);
        return value;
    }
}
