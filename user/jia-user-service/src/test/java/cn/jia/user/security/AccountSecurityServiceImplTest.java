package cn.jia.user.security;

import cn.jia.test.BaseMockTest;
import cn.jia.user.dao.UserInfoDao;
import cn.jia.user.entity.UserEntity;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccountSecurityServiceImplTest extends BaseMockTest {
    @Mock
    UserInfoDao userInfoDao;
    @InjectMocks
    AccountSecurityServiceImpl service;

    @Test
    void returnsNarrowSnapshotAndFailsClosedForUnknownStateOrDuplicateExactIdentity() {
        when(userInfoDao.selectSecurityById(17)).thenReturn(user(17, "Jia-A", "ACTIVE", 4));
        AccountSecuritySnapshot active = service.findByUserId(17).orElseThrow();
        assertTrue(active.matches(17, "Jia-A", 4));

        when(userInfoDao.selectSecurityById(18)).thenReturn(user(18, "Jia-B", "active", 0));
        assertFalse(service.findByUserId(18).orElseThrow().isAuthenticatable());

        when(userInfoDao.selectSecurityByExactJiacn("Jia-A")).thenReturn(List.of(
                user(17, "Jia-A", "ACTIVE", 4), user(19, "Jia-A", "ACTIVE", 0)));
        assertTrue(service.findUniqueByExactJiacn("Jia-A").isEmpty());
        assertTrue(service.findUniqueByExactJiacn(" ").isEmpty());
    }

    @Test
    void revokeUsesOneCasWithoutRetryAndRejectsInvalidOrExhaustedInputs() {
        when(userInfoDao.incrementAuthEpoch(17, 4)).thenReturn(1);
        assertEquals(1, service.revokeAllSessions(17, 4));
        verify(userInfoDao, times(1)).incrementAuthEpoch(17, 4);

        assertEquals(0, service.revokeAllSessions(17, Long.MAX_VALUE));
        assertEquals(0, service.revokeAllSessions(0, 0));
        assertEquals(0, service.revokeAllSessions(17, -1));
        verifyNoMoreInteractions(userInfoDao);
    }

    private static UserEntity user(long id, String jiacn, String state, long epoch) {
        return new UserEntity().setId(id).setJiacn(jiacn).setAccountState(state).setAuthEpoch(epoch);
    }
}
