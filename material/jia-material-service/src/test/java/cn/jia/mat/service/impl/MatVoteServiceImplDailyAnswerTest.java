package cn.jia.mat.service.impl;

import cn.jia.mat.dao.MatVoteDao;
import cn.jia.mat.dao.MatVoteItemDao;
import cn.jia.mat.dao.MatVoteQuestionDao;
import cn.jia.mat.dao.MatVoteTickDao;
import cn.jia.mat.entity.MatDailyVoteAnswerResult;
import cn.jia.mat.entity.MatVoteQuestionEntity;
import cn.jia.mat.entity.MatVoteTickEntity;
import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MatVoteServiceImplDailyAnswerTest extends BaseMockTest {

    @Mock
    private MatVoteDao matVoteDao;
    @Mock
    private MatVoteQuestionDao matVoteQuestionDao;
    @Mock
    private MatVoteItemDao matVoteItemDao;
    @Mock
    private MatVoteTickDao matVoteTickDao;
    @InjectMocks
    private MatVoteServiceImpl service;

    @Test
    void judgesAndUpdatesStatisticsWithoutReadModifyWrite() {
        MatVoteQuestionEntity question = new MatVoteQuestionEntity()
                .setId(337L).setVoteId(12L).setOpt("BA").setPoint(3);
        when(matVoteQuestionDao.selectById(337L)).thenReturn(question);
        when(matVoteTickDao.insert(any())).thenReturn(1);
        when(matVoteDao.incrementNum(12L)).thenReturn(1);
        when(matVoteItemDao.incrementNum(337L, "ab")).thenReturn(1);

        MatDailyVoteAnswerResult result = service.answerDaily(337L, "user-1", "ab");

        assertTrue(result.correct());
        assertEquals(3, result.point());
        verify(matVoteTickDao).insert(argThat(tick -> tick.getQuestionId() == 337L
                && tick.getVoteId() == 12L && tick.getTick() == 1));
        verify(matVoteDao).incrementNum(12L);
        verify(matVoteItemDao).incrementNum(337L, "ab");
        verify(matVoteDao, never()).selectById(any());
        verify(matVoteItemDao, never()).selectByQuestionId(any());
    }

    @Test
    void tickInsertFailureStopsAllCounterMutations() {
        when(matVoteQuestionDao.selectById(337L)).thenReturn(new MatVoteQuestionEntity()
                .setId(337L).setVoteId(12L).setOpt("A").setPoint(1));
        when(matVoteTickDao.insert(any(MatVoteTickEntity.class))).thenReturn(0);

        assertThrows(IllegalStateException.class, () -> service.answerDaily(337L, "user-1", "A"));
        verifyNoInteractions(matVoteDao, matVoteItemDao);
    }

    @Test
    void missingAggregateFailsSoTransactionCanRollBackTick() {
        when(matVoteQuestionDao.selectById(337L)).thenReturn(new MatVoteQuestionEntity()
                .setId(337L).setVoteId(12L).setOpt("A").setPoint(1));
        when(matVoteTickDao.insert(any(MatVoteTickEntity.class))).thenReturn(1);
        when(matVoteDao.incrementNum(12L)).thenReturn(0);

        assertThrows(IllegalStateException.class, () -> service.answerDaily(337L, "user-1", "A"));
        verify(matVoteItemDao, never()).incrementNum(anyLong(), anyString());
    }
}
