package cn.jia.mat.dao.impl;

import cn.jia.mat.dao.MatVoteQuestionDao;
import cn.jia.mat.entity.MatVoteQuestionEntity;
import cn.jia.test.BaseDbUnitTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MatVoteQuestionDaoImplTest extends BaseDbUnitTest {
    @Inject
    private MatVoteQuestionDao matVoteQuestionDao;

    @Test
    void selectByVoteId() {
        List<MatVoteQuestionEntity> matVoteQuestionEntities = matVoteQuestionDao.selectByVoteId(1L);
        assertEquals(1, matVoteQuestionEntities.size());
        assertEquals(1, matVoteQuestionEntities.get(0).getId());
    }

    @Test
    void deleteByVoteId() {
        int num = matVoteQuestionDao.deleteByVoteId(1L);
        assertEquals(1, num);
    }

    @Test
    void selectNoTick() {
        MatVoteQuestionEntity matVoteQuestionEntity = matVoteQuestionDao.selectNoTick("1");
        assertEquals(1, matVoteQuestionEntity.getId());
    }
}