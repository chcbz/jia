package cn.jia.user.api;

import cn.jia.core.context.EsContextHolder;
import cn.jia.core.entity.JsonResult;
import cn.jia.core.errcode.ErrCodeHolder;
import cn.jia.core.exception.EsRuntimeException;
import cn.jia.core.util.Base64Util;
import cn.jia.core.util.BeanUtil;
import cn.jia.core.util.PasswordUtil;
import cn.jia.core.util.StringUtil;
import cn.jia.isp.entity.LdapUser;
import cn.jia.isp.entity.LdapUserGroup;
import cn.jia.isp.service.LdapUserGroupService;
import cn.jia.isp.service.LdapUserService;
import cn.jia.sms.common.SmsConstants;
import cn.jia.sms.common.SmsErrorConstants;
import cn.jia.sms.entity.SmsCodeEntity;
import cn.jia.sms.entity.SmsConfigEntity;
import cn.jia.sms.entity.SmsSendEntity;
import cn.jia.sms.entity.SmsSendResult;
import cn.jia.sms.entity.SmsTemplateEntity;
import cn.jia.sms.service.SmsService;
import cn.jia.sms.service.SmsServiceProvider;
import cn.jia.user.common.UserErrorConstants;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.WebAttributes;
import org.springframework.security.web.savedrequest.DefaultSavedRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.ModelAndView;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 登录控制器
 */
@Slf4j
@Controller
@RequestMapping("/login")
public class LoginController {
    private static final String SAVED_REQUEST = "SPRING_SECURITY_SAVED_REQUEST";

    @Autowired(required = false)
    private LdapUserService ldapUserService;
    @Autowired(required = false)
    private LdapUserGroupService ldapUserGroupService;
    @Autowired(required = false)
    private SmsService smsService;
    @Autowired(required = false)
    private SmsServiceProvider smsServiceProvider;

    @Value("${oauth.default.clientId:jia_client}")
    private String defaultClientId;

    @Value("${sms.login.templateId:}")
    private String smsTemplateId;

    /**
     * 登录页面
     *
     * @param request  请求对象
     * @return 登录视图
     */
    @GetMapping("/index.html")
    public ModelAndView login(HttpServletRequest request) {
        ModelAndView view = this.initModelAndView(request);
        view.setViewName("login/login");
        return view;
    }

    /**
     * 生成验证码信息接口
     * 生成新的验证码，根据模板格式化内容并通过短信服务商发送给用户
     *
     * @param phone      电话号码
     * @return 最新生成的验证码
     * @throws Exception 生成或发送过程中可能抛出的异常
     */
    @RequestMapping(value = "sms/gen", method = RequestMethod.GET)
    @ResponseBody
    public Object gen(@RequestParam String phone, @RequestParam Integer smsType, HttpServletRequest request) throws Exception {
        //检查是否还有额度
        String clientId = this.getClientId(request);
        SmsConfigEntity config = smsService.selectConfig(clientId);
        if (config == null || config.getRemain() <= 0) {
            throw new EsRuntimeException(SmsErrorConstants.SMS_NOT_ENOUGH);
        }

        String smsCode = smsService.upsert(phone, smsType);

        SmsTemplateEntity template = smsService.findTemplate(smsTemplateId);
        if (template == null) {
            throw new EsRuntimeException(SmsErrorConstants.SMS_TEMPLATE_NOT_EXIST);
        }
        // 使用抽象的短信服务发送短信
        SmsSendResult result = smsServiceProvider.sendSmsTemplate(phone, smsTemplateId, config.getShortName(),
                Map.of("code", smsCode, "min", "5"), null, null, null);

        //将发送记录保存到系统里
        if (result.isSuccess()) {
            String content = "【" + config.getShortName() + "】" + template.getContent();
            content = content.replace("{code}", smsCode).replace("{min}", "5");

            SmsSendEntity smsSend = new SmsSendEntity();
            smsSend.setContent(content);
            smsSend.setMobile(phone);
            smsSend.setMsgid(result.getMsgId());
            smsSend.setClientId(clientId);
            smsService.send(smsSend);
            return JsonResult.success(smsCode);
        } else {
            return JsonResult.failure("E999", result.getMessage());
        }
    }

