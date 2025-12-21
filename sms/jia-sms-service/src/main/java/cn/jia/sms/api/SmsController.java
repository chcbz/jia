package cn.jia.sms.api;

import cn.jia.core.context.EsContextHolder;
import cn.jia.core.entity.JsonRequestPage;
import cn.jia.core.entity.JsonResult;
import cn.jia.core.entity.JsonResultPage;
import cn.jia.core.exception.EsRuntimeException;
import cn.jia.core.util.*;
import cn.jia.sms.common.SmsErrorConstants;
import cn.jia.sms.entity.*;
import cn.jia.sms.service.SmsService;
import cn.jia.sms.service.SmsServiceProvider;
import com.github.pagehelper.PageInfo;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 短信服务控制器
 * 提供短信发送、验证码管理、配置管理等相关接口
 */
@Slf4j
@RestController
@RequestMapping("/sms")
public class SmsController {

    @Inject
    private SmsService smsService;

    @Autowired(required = false)
    private SmsServiceProvider smsServiceProvider;

    /**
     * 验证码校验接口
     * 校验指定手机号和类型的验证码是否正确，如果正确则标记为已使用
     *
     * @param phone   手机号码
     * @param smsType 验证码类型
     * @param smsCode 验证码
     * @return 校验结果
     * @throws Exception 校验过程中可能抛出的异常
     */
    @RequestMapping(value = "/validate", method = RequestMethod.GET)
    public Object validateSmsCode(@RequestParam String phone, @RequestParam Integer smsType, @RequestParam String smsCode) throws Exception {
        SmsCodeEntity code = smsService.selectSmsCodeNoUsed(phone, smsType);
        if (code == null || !smsCode.equals(code.getSmsCode())) {
            throw new EsRuntimeException(SmsErrorConstants.DATA_NOT_FOUND);
        }
        smsService.useSmsCode(code.getId());
        return JsonResult.success();
    }

    /**
     * 提取并使用验证码接口
     * 获取指定手机号和类型的未使用验证码，并将其标记为已使用
     *
     * @param phone   手机号码
     * @param smsType 消息类型
     * @return 验证码内容
     * @throws Exception 获取过程中可能抛出的异常
     */
    @RequestMapping(value = "/use", method = RequestMethod.GET)
    public Object useSmsCode(@RequestParam String phone, @RequestParam Integer smsType) throws Exception {
        SmsCodeEntity code = smsService.selectSmsCodeNoUsed(phone, smsType);
        if (code == null) {
            throw new EsRuntimeException(SmsErrorConstants.DATA_NOT_FOUND);
        }
        smsService.useSmsCode(code.getId());
        return JsonResult.success(code.getSmsCode());
    }

    /**
     * 生成验证码信息接口
     * 生成新的验证码，根据模板格式化内容并通过短信服务商发送给用户
     *
     * @param phone      电话号码
     * @param smsType    验证码类型
     * @param templateId 模板ID，可选参数
     * @return 最新生成的验证码
     * @throws Exception 生成或发送过程中可能抛出的异常
     */
    @RequestMapping(value = "/gen", method = RequestMethod.GET)
    public Object gen(@RequestParam String phone, @RequestParam Integer smsType,
                      @RequestParam(value = "templateId", required = false) String templateId) throws Exception {
        //检查是否还有额度
        SmsConfigEntity config = smsService.selectConfig(EsContextHolder.getContext().getClientId());
        if (config == null || config.getRemain() <= 0) {
            throw new EsRuntimeException(SmsErrorConstants.SMS_NOT_ENOUGH);
        }

        String smsCode = smsService.upsert(phone, smsType);

        SmsTemplateEntity template = smsService.findTemplate(templateId);
        if (template == null) {
            throw new EsRuntimeException(SmsErrorConstants.SMS_TEMPLATE_NOT_EXIST);
        }
        String content = "【" + config.getShortName() + "】" + template.getContent();
        content = content.replace("{0}", smsCode);

        // 使用抽象的短信服务发送短信
        SmsSendResult result = smsServiceProvider.sendSmsTemplate(phone, templateId, config.getShortName(),
                Map.of("code", smsCode), null, null, null);

        //将发送记录保存到系统里
        if (result.isSuccess()) {
            SmsSendEntity smsSend = new SmsSendEntity();
            smsSend.setContent(content);
            smsSend.setMobile(phone);
            smsSend.setMsgid(result.getMsgId());
            smsSend.setClientId(EsContextHolder.getContext().getClientId());
            smsService.send(smsSend);
            return JsonResult.success(smsCode);
        } else {
            return JsonResult.failure("E999", result.getMessage());
        }
    }

