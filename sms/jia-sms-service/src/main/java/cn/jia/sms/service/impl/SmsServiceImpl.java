package cn.jia.sms.service.impl;

import cn.jia.core.context.EsContextHolder;
import cn.jia.core.lock.IDistributedLock;
import cn.jia.core.lock.ILock;
import cn.jia.core.util.DataUtil;
import cn.jia.core.util.DateUtil;
import cn.jia.core.util.ValidUtil;
import cn.jia.sms.common.SmsConstants;
import cn.jia.sms.dao.*;
import cn.jia.sms.entity.*;
import cn.jia.sms.service.SmsService;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Named
public class SmsServiceImpl implements SmsService {

    @Inject
    private SmsCodeDao smsCodeDao;
    @Inject
    private SmsSendDao smsSendDao;
    @Inject
    private SmsReplyDao smsReplyDao;
    @Inject
    private SmsConfigDao smsConfigDao;
    @Inject
    private SmsBuyDao smsBuyDao;
    @Inject
    private SmsTemplateDao smsTemplateDao;
    @Autowired(required = false)
    private IDistributedLock distributedLock;

    /**
     * 插入短信表
     *
     * @param smsCode 短信验证码
     */
    @Override
    public SmsCodeEntity create(SmsCodeEntity smsCode) {
        smsCodeDao.insert(smsCode);
        return smsCode;
    }

    @Override
    public SmsCodeEntity update(SmsCodeEntity sc) {
        smsCodeDao.updateById(sc);
        return sc;
    }

    /**
     * 查询是否有未使用邮箱验证码
     *
     * @param phone   手机号码
     * @param smsType 短信类型
     * @return 验证码
     */
    @Override
    public SmsCodeEntity selectSmsCodeNoUsed(String phone, Integer smsType) {
        SmsCodeEntity example = new SmsCodeEntity();
        example.setPhone(phone);
        example.setSmsType(smsType);
        example.setStatus(SmsConstants.COMMON_ENABLE);
        List<SmsCodeEntity> smsCodeList = smsCodeDao.selectByEntity(example);
        SmsCodeEntity sc = null;
        if (!smsCodeList.isEmpty()) {
            sc = smsCodeList.getFirst();
        }
        return sc;
    }

    /**
     * 验证码已经被使用
     *
     * @param id 验证码ID
     */
    @Override
    public void useSmsCode(Long id) {
        SmsCodeEntity sc = new SmsCodeEntity();
        sc.setId(id);
        sc.setStatus(SmsConstants.COMMON_DISABLE);
        update(sc);
    }

    /**
     * 保存验证码信息
     *
     * @param phone   电话号码
     * @param smsType 验证码类型
     * @return 最新验证码
     */
    @Override
    public String upsert(String phone, Integer smsType) {
        ValidUtil.assertNotNull(phone, "phone");
        try (ILock ignored = distributedLock.lock("sms_code_" + phone)) {
            SmsCodeEntity smsCodeNoUsed = selectSmsCodeNoUsed(phone, smsType);
            // 随机获取验证码
            String smsCode = DataUtil.getRandom(true, 4);
            if (smsCodeNoUsed != null) {
                smsCodeNoUsed.setCount(smsCodeNoUsed.getCount() + 1);
                smsCodeNoUsed.setSmsCode(smsCode);
                update(smsCodeNoUsed);
                return smsCodeNoUsed.getSmsCode();
            } else {
                SmsCodeEntity sc = new SmsCodeEntity();
                sc.setPhone(phone);
                sc.setSmsCode(smsCode);
                sc.setSmsType(smsType);
                sc.setCount(1);
                sc.setStatus(SmsConstants.COMMON_ENABLE);
                create(sc);
                return smsCode;
            }
        }
    }

    @Override
    public SmsCodeEntity find(Long id) {
        return smsCodeDao.selectById(id);
    }

    @Override
    public void delete(Long id) {
        smsCodeDao.deleteById(id);
    }