    @GetMapping("/register")
    public ModelAndView register(HttpServletRequest request) {
        ModelAndView view = this.initModelAndView(request);
        view.setViewName("login/register");
        return view;
    }

    @PostMapping("/register")
    public ModelAndView register(LdapUser user, HttpServletRequest request, HttpServletResponse response) {
        ModelAndView view = this.initModelAndView(request);
        view.setViewName("login/register");
        view.addObject("user", user);
        List<LdapUser> userList = ldapUserService.search(user);
        for (LdapUser u : userList) {
            if (u.getTelephoneNumber().equals(user.getTelephoneNumber())) {
                view.addObject("error", ErrCodeHolder.getMessage(UserErrorConstants.USER_PHONE_HAS_EXIST));
                return view;
            } else if (u.getEmail().equals(user.getEmail())) {
                view.addObject("error", ErrCodeHolder.getMessage(UserErrorConstants.USER_EMAIL_HAS_EXIST));
                return view;
            } else if (u.getCn().equals(user.getCn())) {
                view.addObject("error", ErrCodeHolder.getMessage(UserErrorConstants.USER_HAS_EXIST));
                return view;
            }
        }
        user.setUserPassword(PasswordUtil.encode(new String(user.getUserPassword())).getBytes(StandardCharsets.UTF_8));
        ldapUserService.create(user);
        String clientId = this.getClientId(request);
        LdapUserGroup org = ldapUserGroupService.findByClientId(clientId);
        if (org != null) {
            ldapUserGroupService.addUser(org, user.getDn());
        }
        view.addObject("success", true);
        return view;
    }

    @GetMapping("/resetPassword")
    public ModelAndView resetPassword(HttpServletRequest request) {
        ModelAndView view = this.initModelAndView(request);
        view.setViewName("login/resetPassword");
        return view;
    }

    private ModelAndView initModelAndView(HttpServletRequest request) {
        ModelAndView view = new ModelAndView();
        String clientId = this.getClientId(request);
        log.info("clientId: {}", clientId);
        LdapUserGroup org = ldapUserGroupService.findByClientId(clientId);
        view.addObject("org", org);
        view.addObject("orgLogo", Optional.ofNullable(org).map(LdapUserGroup::getLogo).map(Base64Util::encode));
        Object exception = request.getSession().getAttribute(WebAttributes.AUTHENTICATION_EXCEPTION);
        if (exception instanceof AuthenticationException) {
            view.addObject("error", ((AuthenticationException) exception).getMessage());
            request.getSession().removeAttribute(WebAttributes.AUTHENTICATION_EXCEPTION);
        }
        return view;
    }

    private String getClientId(HttpServletRequest request) {
        return Optional.ofNullable(request.getSession())
                .map(session -> (DefaultSavedRequest) session.getAttribute(SAVED_REQUEST))
                .map(savedRequest -> savedRequest.getParameterValues("client_id"))
                .map(values -> values[0]).orElse(defaultClientId);
    }

