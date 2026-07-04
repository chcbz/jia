package cn.jia.chat.tool;

import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import cn.jia.point.entity.PointGiftEntity;
import cn.jia.point.entity.PointGiftUsageEntity;
import cn.jia.point.entity.PointGiftVO;
import cn.jia.point.entity.PointRecordEntity;
import cn.jia.point.entity.PointSignEntity;
import cn.jia.point.service.GiftService;
import cn.jia.point.service.PointService;
import com.github.pagehelper.PageInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class PointTools {

    private final PointService pointService;
    private final GiftService giftService;

    @Tool(name = "signIn", description = "用户每日签到，获取积分奖励")
    public PointRecordEntity signIn() {
        EsContext ctx = EsContextHolder.getContext();
        PointSignEntity sign = new PointSignEntity();
        sign.setJiacn(ctx.getJiacn());
        return pointService.sign(sign);
    }

    @Tool(name = "addPoints", description = "给指定用户增加积分")
    public PointRecordEntity addPoints(
            @ToolParam(description = "用户Jia账号") String jiacn,
            @ToolParam(description = "积分数值") Integer point,
            @ToolParam(description = "积分类型：1新用户 2签到 3推荐 4礼品兑换 5试手气 6答题 7短语被赞") Integer type) {
        return pointService.add(jiacn, point, type);
    }

    @Tool(name = "listGifts", description = "查询可兑换的礼品列表")
    public Map<String, Object> listGifts(
            @ToolParam(description = "页码，默认1") Integer pageNum,
            @ToolParam(description = "每页条数，默认10") Integer pageSize) {
        int page = pageNum != null ? pageNum : 1;
        int size = pageSize != null ? pageSize : 10;
        PageInfo<PointGiftEntity> pageInfo = giftService.list(page, size, new PointGiftVO(), null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", pageInfo.getTotal());
        result.put("pageNum", pageInfo.getPageNum());
        result.put("pageSize", pageInfo.getPageSize());
        result.put("list", pageInfo.getList());
        return result;
    }

    @Tool(name = "getGiftDetail", description = "查看礼品详情")
    public PointGiftEntity getGiftDetail(
            @ToolParam(description = "礼品ID") Long giftId) {
        return giftService.find(giftId);
    }

    @Tool(name = "exchangeGift", description = "使用积分兑换礼品")
    public Map<String, Object> exchangeGift(
            @ToolParam(description = "礼品ID") Long giftId,
            @ToolParam(description = "兑换数量，默认1") Integer quantity) throws Exception {
        EsContext ctx = EsContextHolder.getContext();
        PointGiftUsageEntity usage = new PointGiftUsageEntity();
        usage.setGiftId(giftId);
        usage.setJiacn(ctx.getJiacn());
        usage.setQuantity(quantity != null ? quantity : 1);
        giftService.usage(usage);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("giftUsageId", usage.getId());
        result.put("giftId", giftId);
        result.put("status", "exchanged");
        return result;
    }

    @Tool(name = "listGiftUsages", description = "查看用户的礼品兑换记录")
    public Map<String, Object> listGiftUsages(
            @ToolParam(description = "页码，默认1") Integer pageNum,
            @ToolParam(description = "每页条数，默认10") Integer pageSize) {
        EsContext ctx = EsContextHolder.getContext();
        int page = pageNum != null ? pageNum : 1;
        int size = pageSize != null ? pageSize : 10;
        PageInfo<PointGiftUsageEntity> pageInfo = giftService.usageListByUser(page, size, ctx.getJiacn(), null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", pageInfo.getTotal());
        result.put("pageNum", pageInfo.getPageNum());
        result.put("pageSize", pageInfo.getPageSize());
        result.put("list", pageInfo.getList());
        return result;
    }
}
