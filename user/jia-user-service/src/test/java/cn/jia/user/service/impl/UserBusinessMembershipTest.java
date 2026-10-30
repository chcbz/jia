package cn.jia.user.service.impl;

import cn.jia.user.dao.UserBusinessMembershipDao;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserBusinessMembershipTest {
    @Test void derivesScopeOnlyFromProcessAndUsesAdmittedClient() {
        var dao = mock(UserBusinessMembershipDao.class);
        var production = new UserBusinessMembership(dao, new cn.jia.core.security.FixedTenantAdmissionPolicy("0", null));
        var gray = new UserBusinessMembership(dao, new cn.jia.core.security.FixedTenantAdmissionPolicy("1", null));
        production.ensure(7L, "web");
        gray.ensure(7L, "gray");
        verify(dao).ensure(eq("0"), eq("web"), eq(7L), anyLong());
        verify(dao).ensure(eq("1"), eq("gray"), eq(7L), anyLong());
        assertEquals("0", production.tenantId());
        assertEquals("1", gray.tenantId());
    }

    @Test void registrationWithoutApplicationHasEnvironmentAffiliationOnly() {
        var dao = mock(UserBusinessMembershipDao.class);
        new UserBusinessMembership(dao, new cn.jia.core.security.FixedTenantAdmissionPolicy("0", null)).ensure(7L, null);
        verify(dao).ensure(eq("0"), eq(""), eq(7L), anyLong());
    }

    @Test void invalidIdentityAndAmbiguousScopeCannotReachPersistence() {
        var dao = mock(UserBusinessMembershipDao.class);
        var membership = new UserBusinessMembership(dao, new cn.jia.core.security.FixedTenantAdmissionPolicy("0", null));
        assertThrows(IllegalArgumentException.class, () -> membership.ensure(null, "web"));
        assertThrows(IllegalArgumentException.class, () -> membership.ensure(0L, "web"));
        assertThrows(IllegalArgumentException.class, () -> membership.ensure(7L, "web "));
        verifyNoInteractions(dao);
    }

    @Test void membershipMustJoinIdentityTransactionRatherThanCommitIndependently() throws Exception {
        var tx = UserBusinessMembership.class.getMethod("ensure", Long.class, String.class)
                .getAnnotation(Transactional.class);
        assertEquals(Propagation.MANDATORY, tx.propagation());
        assertArrayEquals(new Class<?>[]{Exception.class}, tx.rollbackFor());
    }
}
