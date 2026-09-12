package cn.jia.user.dao.impl;

import cn.jia.test.BaseDbUnitTest;
import cn.jia.user.dao.UserRelationRow;
import cn.jia.user.entity.UserEntity;
import cn.jia.user.entity.UserVO;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UserInfoDaoImplTest extends BaseDbUnitTest {
    @Inject
    UserInfoDaoImpl userInfoDao;
    @Inject
    JdbcTemplate jdbcTemplate;

    @Test
    void selectByJiacn() {
        UserEntity jiacn = userInfoDao.selectByJiacn("oH2zD1El9hvjnWu-LRmCr-JiTuXI");
        assertNotNull(jiacn);
    }

    @Test
    void selectSecurityByExactJiacnPreservesBytesAndMapsDefaults() {
        List<UserEntity> exact = userInfoDao.selectSecurityByExactJiacn("oH2zD1El9hvjnWu-LRmCr-JiTuXI");
        assertEquals(1, exact.size());
        assertEquals("ACTIVE", exact.getFirst().getAccountState());
        assertEquals(0L, exact.getFirst().getAuthEpoch());
        assertTrue(userInfoDao.selectSecurityByExactJiacn("oh2zd1el9hvjnwu-lrmcr-jituxi").isEmpty());
    }

    @Test
    void selectByUsernameIncludesTheAtomicAccountSecuritySnapshot() {
        UserEntity username = userInfoDao.selectByUsername("oH2zD1El9hvjnWu-LRmCr-JiTuXI");
        assertNotNull(username);
        assertEquals("ACTIVE", username.getAccountState());
        assertEquals(0L, username.getAuthEpoch());
    }

    @Test
    void selectByOpenid() {
        UserEntity openid = userInfoDao.selectByOpenid("oH2zD1El9hvjnWu-LRmCr-JiTuXI");
        assertNotNull(openid);
    }

    @Test
    void selectByPhone() {
        UserEntity phone = userInfoDao.selectByPhone("13450909878");
        assertNotNull(phone);
    }

    @Test
    void selectByRole() {
        List<UserEntity> userEntities = userInfoDao.selectByRole(1L);
        assertNotNull(userEntities);
        assertEquals(1, userEntities.size());
    }

    @Test
    void selectByGroup() {
        List<UserEntity> userEntities = userInfoDao.selectByGroup(1L);
        assertNotNull(userEntities);
        assertEquals(1, userEntities.size());
    }

    @Test
    void selectByOrg() {
        List<UserEntity> userEntities = userInfoDao.selectByOrg(1L);
        assertNotNull(userEntities);
        assertEquals(1, userEntities.size());
    }

    @Test
    void searchByExample() {
        UserEntity user = new UserEntity();
        user.setJiacn("oH2zD1El9hvjnWu-LRmCr-JiTuXI");
        List<UserEntity> userEntities = userInfoDao.searchByExample(user);
        assertNotNull(userEntities);
        assertEquals(1, userEntities.size());
    }
    @Test
    void genericInsertAndUpdateCannotWriteAccountSecurityFields() {
        UserEntity inserted = new UserEntity()
                .setUsername("security-boundary-insert")
                .setJiacn("security-boundary-insert")
                .setAccountState("DISABLED")
                .setAuthEpoch(99L);
        assertEquals(1, userInfoDao.insert(inserted));
        UserEntity insertedSecurity = userInfoDao.selectSecurityById(inserted.getId());
        assertEquals("ACTIVE", insertedSecurity.getAccountState());
        assertEquals(0L, insertedSecurity.getAuthEpoch());

        jdbcTemplate.update("UPDATE user_info SET account_state = 'SUSPENDED', auth_epoch = 7 WHERE id = 1");
        UserEntity genericUpdate = new UserEntity()
                .setId(1L)
                .setNickname("safe-profile-update")
                .setAccountState("ACTIVE")
                .setAuthEpoch(0L);
        assertEquals(1, userInfoDao.updateById(genericUpdate));

        UserEntity updatedSecurity = userInfoDao.selectSecurityById(1L);
        assertEquals("SUSPENDED", updatedSecurity.getAccountState());
        assertEquals(7L, updatedSecurity.getAuthEpoch());
        assertEquals("safe-profile-update", userInfoDao.selectById(1L).getNickname());
    }
    @Test
    void selectForListUsesByteExactScopeWithoutLegacyTenantFallback() {
        jdbcTemplate.update("INSERT INTO user_info (id, username, jiacn, tenant_id, client_id) VALUES "
                + "(100, 'exact-a', 'exact-a', 'Tenant-A', 'Client-A'), "
                + "(101, 'case-tenant', 'case-tenant', 'tenant-a', 'Client-A'), "
                + "(102, 'case-client', 'case-client', 'Tenant-A', 'client-a'), "
                + "(103, 'legacy', 'legacy', '0', 'Client-A')");
        UserVO query = new UserVO();
        query.setTenantId("Tenant-A");
        query.setClientId("Client-A");

        List<UserEntity> exact = userInfoDao.selectForList(query);

        assertEquals(List.of(100L), exact.stream().map(UserEntity::getId).toList());
        assertEquals("Tenant-A", query.getTenantId());
        assertEquals("Client-A", query.getClientId());

        query.setTenantId("0");
        assertEquals(List.of(103L), userInfoDao.selectForList(query).stream().map(UserEntity::getId).toList());

        query.setTenantId("tenant-a");
        assertEquals(List.of(101L), userInfoDao.selectForList(query).stream().map(UserEntity::getId).toList());

        query.setTenantId("Tenant-A");
        query.setClientId("client-a");
        assertEquals(List.of(102L), userInfoDao.selectForList(query).stream().map(UserEntity::getId).toList());
    }

    @Test
    void selectRelationsByUserIdsIsBoundedAndExcludesUnrequestedUsers() {
        jdbcTemplate.update("INSERT INTO user_role_rel (user_id, role_id, client_id) VALUES (1, 1, 'duplicate')");
        jdbcTemplate.update("INSERT INTO user_group_rel (user_id, group_id) VALUES (999, 999)");

        List<UserRelationRow> rows = userInfoDao.selectRelationsByUserIds(List.of(1L));

        assertEquals(4, rows.size());
        assertEquals(2, rows.stream().filter(row -> "ROLE".equals(row.relationType())).count());
        assertEquals(1, rows.stream().filter(row -> "ORG".equals(row.relationType())).count());
        assertEquals(1, rows.stream().filter(row -> "GROUP".equals(row.relationType())).count());
        assertTrue(rows.stream().allMatch(row -> row.userId().equals(1L)));
    }

}
