package cn.jia.wx.service.impl;

import cn.jia.mat.entity.MatDailyVoteAnswerResult;
import cn.jia.mat.service.MatVoteService;
import cn.jia.point.common.PointConstants;
import cn.jia.point.entity.PointRecordEntity;
import cn.jia.point.service.PointService;
import cn.jia.test.BaseMockTest;
import cn.jia.wx.dao.WxDailyVoteReceiptDao;
import cn.jia.wx.dailyvote.WxDailyVoteAnswerCommand;
import cn.jia.wx.dailyvote.WxDailyVoteAnswerResult;
import cn.jia.wx.dailyvote.WxDailyVoteKeys;
import cn.jia.wx.dailyvote.WxDailyVoteReplayQuery;
import cn.jia.wx.entity.WxDailyVoteMessageReceiptEntity;
import cn.jia.wx.entity.WxDailyVoteReceiptEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WxDailyVoteServiceImplTest extends BaseMockTest {

    private static final String APPID = "wx-app";
    private static final String MESSAGE_KEY = "a".repeat(64);
    private static final String OTHER_MESSAGE_KEY = "c".repeat(64);
    private static final String USER_KEY = "b".repeat(64);

    @Mock
    private WxDailyVoteReceiptDao receiptDao;
    @Mock
    private MatVoteService voteService;
    @Mock
    private PointService pointService;
    @InjectMocks
    private WxDailyVoteServiceImpl service;

    private final AtomicReference<WxDailyVoteMessageReceiptEntity> claimedMessage = new AtomicReference<>();

    @BeforeEach
    void arrangeMessageReceiptClaim() {
        lenient().when(receiptDao.claimMessage(any())).thenAnswer(invocation -> {
            WxDailyVoteMessageReceiptEntity messageReceipt = invocation.getArgument(0);
            messageReceipt.setId(191L);
            claimedMessage.set(messageReceipt);
            return 1;
        });
        lenient().when(receiptDao.lockMessage(eq(APPID), anyString()))
                .thenAnswer(invocation -> claimedMessage.get());
    }

    @Test
    void persistsCorrectAnswerAndPointExactlyOnce() {
        AtomicReference<WxDailyVoteReceiptEntity> claimed = arrangeOwnedClaim();
        when(voteService.answerDaily(337L, "user-1", "A"))
                .thenReturn(new MatDailyVoteAnswerResult(337L, true, 2, "A"));
        when(pointService.add("user-1", 2, PointConstants.POINT_TYPE_VOTE)).thenReturn(new PointRecordEntity());
        when(receiptDao.complete(anyLong(), anyString(), anyString(), eq(true), eq(2), anyString(), anyLong()))
                .thenReturn(1);

        WxDailyVoteAnswerResult result = service.answer(command(MESSAGE_KEY, "A"));

        assertTrue(result.firstProcessing());
        assertTrue(result.correct());
        assertEquals(2, result.pointAwarded());
        assertEquals("恭喜你，答案正确，增加2积分！", result.replyContent());
        assertEquals("PROCESSING", claimed.get().getStatus());
        verify(voteService).answerDaily(337L, "user-1", "A");
        verify(pointService).add("user-1", 2, PointConstants.POINT_TYPE_VOTE);
        verify(receiptDao).complete(eq(91L), anyString(), anyString(), eq(true), eq(2),
                eq("恭喜你，答案正确，增加2积分！"), anyLong());
    }

    @Test
    void persistsIncorrectReplyWithoutPointMutation() {
        arrangeOwnedClaim();
        when(voteService.answerDaily(337L, "user-1", "B"))
                .thenReturn(new MatDailyVoteAnswerResult(337L, false, 2, "A"));
        when(receiptDao.complete(anyLong(), anyString(), anyString(), eq(false), eq(0), anyString(), anyLong()))
                .thenReturn(1);

        WxDailyVoteAnswerResult result = service.answer(command(MESSAGE_KEY, "B"));

        assertFalse(result.correct());
        assertEquals(0, result.pointAwarded());
        assertEquals("很遗憾，回答错误，正确答案是A,下次继续努力！", result.replyContent());
        verifyNoInteractions(pointService);
    }

    @Test
    void durableCanonicalMessageReplayUsesPersistedQuestionWhenPointerAdvanced() {
        WxDailyVoteMessageReceiptEntity messageReceipt = messageReceipt(MESSAGE_KEY, 337L, "A", 91L);
        WxDailyVoteReceiptEntity receipt = completed(MESSAGE_KEY, "A");
        when(receiptDao.selectMessage(APPID, MESSAGE_KEY)).thenReturn(messageReceipt);
        when(receiptDao.lockById(91L)).thenReturn(receipt);

        WxDailyVoteAnswerResult result = service.answer(command(MESSAGE_KEY, 338L, "A"));

        assertFalse(result.firstProcessing());
        assertEquals(337L, result.questionId());
        assertEquals(receipt.getReplyContent(), result.replyContent());
        verify(receiptDao, never()).claim(any());
        verify(receiptDao, never()).lockConflicts(anyString(), anyString(), anyString(), anyLong());
        verify(receiptDao, never()).claimMessage(any());
        verify(receiptDao, never()).complete(anyLong(), anyString(), anyString(), anyBoolean(), anyInt(),
                anyString(), anyLong());
        verifyNoInteractions(voteService, pointService);
    }

    @Test
    void durableMessageAliasReplaysCanonicalResultWithDifferentCanonicalMessage() {
        WxDailyVoteMessageReceiptEntity messageReceipt = messageReceipt(OTHER_MESSAGE_KEY, 337L, "B", 91L);
        WxDailyVoteReceiptEntity receipt = completed(MESSAGE_KEY, "A");
        when(receiptDao.selectMessage(APPID, OTHER_MESSAGE_KEY)).thenReturn(messageReceipt);
        when(receiptDao.lockById(91L)).thenReturn(receipt);

        WxDailyVoteAnswerResult result = service.answer(command(OTHER_MESSAGE_KEY, 338L, "B"));

        assertFalse(result.firstProcessing());
        assertEquals(337L, result.questionId());
        assertEquals(receipt.getReplyContent(), result.replyContent());
        verify(receiptDao, never()).claim(any());
        verify(receiptDao, never()).lockConflicts(anyString(), anyString(), anyString(), anyLong());
        verify(receiptDao, never()).claimMessage(any());
        verify(receiptDao, never()).complete(anyLong(), anyString(), anyString(), anyBoolean(), anyInt(),
                anyString(), anyLong());
        verifyNoInteractions(voteService, pointService);
    }

    @Test
    void durableMessageReplayRejectsPayloadTamperingBeforeCanonicalOrBusinessAccess() {
        WxDailyVoteMessageReceiptEntity messageReceipt = messageReceipt(MESSAGE_KEY, 337L, "A", 91L);
        when(receiptDao.selectMessage(APPID, MESSAGE_KEY)).thenReturn(messageReceipt);

        assertThrows(IllegalStateException.class,
                () -> service.answer(command(MESSAGE_KEY, 338L, "B")));

        verify(receiptDao, never()).lockById(anyLong());
        verify(receiptDao, never()).claim(any());
        verify(receiptDao, never()).claimMessage(any());
        verify(receiptDao, never()).complete(anyLong(), anyString(), anyString(), anyBoolean(), anyInt(),
                anyString(), anyLong());
        verifyNoInteractions(voteService, pointService);
    }

    @Test
    void canonicalConflictFallbackUsesPersistedQuestionWhenAliasPrecheckMissed() {
        WxDailyVoteReceiptEntity receipt = completed(MESSAGE_KEY, "A");
        when(receiptDao.lockConflicts(APPID, MESSAGE_KEY, USER_KEY, 338L)).thenReturn(List.of(receipt));

        WxDailyVoteAnswerResult result = service.answer(command(MESSAGE_KEY, 338L, "A"));

        assertFalse(result.firstProcessing());
        assertEquals(337L, result.questionId());
        assertEquals(337L, claimedMessage.get().getQuestionId().longValue());
        assertEquals(WxDailyVoteKeys.requestFingerprint(APPID, USER_KEY, 337L, "A"),
                claimedMessage.get().getRequestFingerprint());
        verify(receiptDao).claim(any());
        verify(receiptDao).claimMessage(any());
        verify(receiptDao, never()).complete(anyLong(), anyString(), anyString(), anyBoolean(), anyInt(),
                anyString(), anyLong());
        verifyNoInteractions(voteService, pointService);
    }

    @Test
    void canonicalConflictFallbackRejectsPayloadTamperingWithoutBusinessSideEffects() {
        WxDailyVoteReceiptEntity receipt = completed(MESSAGE_KEY, "A");
        when(receiptDao.lockConflicts(APPID, MESSAGE_KEY, USER_KEY, 338L)).thenReturn(List.of(receipt));

        assertThrows(IllegalStateException.class,
                () -> service.answer(command(MESSAGE_KEY, 338L, "B")));

        verify(receiptDao, never()).claimMessage(any());
        verify(receiptDao, never()).complete(anyLong(), anyString(), anyString(), anyBoolean(), anyInt(),
                anyString(), anyLong());
        verifyNoInteractions(voteService, pointService);
    }

    @Test
    void sameMessageReplayReturnsPersistedResultWithoutSideEffects() {
        WxDailyVoteReceiptEntity receipt = completed(MESSAGE_KEY, "A");
        when(receiptDao.lockConflicts(APPID, MESSAGE_KEY, USER_KEY, 337L)).thenReturn(List.of(receipt));

        WxDailyVoteAnswerResult result = service.answer(command(MESSAGE_KEY, "A"));

        assertFalse(result.firstProcessing());
        assertEquals(receipt.getReplyContent(), result.replyContent());
        verifyNoInteractions(voteService, pointService);
        verify(receiptDao, never()).complete(anyLong(), anyString(), anyString(), anyBoolean(), anyInt(),
                anyString(), anyLong());
    }

    @Test
    void sameUserQuestionWithDifferentMessageReplaysFirstOutcome() {
        WxDailyVoteReceiptEntity receipt = completed(MESSAGE_KEY, "A");
        when(receiptDao.lockConflicts(APPID, OTHER_MESSAGE_KEY, USER_KEY, 337L)).thenReturn(List.of(receipt));

        WxDailyVoteAnswerResult result = service.answer(command(OTHER_MESSAGE_KEY, "B"));

        assertFalse(result.firstProcessing());
        assertTrue(result.correct());
        assertEquals(91L, claimedMessage.get().getReceiptId());
        assertEquals(OTHER_MESSAGE_KEY, claimedMessage.get().getMessageKey());
        assertEquals(WxDailyVoteKeys.requestFingerprint(APPID, USER_KEY, 337L, "B"),
                claimedMessage.get().getRequestFingerprint());
        verifyNoInteractions(voteService, pointService);
    }

    @Test
    void transactionFailureLeavesReceiptIncompleteForRollback() {
        arrangeOwnedClaim();
        when(voteService.answerDaily(337L, "user-1", "A"))
                .thenThrow(new IllegalStateException("fixed failure"));

        assertThrows(IllegalStateException.class, () -> service.answer(command(MESSAGE_KEY, "A")));

        verifyNoInteractions(pointService);
        verify(receiptDao, never()).complete(anyLong(), anyString(), anyString(), anyBoolean(), anyInt(),
                anyString(), anyLong());
    }

    @Test
    void pointFailureDoesNotCompleteReceipt() {
        arrangeOwnedClaim();
        when(voteService.answerDaily(337L, "user-1", "A"))
                .thenReturn(new MatDailyVoteAnswerResult(337L, true, 2, "A"));
        when(pointService.add("user-1", 2, PointConstants.POINT_TYPE_VOTE))
                .thenThrow(new IllegalStateException("point record failed"));

        assertThrows(IllegalStateException.class, () -> service.answer(command(MESSAGE_KEY, "A")));

        verify(receiptDao, never()).complete(anyLong(), anyString(), anyString(), anyBoolean(), anyInt(),
                anyString(), anyLong());
    }

    @Test
    void inFlightConflictingReceiptFailsClosedWithoutBusinessSideEffects() {
        WxDailyVoteReceiptEntity receipt = completed(MESSAGE_KEY, "A")
                .setStatus("PROCESSING").setCorrect(null).setPointAwarded(null).setReplyContent(null);
        when(receiptDao.lockConflicts(APPID, MESSAGE_KEY, USER_KEY, 337L)).thenReturn(List.of(receipt));

        assertThrows(IllegalStateException.class, () -> service.answer(command(MESSAGE_KEY, "A")));

        verifyNoInteractions(voteService, pointService);
    }

    @Test
    void conflictingReceiptRowsFailClosed() {
        WxDailyVoteReceiptEntity first = completed(MESSAGE_KEY, "A");
        WxDailyVoteReceiptEntity second = completed(OTHER_MESSAGE_KEY, "A").setId(92L);
        when(receiptDao.lockConflicts(APPID, MESSAGE_KEY, USER_KEY, 337L)).thenReturn(List.of(first, second));

        assertThrows(IllegalStateException.class, () -> service.answer(command(MESSAGE_KEY, "A")));
        verify(receiptDao, never()).claimMessage(any());
        verify(receiptDao, never()).complete(anyLong(), anyString(), anyString(), anyBoolean(), anyInt(),
                anyString(), anyLong());
        verifyNoInteractions(voteService, pointService);
    }

    @Test
    void replayLookupRejectsPayloadMismatch() {
        WxDailyVoteReceiptEntity receipt = completed(MESSAGE_KEY, "A");
        when(receiptDao.selectCompletedByMessage(APPID, MESSAGE_KEY)).thenReturn(receipt);

        assertThrows(IllegalStateException.class, () -> service.findReplay(
                new WxDailyVoteReplayQuery(APPID, MESSAGE_KEY, USER_KEY, "B")));
    }

    @Test
    void replayLookupReturnsPersistedReplyWhenCacheIsUnavailable() {
        WxDailyVoteReceiptEntity receipt = completed(MESSAGE_KEY, "A");
        when(receiptDao.selectCompletedByMessage(APPID, MESSAGE_KEY)).thenReturn(receipt);

        Optional<WxDailyVoteAnswerResult> replay = service.findReplay(
                new WxDailyVoteReplayQuery(APPID, MESSAGE_KEY, USER_KEY, "A"));

        assertTrue(replay.isPresent());
        assertFalse(replay.orElseThrow().firstProcessing());
        verifyNoInteractions(voteService, pointService);
    }

    private AtomicReference<WxDailyVoteReceiptEntity> arrangeOwnedClaim() {
        AtomicReference<WxDailyVoteReceiptEntity> claimed = new AtomicReference<>();
        when(receiptDao.claim(any())).thenAnswer(invocation -> {
            WxDailyVoteReceiptEntity receipt = invocation.getArgument(0);
            receipt.setId(91L);
            claimed.set(receipt);
            return 1;
        });
        when(receiptDao.lockConflicts(eq(APPID), anyString(), eq(USER_KEY), eq(337L)))
                .thenAnswer(invocation -> List.of(claimed.get()));
        return claimed;
    }

    private WxDailyVoteAnswerCommand command(String messageKey, String answer) {
        return command(messageKey, 337L, answer);
    }

    private WxDailyVoteAnswerCommand command(String messageKey, long questionId, String answer) {
        return new WxDailyVoteAnswerCommand(APPID, messageKey, USER_KEY, "user-1", questionId, answer);
    }

    private WxDailyVoteMessageReceiptEntity messageReceipt(String messageKey, long questionId,
                                                           String answer, long receiptId) {
        return new WxDailyVoteMessageReceiptEntity()
                .setId(191L)
                .setReceiptId(receiptId)
                .setAppid(APPID)
                .setMessageKey(messageKey)
                .setUserKey(USER_KEY)
                .setRequestFingerprint(WxDailyVoteKeys.requestFingerprint(
                        APPID, USER_KEY, questionId, answer))
                .setQuestionId(questionId);
    }

    private WxDailyVoteReceiptEntity completed(String messageKey, String answer) {
        String fingerprint = WxDailyVoteKeys.requestFingerprint(APPID, USER_KEY, 337L, answer);
        return new WxDailyVoteReceiptEntity()
                .setId(91L)
                .setAppid(APPID)
                .setMessageKey(messageKey)
                .setUserKey(USER_KEY)
                .setRequestFingerprint(fingerprint)
                .setQuestionId(337L)
                .setProcessorToken("other-processor")
                .setStatus("COMPLETED")
                .setCorrect(1)
                .setPointAwarded(2)
                .setReplyContent("恭喜你，答案正确，增加2积分！");
    }
}
