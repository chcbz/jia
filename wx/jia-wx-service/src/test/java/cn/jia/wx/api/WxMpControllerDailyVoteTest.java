package cn.jia.wx.api;

import cn.jia.core.redis.RedisService;
import cn.jia.mat.entity.MatVoteItemEntity;
import cn.jia.mat.entity.MatVoteQuestionVO;
import cn.jia.mat.service.MatVoteService;
import cn.jia.test.BaseMockTest;
import cn.jia.wx.dailyvote.WxDailyVoteAnswerResult;
import cn.jia.wx.entity.MpInfoEntity;
import cn.jia.wx.entity.MpUserEntity;
import cn.jia.wx.service.MpInfoService;
import cn.jia.wx.service.MpUserService;
import cn.jia.wx.service.WxDailyVoteService;
import jakarta.servlet.http.HttpServletRequest;
import me.chanjar.weixin.mp.api.WxMpService;
import me.chanjar.weixin.mp.bean.kefu.WxMpKefuMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.SQLException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.*;

class WxMpControllerDailyVoteTest extends BaseMockTest {

    private static final String APPID = "wx-test-app";
    private static final String ORIGINAL = "gh_test";
    private static final String OPENID = "openid-secret-value";
    private static final String XML = "<xml>"
            + "<ToUserName><![CDATA[" + ORIGINAL + "]]></ToUserName>"
            + "<FromUserName><![CDATA[" + OPENID + "]]></FromUserName>"
            + "<CreateTime>1710000000</CreateTime>"
            + "<MsgType><![CDATA[text]]></MsgType>"
            + "<Content><![CDATA[A]]></Content>"
            + "<MsgId>123456789</MsgId>"
            + "</xml>";

    private WxMpController controller;
    private MpInfoService mpInfoService;
    private MpUserService mpUserService;
    private RedisService redisService;
    private WxDailyVoteService dailyVoteService;
    private MatVoteService voteService;
    private ThreadPoolTaskExecutor taskExecutor;
    private ThreadPoolTaskExecutor dailyVoteQuestionExecutor;
    private WxMpService wxMpService;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        controller = new WxMpController();
        mpInfoService = mock(MpInfoService.class);
        mpUserService = mock(MpUserService.class);
        redisService = mock(RedisService.class);
        dailyVoteService = mock(WxDailyVoteService.class);
        voteService = mock(MatVoteService.class);
        taskExecutor = mock(ThreadPoolTaskExecutor.class);
        dailyVoteQuestionExecutor = mock(ThreadPoolTaskExecutor.class);
        wxMpService = mock(WxMpService.class, RETURNS_DEEP_STUBS);
        request = mock(HttpServletRequest.class);

        ReflectionTestUtils.setField(controller, "mpInfoService", mpInfoService);
        ReflectionTestUtils.setField(controller, "mpUserService", mpUserService);
        ReflectionTestUtils.setField(controller, "redisService", redisService);
        ReflectionTestUtils.setField(controller, "dailyVoteService", dailyVoteService);
        ReflectionTestUtils.setField(controller, "voteService", voteService);
        ReflectionTestUtils.setField(controller, "taskExecutor", taskExecutor);
        ReflectionTestUtils.setField(controller, "dailyVoteQuestionExecutor", dailyVoteQuestionExecutor);

