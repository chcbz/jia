package cn.jia.wx.api;

import cn.jia.core.redis.RedisService;
import cn.jia.test.BaseMockTest;
import cn.jia.wx.dailyvote.WxDailyVoteAnswerResult;
import cn.jia.wx.entity.MpInfoEntity;
import cn.jia.wx.entity.MpUserEntity;
import cn.jia.wx.service.MpInfoService;
import cn.jia.wx.service.MpUserService;
import cn.jia.wx.service.WxDailyVoteService;
import jakarta.servlet.http.HttpServletRequest;
import me.chanjar.weixin.mp.api.WxMpService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
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
    private ThreadPoolTaskExecutor taskExecutor;
    private WxMpService wxMpService;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        controller = new WxMpController();
        mpInfoService = mock(MpInfoService.class);
        mpUserService = mock(MpUserService.class);
        redisService = mock(RedisService.class);
        dailyVoteService = mock(WxDailyVoteService.class);
        taskExecutor = mock(ThreadPoolTaskExecutor.class);
        wxMpService = mock(WxMpService.class, RETURNS_DEEP_STUBS);
        request = mock(HttpServletRequest.class);

        ReflectionTestUtils.setField(controller, "mpInfoService", mpInfoService);
        ReflectionTestUtils.setField(controller, "mpUserService", mpUserService);
        ReflectionTestUtils.setField(controller, "redisService", redisService);
        ReflectionTestUtils.setField(controller, "dailyVoteService", dailyVoteService);
        ReflectionTestUtils.setField(controller, "taskExecutor", taskExecutor);

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
        when(mpInfoService.findByKey(APPID)).thenReturn(new MpInfoEntity()
                .setAppid(APPID).setOriginal(ORIGINAL).setClientId("client-1").setName("公众号"));
        when(mpUserService.findByAppIdAndOpenId(APPID, OPENID)).thenReturn(new MpUserEntity()
                .setAppid(APPID).setOpenId(OPENID).setJiacn("user-1"));
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