    /**
     * 重置密码
     *
     * @param smsCode     短信验证码
     * @param newPassword 新密码
     * @return 处理结果
     */
    @PostMapping(value = "/resetPassword")
    public ModelAndView resetPassword(@RequestParam String phone, @RequestParam String smsCode,
                                      @RequestParam String newPassword, HttpServletRequest request) {
        ModelAndView view = this.initModelAndView(request);
        view.setViewName("login/resetPassword");
        LdapUser example = new LdapUser();
        example.setTelephoneNumber(phone);
        LdapUser u;
        try {
            u = ldapUserService.findByExample(example);
        } catch (Exception e) {
            view.addObject("error", ErrCodeHolder.getMessage(UserErrorConstants.USER_NOT_EXIST));
            return view;
        }
        SmsCodeEntity code = smsService.selectSmsCodeNoUsed(u.getTelephoneNumber(), SmsConstants.SMS_CODE_TYPE_RESETPWD);
        if (code == null || !smsCode.equals(code.getSmsCode())) {
            view.addObject("error", ErrCodeHolder.getMessage(SmsErrorConstants.SMS_CODE_INCORRECT));
            return view;
        }
        smsService.useSmsCode(code.getId());
        u.setUserPassword(PasswordUtil.encode(newPassword).getBytes(StandardCharsets.UTF_8));
        ldapUserService.modifyLdapUser(u);
        view.addObject("success", true);
        return view;
    }

    /**
     * 获取用户OAUTH2权限信息
     *
     * @param user 用户信息
     * @return 用户信息
     */
    @RequestMapping(value = "/user", method = RequestMethod.GET)
    public Principal info(Principal user) {
        return user;
    }

    @GetMapping(value = "/user/info")
    @ResponseBody
    public Object userInfo() {
        LdapUser user = ldapUserService.findByUid(EsContextHolder.getContext().getUsername());
        if (user == null) {
            throw new EsRuntimeException(UserErrorConstants.USER_NOT_EXIST);
        }
        user.setUserPassword("***".getBytes());
        return JsonResult.success(user);
    }

    /**
     * 更新用户信息
     *
     * @param user 用户信息
     * @return 最新用户信息
     */
    @PostMapping(value = "/user/info/update")
    @ResponseBody
    public Object updateUser(LdapUser user) {
        LdapUser u = ldapUserService.findByUid(EsContextHolder.getContext().getUsername());
        if (u == null) {
            throw new EsRuntimeException(UserErrorConstants.USER_NOT_EXIST);
        }
        BeanUtil.copyPropertiesIgnoreNull(user, u, "uid");
        ldapUserService.modifyLdapUser(u);
        u.setUserPassword("***".getBytes());
        return JsonResult.success(u);
    }

    /**
     * 修改用户密码
     *
     * @param oldPassword 旧密码
     * @param newPassword 新密码
     * @return 处理结果
     */
    @GetMapping(value = "/user/password/change")
    @ResponseBody
    public Object changePassword(@RequestParam String oldPassword, @RequestParam String newPassword) {
        LdapUser u = ldapUserService.findByUid(EsContextHolder.getContext().getUsername());
        if (u == null) {
            throw new EsRuntimeException(UserErrorConstants.USER_NOT_EXIST);
        }
        if (u.getUserPassword() != null && !Arrays.equals(u.getUserPassword(), PasswordUtil.encode(oldPassword).getBytes(StandardCharsets.UTF_8))) {
            throw new EsRuntimeException(UserErrorConstants.OLD_PASSWORD_WRONG);
        }
        u.setUserPassword(PasswordUtil.encode(newPassword).getBytes(StandardCharsets.UTF_8));
        ldapUserService.modifyLdapUser(u);
        return JsonResult.success();
    }

    /**
     * 上传用户头像
     *
     * @param file 头像文件
     * @return 处理结果
     * @throws Exception 异常信息
     */
    @PostMapping(value = "/user/avatar/update")
    @ResponseBody
    public Object updateAvatar(@RequestPart MultipartFile file) throws Exception {
        LdapUser u = ldapUserService.findByUid(EsContextHolder.getContext().getUsername());
        if (u == null || StringUtil.isEmpty(u.getTelephoneNumber())) {
            throw new EsRuntimeException(UserErrorConstants.USER_NOT_EXIST);
        }
        u.setHeadimg(file.getBytes());
        ldapUserService.modifyLdapUser(u);
        return JsonResult.success();
    }
}
