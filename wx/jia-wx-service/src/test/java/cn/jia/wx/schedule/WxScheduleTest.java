package cn.jia.wx.schedule;

import cn.jia.core.redis.RedisService;
import cn.jia.kefu.service.KefuMsgSubscribeService;
import cn.jia.kefu.service.KefuMsgTypeService;
import cn.jia.kefu.service.KefuService;
import cn.jia.mat.service.MatPhraseService;
import cn.jia.mat.service.MatVoteService;
import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WxScheduleTest extends BaseMockTest {
    @Mock
    private RedisService redisService;
    @Mock
    private MatVoteService voteService;
    @Mock
    private MatPhraseService phraseService;
    @Mock
    private KefuService kefuService;
    @Mock
    private KefuMsgSubscribeService kefuMsgSubscribeService;
    @Mock
    private KefuMsgTypeService kefuMsgTypeService;
    @InjectMocks
    private WxSchedule wxSchedule;

    @Test
    void skipsDuplicateScheduleExecution() {
        when(redisService.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        wxSchedule.sendVote();

        verify(redisService).setIfAbsent(anyString(), anyString(), any(Duration.class));
        verifyNoInteractions(kefuMsgSubscribeService, voteService, phraseService, kefuService, kefuMsgTypeService);
    }
}