    @Override
    @Transactional
    public void send(SmsSendEntity smsSend) {
        smsSendDao.insert(smsSend);
        //设置最新剩余数量
        ValidUtil.assertNotNull(smsSend.getClientId(), "clientId");
        smsConfigDao.reduce(smsSend.getClientId());
    }

    @Override
    public SmsSendEntity selectSend(String msgid) {
        return smsSendDao.selectById(msgid);
    }

    @Override
    public PageInfo<SmsSendEntity> listSend(SmsSendVO example, int pageNum, int pageSize, String orderBy) {
        PageHelper.startPage(pageNum, pageSize, Optional.ofNullable(orderBy).orElse("update_time desc"));
        return PageInfo.of(smsSendDao.selectByEntity(example));
    }

    @Override
    public void reply(SmsReplyEntity smsReply) {
        smsReplyDao.insert(smsReply);
    }

    @Override
    public PageInfo<SmsReplyEntity> listReply(SmsReplyEntity example, int pageNum, int pageSize, String orderBy) {
        PageHelper.startPage(pageNum, pageSize, Optional.ofNullable(orderBy).orElse("update_time desc"));
        return PageInfo.of(smsReplyDao.selectByEntity(example));
    }

    @Override
    public SmsConfigEntity selectConfig(String clientId) {
        return smsConfigDao.selectById(clientId);
    }

    @Override
    public void createConfig(SmsConfigEntity config) {
        smsConfigDao.insert(config);
    }

    @Override
    public void updateConfig(SmsConfigEntity config) {
        config.setRemain(null); //不能直接更新剩余数量
        smsConfigDao.updateById(config);
    }

    @Override
    @Transactional
    public void buy(int number, double money) {
        try (ILock ignore = distributedLock.lock("sms_buy_" + number)) {
            String clientId = EsContextHolder.getContext().getClientId();
            ValidUtil.assertNotNull(clientId, "clientId");
            int total = 0;
            //获取上次充值后的总充值数量
            SmsBuyEntity lastBuy = smsBuyDao.selectLatest(clientId);
            if (lastBuy != null) {
                total = lastBuy.getTotal();
            }
            //获取最新剩余数量
            SmsConfigEntity config = smsConfigDao.selectById(clientId);
            ValidUtil.assertNotNull(config.getRemain(), "config.remain");
            int remain = config.getRemain();
            //新增购买记录
            SmsBuyEntity buy = new SmsBuyEntity();
            buy.setClientId(clientId);
            buy.setNumber(number);
            buy.setMoney(BigDecimal.valueOf(money));
            buy.setTotal(total + number);
            buy.setRemain(remain + number);
            smsBuyDao.insert(buy);
            //设置最新剩余数量
            SmsConfigEntity upConfig = new SmsConfigEntity();
            upConfig.setClientId(clientId);
            upConfig.setRemain(remain + number);
            smsConfigDao.updateById(upConfig);
        }
    }

    @Override
    public SmsTemplateEntity createTemplate(SmsTemplateEntity sms) {
        sms.setTemplateId(DataUtil.getRandom(false, 32));
        smsTemplateDao.insert(sms);
        return sms;
    }

    @Override
    public SmsTemplateEntity findTemplate(String templateId) {
        return smsTemplateDao.selectById(templateId);
    }

    @Override
    public SmsTemplateEntity updateTemplate(SmsTemplateEntity sms) {
        long now = DateUtil.nowTime();
        sms.setUpdateTime(now);
        smsTemplateDao.updateById(sms);
        return sms;
    }

    @Override
    public void deleteTemplate(String templateId) {
        SmsTemplateEntity sms = new SmsTemplateEntity();
        sms.setTemplateId(templateId);
        long now = DateUtil.nowTime();
        sms.setUpdateTime(now);
        sms.setStatus(SmsConstants.COMMON_DISABLE);
        smsTemplateDao.updateById(sms);
    }

    @Override
    public PageInfo<SmsTemplateEntity> listTemplate(SmsTemplateVO example, int pageNum, int pageSize, String orderBy) {
        PageHelper.startPage(pageNum, pageSize, orderBy);
        return PageInfo.of(smsTemplateDao.selectByEntity(example));
    }

}
