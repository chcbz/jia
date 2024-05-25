package cn.jia.point.service;

import cn.jia.core.util.DataUtil;
import cn.jia.core.util.StringUtil;
import cn.jia.point.common.PointConstants;
import cn.jia.point.dao.PointGiftDao;
import cn.jia.point.dao.PointGiftUsageDao;
import cn.jia.point.entity.PointGiftEntity;
import cn.jia.point.entity.PointGiftUsageEntity;
import cn.jia.user.entity.UserEntity;
import cn.jia.user.service.UserService;
import cn.jia.wx.entity.PayOrderEntity;
import cn.jia.wx.entity.PayOrderParseVO;
import cn.jia.wx.service.AbstractPayOrderParseService;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class GiftPayOrderService extends AbstractPayOrderParseService {

    @Autowired
    private PointGiftDao pointGiftDao;
    @Autowired
    private PointGiftUsageDao pointGiftUsageDao;
    @Autowired(required = false)
    private UserService userService;

    @PostConstruct
    public void init() {
        super.register();
    }

    @Override
    public PayOrderEntity scanPayNotifyResult(PayOrderParseVO payOrderParseVO) {
        PayOrderEntity payOrder = new PayOrderEntity();
        payOrder.setBody("礼品兑换");
        payOrder.setDetail("礼品兑换");
        if(StringUtil.isNotEmpty(payOrderParseVO.getOutTradeNo())) {
            Long giftUsageId = Long.parseLong(payOrderParseVO.getOutTradeNo().substring(3));
            PointGiftUsageEntity giftUsage = pointGiftUsageDao.selectById(giftUsageId);
            payOrder.setTotalFee(giftUsage.getPrice());
            payOrder.setProductId(genProductId(giftUsage.getGiftId()));
            payOrder.setOutTradeNo(payOrderParseVO.getOutTradeNo());
            UserEntity user = userService.findByJiacn(giftUsage.getJiacn());
            payOrder.setOpenid(user.getOpenid());
        } else {
            Long giftId = Long.parseLong(payOrderParseVO.getProductId().substring(3));
            PointGiftEntity gift = pointGiftDao.selectById(giftId);
            payOrder.setTotalFee(gift.getPrice());
            payOrder.setProductId(payOrderParseVO.getProductId());

            PointGiftUsageEntity giftUsage = new PointGiftUsageEntity();
            giftUsage.setGiftId(giftId);
            giftUsage.setPrice(gift.getPrice());
            giftUsage.setQuantity(1);
            giftUsage.setJiacn(payOrderParseVO.getUserId());
            pointGiftUsageDao.insert(giftUsage);

            UserEntity user = userService.findByJiacn(payOrderParseVO.getUserId());
            payOrder.setOpenid(user.getOpenid());
            payOrder.setOutTradeNo(genOutTradeNo(giftUsage.getId()));
        }
        return payOrder;
    }

    @Override
    public void orderNotifyResult(PayOrderParseVO payOrderParseVO) {
        Long giftUsageId = Long.parseLong(payOrderParseVO.getOutTradeNo().substring(3));
        PointGiftUsageEntity giftUsage = pointGiftUsageDao.selectById(giftUsageId);
        giftUsage.setStatus(PointConstants.COMMON_ENABLE);
        pointGiftUsageDao.updateById(giftUsage);
    }

    private String genProductId(Long id){
        return getType() + DataUtil.frontCompWithZore(id, 7);
    }

    private String genOutTradeNo(Long id){
        return getType() + DataUtil.frontCompWithZore(id, 7);
    }

    @Override
    protected String getType() {
        return "GIF";
    }
}
