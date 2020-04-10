package cn.jia.sms.service;

import cn.jia.core.configuration.SpringContextHolder;
import cn.jia.core.util.DataUtil;
import cn.jia.core.util.DateUtil;
import cn.jia.sms.common.Constants;
import cn.jia.sms.dao.SmsBuyMapper;
import cn.jia.sms.entity.SmsBuy;
import cn.jia.wx.entity.PayOrder;
import cn.jia.wx.service.PayOrderParse;

import java.util.Date;

public class SmsPayOrderParse extends PayOrderParse {

    @Override
    public PayOrder scanPayNotifyResult() {
        SmsBuyMapper smsBuyMapper = SpringContextHolder.getBean(SmsBuyMapper.class);
        SmsBuy smsBuy = smsBuyMapper.selectByPrimaryKey(Integer.parseInt(this.productId.substring(3)));
        PayOrder payOrder = new PayOrder();
        payOrder.setBody("+顺短信服务");
        payOrder.setDetail("+顺短信服务");
        payOrder.setTotalFee(new Double(smsBuy.getMoney() * 100).intValue());
        return payOrder;
    }

    @Override
    public void orderNotifyResult() {
        SmsBuyMapper smsBuyMapper = SpringContextHolder.getBean(SmsBuyMapper.class);
        SmsBuy smsBuy = new SmsBuy();
        smsBuy.setId(Integer.parseInt(this.productId.substring(3)));
        smsBuy.setStatus(Constants.COMMON_ENABLE);
        smsBuy.setTime(DateUtil.genTime(new Date()));
        smsBuyMapper.updateByPrimaryKeySelective(smsBuy);
    }

    public static String genProductId(Integer id){
        return "SMS"+ DataUtil.frontCompWithZore(id, 7);
    }
}
