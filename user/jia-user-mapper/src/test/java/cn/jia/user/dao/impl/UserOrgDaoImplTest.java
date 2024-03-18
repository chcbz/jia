package cn.jia.user.dao.impl;

import cn.jia.test.BaseDbUnitTest;
import cn.jia.user.entity.OrgEntity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UserOrgDaoImplTest extends BaseDbUnitTest {
    @Inject
    UserOrgDaoImpl userOrgDao;

    @Test
    void findByCode() {
        OrgEntity flb = userOrgDao.findByCode("FLB");
        assertNotNull(flb);
    }

    @Test
    void selectByParentId() {
        List<OrgEntity> orgEntity = userOrgDao.selectByParentId(0L);
        assertNotNull(orgEntity);
        assertEquals(2, orgEntity.size());
    }

    @Test
    void selectByUserId() {
        List<OrgEntity> orgEntity = userOrgDao.selectByUserId(1L);
        assertNotNull(orgEntity);
        assertEquals(1, orgEntity.size());
    }

    @Test
    void findFirstChild() {
        OrgEntity orgEntity = userOrgDao.findFirstChild(0L);
        assertNotNull(orgEntity);
    }

    @Test
    void findDirector() {
        String director = userOrgDao.findDirector(0L, 1L);
        assertEquals("1", director);
    }

    @Test
    void findPreOrg() {
        OrgEntity orgEntity = userOrgDao.findPreOrg(2L);
        assertNotNull(orgEntity);
        assertEquals(1, orgEntity.getId());
    }

    @Test
    void findNextOrg() {
        OrgEntity orgEntity = userOrgDao.findNextOrg(1L);
        assertNotNull(orgEntity);
        assertEquals(2, orgEntity.getId());
    }

    @Test
    void findParentOrg() {
        OrgEntity orgEntity = userOrgDao.findParentOrg(3L);
        assertNotNull(orgEntity);
        assertEquals(1, orgEntity.getId());
    }
}