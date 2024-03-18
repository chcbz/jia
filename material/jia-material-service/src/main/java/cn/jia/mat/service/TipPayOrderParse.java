package cn.jia.mat.service;

import cn.jia.core.common.EsConstants;
import cn.jia.core.util.DataUtil;
import cn.jia.core.util.StringUtils;
import cn.jia.mat.entity.MatTipEntity;
import cn.jia.user.entity.UserEntity;
import cn.jia.user.service.UserService;
import cn.jia.wx.entity.PayOrderEntity;
import cn.jia.wx.entity.PayOrderParseVO;
import cn.jia.wx.service.AbstractPayOrderParseService;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class TipPayOrderParse extends AbstractPayOrderParseService {

    @Autowired
    private MatTipService tipService;
    @Autowired(required = false)
    private UserService userService;

    @PostConstruct
    public void init() {
        super.register();
    }

    private static String genProductId(Long id){
        return "TIP"+ DataUtil.frontCompWithZore(id, 7);
    }

    private static String genOutTradeNo(Long id){
        return "TIP"+ DataUtil.frontCompWithZore(id, 7);
    }

    @Override
    protected String getType() {
        return "TIP";
    }

    @Override
    public PayOrderEntity scanPayNotifyResult(PayOrderParseVO payOrderParseVO) {
        PayOrderEntity payOrder = new PayOrderEntity();
        payOrder.setBody("打赏");
        payOrder.setDetail("打赏");
        if(StringUtils.isNotEmpty(payOrderParseVO.getOutTradeNo())) {
            Long tipId = Long.parseLong(payOrderParseVO.getOutTradeNo().substring(3));
            MatTipEntity tip = tipService.get(tipId);
            payOrder.setTotalFee(tip.getPrice());
            payOrder.setProductId(genProductId(tip.getEntityId()));
            payOrder.setOutTradeNo(payOrderParseVO.getOutTradeNo());
            UserEntity user = userService.findByJiacn(tip.getJiacn());
            payOrder.setOpenid(user.getOpenid());
        } else {
            Long entityId = Long.parseLong(payOrderParseVO.getProductId().substring(3));
            Integer price = 100;
            payOrder.setTotalFee(price);
            payOrder.setProductId(payOrderParseVO.getProductId());

            MatTipEntity tip = new MatTipEntity();
            tip.setEntityId(entityId);
            tip.setPrice(price);
            tip.setJiacn(payOrderParseVO.getUserId());
            tipService.create(tip);

            UserEntity user = userService.findByJiacn(payOrderParseVO.getUserId());
            payOrder.setOpenid(user.getOpenid());
            payOrder.setOutTradeNo(genOutTradeNo(tip.getId()));
        }
        return payOrder;
    }

    @Override
    public void orderNotifyResult(PayOrderParseVO payOrderParseVO) {
        Long tipId = Long.parseLong(payOrderParseVO.getOutTradeNo().substring(3));
        MatTipEntity tip = tipService.get(tipId);
        tip.setStatus(EsConstants.COMMON_ENABLE);
        tipService.update(tip);
    }
}