        when(mpInfoService.findWxMpService(request)).thenReturn(wxMpService);
        when(wxMpService.getWxMpConfigStorage().getAppId()).thenReturn(APPID);
        when(request.getParameter("signature")).thenReturn("valid-signature");
        when(request.getParameter("timestamp")).thenReturn("1710000000");
        when(request.getParameter("nonce")).thenReturn("nonce");
    }

    private void stubValidSignature() {
        when(wxMpService.checkSignature("1710000000", "nonce", "valid-signature")).thenReturn(true);
    }

    private void stubAccountAndIdentity() {
        MpInfoEntity account = new MpInfoEntity()
                .setAppid(APPID).setOriginal(ORIGINAL).setClientId("client-1").setName("公众号");
        when(mpInfoService.findCachedByKey(APPID)).thenReturn(account);
        when(mpInfoService.findByKey(APPID)).thenReturn(account);
        when(mpUserService.findByAppIdAndOpenId(APPID, OPENID)).thenReturn(new MpUserEntity()
                .setId(17L).setAppid(APPID).setOpenId(OPENID).setJiacn("user-1"));
    }

    private void stubValidDailyVoteRequest() {
        stubValidSignature();
        stubAccountAndIdentity();
    }

    @Test
    void invalidSignatureFailsBeforeXmlParsingOrIdentityLookup() throws Exception {
        when(wxMpService.checkSignature(anyString(), anyString(), anyString())).thenReturn(false);

        Object response = controller.receiveMsg("not-xml", request);

        assertEquals("", response);
        verify(mpInfoService, never()).findByKey(anyString());
        verifyNoInteractions(mpUserService, dailyVoteService, redisService);
    }

    @Test
    void questionCommandAcknowledgesImmediatelyThenDeliversTheQuestionThroughKefu() throws Exception {
        stubValidDailyVoteRequest();
        MatVoteQuestionVO question = new MatVoteQuestionVO();
        question.setId(337L);
        question.setTitle("水浒第一题");
        MatVoteItemEntity item = new MatVoteItemEntity();
        item.setOpt("A");
        item.setContent("及时雨宋江");
        question.setItems(java.util.List.of(item));
        when(voteService.findOneQuestion("user-1")).thenReturn(question);
        when(wxMpService.getKefuService().sendKefuMessage(any(WxMpKefuMessage.class))).thenReturn(true);

        String questionXml = XML.replace("<![CDATA[A]]>", "<![CDATA[我要做题 ]]>");
        String response = (String) controller.receiveMsg(questionXml, request);

        assertTrue(response.contains("正在为你准备题目，将继续发送到本会话。"));
        verify(dailyVoteQuestionExecutor).execute(any(Runnable.class));
        verifyNoInteractions(dailyVoteService);
        verify(voteService, never()).findOneQuestion(anyString());
        verify(redisService, never()).get("vote_user-1");

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(dailyVoteQuestionExecutor).execute(task.capture());
        task.getValue().run();

        ArgumentCaptor<WxMpKefuMessage> delivery = ArgumentCaptor.forClass(WxMpKefuMessage.class);
        verify(wxMpService.getKefuService()).sendKefuMessage(delivery.capture());
        assertEquals(OPENID, delivery.getValue().getToUser());
        assertEquals("水浒第一题\n\nA 及时雨宋江\n", delivery.getValue().getContent());
        verify(voteService).findOneQuestion("user-1");
        verify(redisService).set("vote_user-1", "337", 2L, java.util.concurrent.TimeUnit.HOURS);
        verify(mpUserService).touchLastActive(eq(17L), anyLong());
    }

    @Test
    void questionDeliveryContinuesWhenLastActivePersistenceFails() throws Exception {
        stubValidDailyVoteRequest();
        MatVoteQuestionVO question = new MatVoteQuestionVO();
        question.setId(337L);
        question.setTitle("水浒第一题");
        question.setItems(java.util.List.of(new MatVoteItemEntity().setOpt("A").setContent("及时雨宋江")));
        when(voteService.findOneQuestion("user-1")).thenReturn(question);
        when(wxMpService.getKefuService().sendKefuMessage(any(WxMpKefuMessage.class))).thenReturn(true);
        doThrow(new IllegalStateException("last-active column unavailable"))
                .when(mpUserService).touchLastActive(eq(17L), anyLong());

        controller.receiveMsg(XML.replace("<![CDATA[A]]>", "<![CDATA[我要做题]]>"), request);
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(dailyVoteQuestionExecutor).execute(task.capture());
        task.getValue().run();

        verify(wxMpService.getKefuService()).sendKefuMessage(any(WxMpKefuMessage.class));
        verify(redisService).set("active_mp_user_" + OPENID, "Y", java.time.Duration.ofDays(2));
        verify(redisService).set("vote_user-1", "337", 2L, java.util.concurrent.TimeUnit.HOURS);
    }

    @Test
    void questionCommandNeverRunsQuestionLookupOnTheCallbackThreadWhenDispatchFails() throws Exception {
        stubValidDailyVoteRequest();
        doThrow(new IllegalStateException("executor unavailable"))
                .when(dailyVoteQuestionExecutor).execute(any(Runnable.class));

        String questionXml = XML.replace("<![CDATA[A]]>", "<![CDATA[我要做题]]>");
        String response = (String) controller.receiveMsg(questionXml, request);

        assertTrue(response.contains("正在为你准备题目，将继续发送到本会话。"));
        verify(voteService, never()).findOneQuestion(anyString());
        verifyNoInteractions(dailyVoteService);
    }

    @Test
    void committedAnswerSurvivesCacheCleanupFailureAndUsesCompareDelete() throws Exception {
        stubValidDailyVoteRequest();
        when(dailyVoteService.findReplay(any())).thenReturn(Optional.empty());
        when(redisService.get("vote_user-1")).thenReturn("337");
        when(dailyVoteService.answer(any())).thenReturn(new WxDailyVoteAnswerResult(
                91L, 337L, "恭喜你，答案正确，增加2积分！", true, 2, true));
        when(redisService.deleteIfValueEquals("vote_user-1", "337"))
                .thenThrow(new IllegalStateException("cache unavailable"));

        String response = (String) controller.receiveMsg(XML, request);

        assertTrue(response.contains("恭喜你，答案正确，增加2积分！"));
        verify(redisService).deleteIfValueEquals("vote_user-1", "337");
        verify(redisService, never()).delete("vote_user-1");
        verify(taskExecutor).execute(any(Runnable.class));
    }

    @Test
    void lateTransactionalReplayClearsPersistedQuestionAndPreservesNewPointer() throws Exception {
        stubValidDailyVoteRequest();
        when(dailyVoteService.findReplay(any())).thenReturn(Optional.empty());
        when(redisService.get("vote_user-1")).thenReturn("338");
        when(dailyVoteService.answer(any())).thenReturn(new WxDailyVoteAnswerResult(
                91L, 337L, "恭喜你，答案正确，增加2积分！", true, 2, false));

        String response = (String) controller.receiveMsg(XML, request);

        assertTrue(response.contains("恭喜你，答案正确，增加2积分！"));
        verify(redisService).deleteIfValueEquals("vote_user-1", "337");
        verify(redisService, never()).deleteIfValueEquals("vote_user-1", "338");
        verify(taskExecutor, never()).execute(any(Runnable.class));
    }

    @Test
    void answerFailureUsesOneFreshReplayAfterRollbackAndNeverRedpacksReplay() throws Exception {
        stubValidDailyVoteRequest();
        IllegalStateException original = new IllegalStateException("transaction rolled back");
        when(dailyVoteService.findReplay(any())).thenReturn(
                Optional.empty(),
                Optional.of(new WxDailyVoteAnswerResult(
                        91L, 337L, "恭喜你，答案正确，增加2积分！", true, 2, true)));
        when(redisService.get("vote_user-1")).thenReturn("338");
        when(dailyVoteService.answer(any())).thenThrow(original);

        String response = (String) controller.receiveMsg(XML, request);

        assertTrue(response.contains("恭喜你，答案正确，增加2积分！"));
        verify(dailyVoteService, times(2)).findReplay(any());
        verify(dailyVoteService).answer(any());
        verify(redisService).deleteIfValueEquals("vote_user-1", "337");
        verify(redisService, never()).deleteIfValueEquals("vote_user-1", "338");
        verify(taskExecutor, never()).execute(any(Runnable.class));
    }

    @Test
    void transactionFailureWithNoFreshReplayRethrowsOriginalObject() {
        stubValidDailyVoteRequest();
        IllegalStateException original = new IllegalStateException("database unavailable");
        when(dailyVoteService.findReplay(any())).thenReturn(Optional.empty());
        when(redisService.get("vote_user-1")).thenReturn("337");
        when(dailyVoteService.answer(any())).thenThrow(original);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> controller.receiveMsg(XML, request));

        assertSame(original, thrown);
        verify(dailyVoteService, times(2)).findReplay(any());
        verify(redisService, never()).deleteIfValueEquals(anyString(), anyString());
        verify(taskExecutor, never()).execute(any(Runnable.class));
    }

    @Test
    void replayFailureIsSuppressedAndOriginalAnswerFailureIsRethrown() {
        stubValidDailyVoteRequest();
        IllegalStateException original = new IllegalStateException("transaction rolled back");
        IllegalArgumentException replayFailure = new IllegalArgumentException("payload mismatch");
        when(dailyVoteService.findReplay(any()))
                .thenReturn(Optional.empty())
                .thenThrow(replayFailure);
        when(redisService.get("vote_user-1")).thenReturn("338");
        when(dailyVoteService.answer(any())).thenThrow(original);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> controller.receiveMsg(XML, request));

        assertSame(original, thrown);
        assertArrayEquals(new Throwable[]{replayFailure}, thrown.getSuppressed());
        verify(dailyVoteService, times(2)).findReplay(any());
        verify(redisService, never()).deleteIfValueEquals(anyString(), anyString());
        verify(taskExecutor, never()).execute(any(Runnable.class));
    }


    @Test
    void missingReceiptSchemaKeepsTheCurrentQuestionAndReturnsRecoverableReply() throws Exception {
        stubValidDailyVoteRequest();
        when(dailyVoteService.findReplay(any())).thenReturn(Optional.empty());
        when(redisService.get("vote_user-1")).thenReturn("337");
        when(dailyVoteService.answer(any())).thenThrow(new RuntimeException("mapper failed",
                new SQLException("Table 'jia.wx_daily_vote_message_receipt' doesn't exist", "42S02", 1146)));

        String response = (String) controller.receiveMsg(XML, request);

        assertTrue(response.contains("每日投票服务正在恢复，请稍后重试，当前题目已保留。"));
        verify(dailyVoteService).answer(any());
        verify(dailyVoteService, times(1)).findReplay(any());
        verify(redisService, never()).deleteIfValueEquals(anyString(), anyString());
        verify(taskExecutor, never()).execute(any(Runnable.class));
    }

    @Test
    void incorrectAnswerNeverDispatchesRedpack() throws Exception {
        stubValidDailyVoteRequest();
        when(dailyVoteService.findReplay(any())).thenReturn(Optional.empty());
        when(redisService.get("vote_user-1")).thenReturn("337");
        when(dailyVoteService.answer(any())).thenReturn(new WxDailyVoteAnswerResult(
                92L, 337L, "很遗憾，回答错误，正确答案是A,下次继续努力！", false, 0, true));

        String response = (String) controller.receiveMsg(XML, request);

        assertTrue(response.contains("很遗憾，回答错误，正确答案是A,下次继续努力！"));
        verify(redisService).deleteIfValueEquals("vote_user-1", "337");
        verify(taskExecutor, never()).execute(any(Runnable.class));
    }

    @Test
    void originalAccountMismatchFailsBeforeIdentityOrDailyVoteSideEffects() throws Exception {
        stubValidSignature();
        when(mpInfoService.findByKey(APPID)).thenReturn(new MpInfoEntity()
                .setAppid(APPID).setOriginal("gh_other").setClientId("client-1"));

        assertEquals("", controller.receiveMsg(XML, request));

        verifyNoInteractions(mpUserService, dailyVoteService, redisService);
    }

    @Test
    void replayReturnsPersistedReplyWithoutRedisReadOrRedpackOrDeletingANewQuestion() throws Exception {
        stubValidDailyVoteRequest();
        when(dailyVoteService.findReplay(any())).thenReturn(Optional.of(new WxDailyVoteAnswerResult(
                91L, 337L, "恭喜你，答案正确，增加2积分！", true, 2, false)));
        when(redisService.deleteIfValueEquals("vote_user-1", "337")).thenReturn(false);

        String response = (String) controller.receiveMsg(XML, request);

        assertTrue(response.contains("恭喜你，答案正确，增加2积分！"));
        verify(redisService, never()).get("vote_user-1");
        verify(dailyVoteService, never()).answer(any());
        verify(redisService).deleteIfValueEquals("vote_user-1", "337");
        verify(redisService, never()).delete("vote_user-1");
        verify(taskExecutor, never()).execute(any(Runnable.class));
    }
}