    /**
     * 发送单条短信接口
     * 向指定手机号发送短信内容
     *
     * @param mobile  手机号码
     * @param content 短信内容
     * @param xh      扩展的小号，可选参数
     * @return 发送结果
     */
    @RequestMapping(value = "/send", method = RequestMethod.POST)
    @ResponseBody
    public Object sendSms(@RequestParam String mobile, @RequestParam String content, @RequestParam(required = false) String xh) {
        //检查是否还有额度
        SmsConfigEntity config = smsService.selectConfig(EsContextHolder.getContext().getClientId());
        if (config == null || config.getRemain() <= 0) {
            throw new EsRuntimeException(SmsErrorConstants.SMS_NOT_ENOUGH);
        }

        // 使用抽象的短信服务发送短信
        SmsSendResult result = smsServiceProvider.sendSms(config.getShortName(), mobile, content, xh);

        //将发送记录保存到系统里
        if (result.isSuccess()) {
            SmsSendEntity smsSend = new SmsSendEntity();
            smsSend.setContent(content);
            smsSend.setMobile(mobile);
            smsSend.setMsgid(result.getMsgId());
            smsSend.setXh(xh);
            smsSend.setClientId(EsContextHolder.getContext().getClientId());
            smsService.send(smsSend);
            return JsonResult.success(result.getMessage());
        } else {
            return JsonResult.failure("E999", result.getMessage());
        }
    }

    /**
     * 短信发送列表接口
     * 分页查询短信发送记录列表
     *
     * @param page    查询条件和分页信息
     * @param request HTTP请求对象
     * @return 短信发送记录列表
     */
    @RequestMapping(value = "/send/list", method = RequestMethod.POST)
    public Object listSend(@RequestBody JsonRequestPage<SmsSendVO> page, HttpServletRequest request) {
        SmsSendVO example = Optional.ofNullable(page.getSearch()).orElse(new SmsSendVO());
        example.setClientId(EsContextHolder.getContext().getClientId());
        PageInfo<SmsSendEntity> list = smsService.listSend(example, page.getPageNum(), page.getPageSize());
        JsonResultPage<SmsSendEntity> result = new JsonResultPage<>(list.getList());
        result.setPageNum(list.getPageNum());
        result.setTotal(list.getTotal());
        return result;
    }

    /**
     * 批量发送短信接口
     * 向多个手机号码发送相同的短信内容
     *
     * @param records 短信内容
     * @return 发送结果
     */
    @RequestMapping(value = "/sendBatch", method = RequestMethod.POST)
    @ResponseBody
    public Object sendSmsBatch(@RequestBody List<SmsBatchRecord> records) {
        //检查是否还有额度
        SmsConfigEntity config = smsService.selectConfig(EsContextHolder.getContext().getClientId());
        if (config == null || config.getRemain() < records.size()) {
            throw new EsRuntimeException(SmsErrorConstants.SMS_NOT_ENOUGH);
        }

        // 使用抽象的短信服务发送短信
        SmsSendResult result = smsServiceProvider.sendSmsBatch(config.getShortName(), records, null);

        if (result.isSuccess()) {
            for (int i = 0; i < records.size(); i++) {
                SmsBatchRecord record = records.get(i);
                SmsSendEntity smsSend = new SmsSendEntity();
                smsSend.setContent(record.getContent());
                smsSend.setMobile(record.getMobile());
                smsSend.setMsgid(result.getMsgId() + "-" + (i + 1));
                smsSend.setXh(record.getExt());
                smsSend.setClientId(EsContextHolder.getContext().getClientId());
                smsService.send(smsSend);
            }
            return JsonResult.success(result.getMessage());
        } else {
            return JsonResult.failure("E999", result.getMessage());
        }
    }

    /**
     * 短信剩余条数查询接口
     * 查询当前账户的短信余额信息
     *
     * @return 短信余额信息
     */
    @RequestMapping(value = "/balance", method = RequestMethod.GET)
    @ResponseBody
    public Object balance() {
        SmsConfigEntity config = smsService.selectConfig(EsContextHolder.getContext().getClientId());
        return JsonResult.success(config.getRemain());
    }

    /**
     * 接收短信回复接口
     * 处理用户回复的短信内容
     *
     * @param mobile  发送回复的手机号
     * @param content 回复内容
     * @param msgid   原始短信的消息ID
     * @param xh      扩展号码，可选参数
     * @return 处理结果
     */
    @RequestMapping(value = "/receive", method = RequestMethod.GET)
    @ResponseBody
    public Object receive(@RequestParam String mobile, @RequestParam String content, @RequestParam String msgid, @RequestParam(required = false) String xh) {
        SmsReplyEntity smsReply = new SmsReplyEntity();
        smsReply.setContent(content);
        smsReply.setMobile(mobile);
        smsReply.setMsgid(msgid);
        smsReply.setXh(xh);
        smsService.reply(smsReply);

        SmsSendEntity send = smsService.selectSend(msgid);
        SmsConfigEntity config = smsService.selectConfig(send.getClientId());
        if (config != null && StringUtil.isNotEmpty(config.getReplyUrl())) {
            String replyUrl = config.getReplyUrl();
            replyUrl = HttpUtil.addUrlValue(replyUrl, "mobile", mobile);
            replyUrl = HttpUtil.addUrlValue(replyUrl, "content", content);
            replyUrl = HttpUtil.addUrlValue(replyUrl, "msgid", msgid);
            replyUrl = HttpUtil.addUrlValue(replyUrl, "xh", xh);

            // 使用RestTemplate发送回复
            String response = new org.springframework.web.client.RestTemplate().getForObject(replyUrl, String.class);
            log.info("sms reply success: " + response);
        } else {
            log.warn("sms reply no replyUrl");
        }

        return JsonResult.success();
    }

