package cn.jia.wx.schedule;

import cn.jia.base.service.DictService;
import cn.jia.mat.entity.MatVoteItemEntity;
import cn.jia.mat.entity.MatVoteQuestionVO;
import cn.jia.mat.service.MatVoteService;
import cn.jia.task.common.TaskConstants;
import cn.jia.test.BaseDbUnitTest;
import cn.jia.user.entity.UserEntity;
import cn.jia.user.service.UserService;
import cn.jia.wx.service.MpInfoService;
import org.springframework.test.context.jdbc.Sql;
import me.chanjar.weixin.mp.bean.template.WxMpTemplateData;
import me.chanjar.weixin.mp.bean.template.WxMpTemplateMessage;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class WxScheduleTest extends BaseDbUnitTest {

    @Autowired
    private WxSchedule wxSchedule;
    @Autowired
    private UserService userService;
    @Autowired
    private MatVoteService matVoteService;
    @Autowired
    private MpInfoService mpInfoService;
    @Autowired
    private DictService dictService;
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Test
    @Sql(scripts = "classpath:testObject/wx/wx_schedule_init.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
    void sendVote() {
        wxSchedule.sendVote();
        redisTemplate.opsForValue().set("active_mp_user_oH2zD1PUPvspicVak69uB4wDaFLg", "Y", Duration.ofDays(2));
        wxSchedule.sendVote();
    }

    @Test
    @Disabled
    void sendTemplateMessage() throws Exception {
        UserEntity user = userService.findByJiacn("oH2zD1PUPvspicVak69uB4wDaFLg");
        String wxAppId = dictService.getValue(TaskConstants.DICT_TYPE_TASK_CONFIG, TaskConstants.TASK_CONFIG_WX_APP_ID);
        MatVoteQuestionVO question = matVoteService.findOneQuestion(user.getJiacn());
        List<WxMpTemplateData> data = new ArrayList<>();
        WxMpTemplateData keyword0 = new WxMpTemplateData();
        keyword0.setName("first");
        StringBuilder content = new StringBuilder("每天答题时间(两小时有效)\r\n\r\n").append(question.getTitle()).append("\r\n");
        for (MatVoteItemEntity item : question.getItems()) {
            content.append("\r\n").append(item.getOpt()).append(". ").append(item.getContent());
        }
        keyword0.setValue(content.toString());
        keyword0.setColor("#173177");
        data.add(keyword0);
        WxMpTemplateData keyword1 = new WxMpTemplateData();
        keyword1.setName("keyword1");
        keyword1.setValue("保险知识普及");
        keyword1.setColor("#173177");
        data.add(keyword1);
        WxMpTemplateData keyword2 = new WxMpTemplateData();
        keyword2.setName("keyword2");
        keyword2.setValue("请回复正确答案，退订回复TD");
        keyword2.setColor("#173177");
        data.add(keyword2);
        WxMpTemplateData remark = new WxMpTemplateData();
        remark.setName("remark");
        remark.setValue("");
        remark.setColor("#173177");
        data.add(remark);

        WxMpTemplateMessage message = new WxMpTemplateMessage();
        message.setToUser(user.getOpenid());
        String templateId = dictService.getValue(TaskConstants.DICT_TYPE_TASK_CONFIG, TaskConstants.TASK_CONFIG_WX_MSG_TEMPLATE_ID);
        message.setTemplateId(templateId);
        message.setData(data);
        String baseUrl = dictService.getValue(TaskConstants.DICT_TYPE_TASK_CONFIG, TaskConstants.TASK_CONFIG_NOTIFY_URL);
        message.setUrl(baseUrl + "/vote");
        mpInfoService.findWxMpService(wxAppId).getTemplateMsgService().sendTemplateMsg(message);
    }
}