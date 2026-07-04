package cn.jia.chat.tool;

import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import cn.jia.kefu.entity.KefuFaqEntity;
import cn.jia.kefu.entity.KefuMessageEntity;
import cn.jia.kefu.entity.KefuMsgSubscribeEntity;
import cn.jia.kefu.entity.KefuMsgTypeCode;
import cn.jia.kefu.entity.KefuMsgTypeEntity;
import cn.jia.kefu.service.KefuFaqService;
import cn.jia.kefu.service.KefuMessageService;
import cn.jia.kefu.service.KefuMsgSubscribeService;
import cn.jia.kefu.service.KefuMsgTypeService;
import cn.jia.kefu.service.KefuService;
import com.github.pagehelper.PageInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class KefuTools {

    private final KefuService kefuService;
    private final KefuFaqService kefuFaqService;
    private final KefuMessageService kefuMessageService;
    private final KefuMsgTypeService kefuMsgTypeService;
    private final KefuMsgSubscribeService kefuMsgSubscribeService;

    // ========== FAQ ==========

    @Tool(name = "listFaqs", description = "查询常见问题FAQ列表")
    public Map<String, Object> listFaqs(
            @ToolParam(description = "搜索关键词，匹配问题标题") String keyword,
            @ToolParam(description = "页码，默认1") Integer pageNum,
            @ToolParam(description = "每页条数，默认10") Integer pageSize) {
        KefuFaqEntity query = new KefuFaqEntity();
        if (keyword != null && !keyword.isEmpty()) {
            query.setTitle(keyword);
        }
        int page = pageNum != null ? pageNum : 1;
        int size = pageSize != null ? pageSize : 10;
        PageInfo<KefuFaqEntity> pageInfo = kefuFaqService.findPage(query, page, size);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", pageInfo.getTotal());
        result.put("pageNum", pageInfo.getPageNum());
        result.put("pageSize", pageInfo.getPageSize());
        result.put("list", pageInfo.getList());
        return result;
    }

    @Tool(name = "getFaqDetail", description = "查看FAQ详情")
    public KefuFaqEntity getFaqDetail(
            @ToolParam(description = "FAQ ID") Long id) {
        return kefuFaqService.get(id);
    }

    // ========== 消息管理 ==========

    @Tool(name = "listKefuMessages", description = "查询客服消息记录")
    public Map<String, Object> listKefuMessages(
            @ToolParam(description = "页码，默认1") Integer pageNum,
            @ToolParam(description = "每页条数，默认10") Integer pageSize) {
        KefuMessageEntity query = new KefuMessageEntity();
        int page = pageNum != null ? pageNum : 1;
        int size = pageSize != null ? pageSize : 10;
        PageInfo<KefuMessageEntity> pageInfo = kefuMessageService.findPage(query, page, size);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", pageInfo.getTotal());
        result.put("pageNum", pageInfo.getPageNum());
        result.put("pageSize", pageInfo.getPageSize());
        result.put("list", pageInfo.getList());
        return result;
    }

    @Tool(name = "getKefuMessageDetail", description = "查看客服消息详情")
    public KefuMessageEntity getKefuMessageDetail(
            @ToolParam(description = "消息ID") Long id) {
        return kefuMessageService.get(id);
    }

    // ========== 消息类型 ==========

    @Tool(name = "listMsgTypes", description = "查询所有消息类型模板")
    public Map<String, Object> listMsgTypes(
            @ToolParam(description = "页码，默认1") Integer pageNum,
            @ToolParam(description = "每页条数，默认10") Integer pageSize) {
        KefuMsgTypeEntity query = new KefuMsgTypeEntity();
        int page = pageNum != null ? pageNum : 1;
        int size = pageSize != null ? pageSize : 10;
        PageInfo<KefuMsgTypeEntity> pageInfo = kefuMsgTypeService.findPage(query, page, size);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", pageInfo.getTotal());
        result.put("pageNum", pageInfo.getPageNum());
        result.put("pageSize", pageInfo.getPageSize());
        result.put("list", pageInfo.getList());
        return result;
    }

    @Tool(name = "getMsgTypeDetail", description = "查看消息类型模板详情")
    public KefuMsgTypeEntity getMsgTypeDetail(
            @ToolParam(description = "消息类型ID") Long id) {
        return kefuMsgTypeService.get(id);
    }

    // ========== 消息订阅 ==========

    @Tool(name = "listMsgSubscriptions", description = "查询消息订阅列表")
    public Map<String, Object> listMsgSubscriptions(
            @ToolParam(description = "页码，默认1") Integer pageNum,
            @ToolParam(description = "每页条数，默认10") Integer pageSize) {
        KefuMsgSubscribeEntity query = new KefuMsgSubscribeEntity();
        int page = pageNum != null ? pageNum : 1;
        int size = pageSize != null ? pageSize : 10;
        PageInfo<KefuMsgSubscribeEntity> pageInfo = kefuMsgSubscribeService.findPage(query, page, size);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", pageInfo.getTotal());
        result.put("pageNum", pageInfo.getPageNum());
        result.put("pageSize", pageInfo.getPageSize());
        result.put("list", pageInfo.getList());
        return result;
    }

    @Tool(name = "getMsgSubscriptionDetail", description = "查看消息订阅详情")
    public KefuMsgSubscribeEntity getMsgSubscriptionDetail(
            @ToolParam(description = "订阅ID") Long id) {
        return kefuMsgSubscribeService.get(id);
    }

    // ========== 发送消息 ==========

    @Tool(name = "sendKefuMessage", description = "通过客服系统发送模板消息给用户")
    public Map<String, Object> sendKefuMessage(
            @ToolParam(description = "消息类型：VOTE(每日一题) TASK(定时任务) GIFT_USAGE(礼品下单) GIFT_DELIVERY(礼品发货) PHRASE(每日一句)") String msgTypeCode,
            @ToolParam(description = "目标用户Jia账号") String jiacn) throws Exception {
        EsContext ctx = EsContextHolder.getContext();
        KefuMsgTypeCode code = KefuMsgTypeCode.valueOf(msgTypeCode.toUpperCase());
        boolean sent = kefuService.sendMessage(code, ctx.getClientId(), jiacn);
        return Map.of("msgType", msgTypeCode, "jiacn", jiacn, "sent", sent);
    }

    @Tool(name = "checkWxActive", description = "检查用户微信是否活跃（已关注公众号）")
    public Map<String, Object> checkWxActive(
            @ToolParam(description = "用户Jia账号") String jiacn) {
        boolean active = kefuService.isWxActive(jiacn);
        return Map.of("jiacn", jiacn, "wxActive", active);
    }
}