    /**
     * 短信回复列表接口
     * 分页查询收到的短信回复列表
     *
     * @param page    查询条件和分页信息
     * @param request HTTP请求对象
     * @return 短信回复列表
     */
    @RequestMapping(value = "/reply/list", method = RequestMethod.POST)
    public Object listReply(@RequestBody JsonRequestPage<SmsReplyEntity> page, HttpServletRequest request) {
        SmsReplyEntity example = Optional.ofNullable(page.getSearch()).orElse(new SmsReplyEntity());
        PageInfo<SmsReplyEntity> list = smsService.listReply(example, page.getPageNum(), page.getPageSize());
        JsonResultPage<SmsReplyEntity> result = new JsonResultPage<>(list.getList());
        result.setPageNum(list.getPageNum());
        result.setTotal(list.getTotal());
        return result;
    }

    /**
     * 获取短信配置信息接口
     * 获取当前客户端的短信配置信息
     *
     * @return 短信配置信息
     */
    @RequestMapping(value = "/config/get", method = RequestMethod.GET)
    public Object findConfig() {
        SmsConfigEntity config = smsService.selectConfig(EsContextHolder.getContext().getClientId());
        return JsonResult.success(config);
    }

    /**
     * 更新短信配置信息接口
     * 更新当前客户端的短信配置信息
     *
     * @param config 新的配置信息
     * @return 更新后的配置信息
     */
    @RequestMapping(value = "/config/update", method = RequestMethod.POST)
    public Object updateConfig(@RequestBody SmsConfigEntity config) {
        config.setClientId(EsContextHolder.getContext().getClientId());
        smsService.updateConfig(config);
        return JsonResult.success(config);
    }

    /**
     * 获取短信模板信息接口
     * 根据模板ID获取短信模板详细信息
     *
     * @param templateId 模板ID
     * @return 短信模板信息
     * @throws Exception 获取过程中可能抛出的异常
     */
    @RequestMapping(value = "/template/get", method = RequestMethod.GET)
    public Object findTemplateById(@RequestParam(name = "templateId") String templateId) throws Exception {
        SmsTemplateEntity sms = smsService.findTemplate(templateId);
        if (sms == null) {
            throw new EsRuntimeException(SmsErrorConstants.DATA_NOT_FOUND);
        }
        return JsonResult.success(sms);
    }

    /**
     * 创建短信模板接口
     * 创建一个新的短信模板
     *
     * @param sms 短信模板信息
     * @return 创建成功的短信模板信息
     */
    @RequestMapping(value = "/template/create", method = RequestMethod.POST)
    public Object createTemplate(@RequestBody SmsTemplateEntity sms) {
        smsService.createTemplate(sms);
        return JsonResult.success(sms);
    }

    /**
     * 更新短信模板信息接口
     * 更新现有的短信模板信息
     *
     * @param sms 短信模板信息
     * @return 更新后的短信模板信息
     */
    @RequestMapping(value = "/template/update", method = RequestMethod.POST)
    public Object updateTemplate(@RequestBody SmsTemplateEntity sms) {
        smsService.updateTemplate(sms);
        return JsonResult.success(sms);
    }

    /**
     * 删除短信模板接口
     * 根据模板ID删除短信模板
     *
     * @param templateId 模板ID
     * @return 删除结果
     */
    @RequestMapping(value = "/template/delete", method = RequestMethod.GET)
    public Object deleteTemplate(@RequestParam(name = "templateId") String templateId) {
        smsService.deleteTemplate(templateId);
        return JsonResult.success();
    }

    /**
     * 短信模板列表接口
     * 分页查询短信模板列表
     *
     * @param page    查询条件和分页信息
     * @param request HTTP请求对象
     * @return 短信模板列表
     */
    @RequestMapping(value = "/template/list", method = RequestMethod.POST)
    public Object listTemplate(@RequestBody JsonRequestPage<SmsTemplateVO> page, HttpServletRequest request) {
        SmsTemplateVO example = Optional.ofNullable(page.getSearch()).orElse(new SmsTemplateVO());
        example.setClientId(EsContextHolder.getContext().getClientId());
        PageInfo<SmsTemplateEntity> list = smsService.listTemplate(example, page.getPageNum(), page.getPageSize());
        JsonResultPage<SmsTemplateEntity> result = new JsonResultPage<>(list.getList());
        result.setPageNum(list.getPageNum());
        result.setTotal(list.getTotal());
        return result;
    }

    /**
     * 购买短信套餐接口
     * 购买指定数量和金额的短信套餐
     *
     * @param number 短信条数
     * @param money  金额
     * @return 购买结果
     */
    @RequestMapping(value = "/buy", method = RequestMethod.GET)
    public Object buy(@RequestParam Integer number, @RequestParam Double money) {
        smsService.buy(number, money);
        return JsonResult.success();
    }
}