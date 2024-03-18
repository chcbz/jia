package cn.jia.base.dao.impl;

import cn.jia.base.dao.DictDao;
import cn.jia.base.entity.DictEntity;
import cn.jia.test.BaseDbUnitTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DictDaoImplTest extends BaseDbUnitTest {
    @Inject
    private DictDao dictDao;

    @Test
    public void testFind() {
        DictEntity dictEntity = dictDao.selectById(1L);
        assertNotNull(dictEntity);
        assertEquals("E999", dictEntity.getValue());
    }
}