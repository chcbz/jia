package cn.jia.mat.dao.impl;

import cn.jia.mat.dao.MatPhraseDao;
import cn.jia.mat.entity.MatPhraseEntity;
import cn.jia.test.BaseDbUnitTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MatPhraseDaoImplTest extends BaseDbUnitTest {
    @Inject
    private MatPhraseDao matPhraseDao;

    @Test
    void selectRandom() {
        MatPhraseEntity query = new MatPhraseEntity();
        query.setJiacn("oH2zD1PUPvspicVak69uB4wDaFLg");
        MatPhraseEntity entity = matPhraseDao.selectRandom(query);
        assertNotNull(entity);
        assertEquals(2, entity.getId());
    }
}