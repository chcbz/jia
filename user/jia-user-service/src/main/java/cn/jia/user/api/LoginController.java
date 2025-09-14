package cn.jia.user.api;

import cn.jia.base.service.DictService;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.entity.JsonResult;
import cn.jia.core.errcode.ErrCodeHolder;
import cn.jia.core.exception.EsErrorConstants;
import cn.jia.core.exception.EsRuntimeException;
import cn.jia.core.util.Base64Util;
import cn.jia.core.util.BeanUtil;
import cn.jia.core.util.DateUtil;
import cn.jia.core.util.HttpUtil;
import cn.jia.core.util.Md5Util;
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
import cn.jia.sms.entity.SmsTemplateEntity;
import cn.jia.sms.service.SmsService;
import cn.jia.user.common.UserErrorConstants;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.LdapShaPasswordEncoder;
import org.springframework.security.web.WebAttributes;
import org.springframework.security.web.savedrequest.DefaultSavedRequest;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.stereotype.Controller;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.ModelAndView;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Arrays;
import java.util.List;
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
    private DictService dictService;

    @Autowired
    private RestTemplate restTemplate;

    @Value("${oauth.default.clientId:jia_client}")
    private String defaultClientId;

    /**
     * 登录页面
     *
     * @param request  请求对象
     * @return 登录视图
     */
    @GetMapping("/index.html")
    public ModelAndView login(HttpServletRequest request) {
        ModelAndView view = new ModelAndView();
        view.setViewName("login/login");
        String clientId = Optional.ofNullable(request.getSession())
                .map(session -> (DefaultSavedRequest)session.getAttribute(SAVED_REQUEST))
                .map(savedRequest -> savedRequest.getParameterValues("client_id"))
                .map(values -> values[0]).orElse(defaultClientId);
        LdapUserGroup org = ldapUserGroupService.findByClientId(clientId);
        view.addObject("org", org);
        view.addObject("orgLogo", Base64Util.encode(org.getLogo()));
        Object exception = request.getSession().getAttribute(WebAttributes.AUTHENTICATION_EXCEPTION);
        if (exception instanceof AuthenticationException) {
            view.addObject("error", ((AuthenticationException) exception).getMessage());
            request.getSession().removeAttribute(WebAttributes.AUTHENTICATION_EXCEPTION);
        }
        return view;
    }

    /**
     * 生成验证码信息
     *
     * @param phone   电话号码
     * @param smsType 验证码类型
     * @return 最新验证码
     */
    @RequestMapping(value = "/sms/gen", method = RequestMethod.GET)
    @ResponseBody
    public Object gen(@RequestParam String phone, @RequestParam Integer smsType,
                      @RequestParam(value = "templateId", required = false) String templateId,
                      HttpServletRequest request, HttpServletResponse response) throws Exception {
        //检查是否还有额度
        String clientId = EsContextHolder.getContext().getClientId();
        RequestCache requestCache = new HttpSessionRequestCache();
        SavedRequest savedRequest = requestCache.getRequest(request, response);
        if (savedRequest != null) {
            String redirectUrl = savedRequest.getRedirectUrl();
            clientId = HttpUtil.getUrlValue(redirectUrl, "client_id");
        }
        SmsConfigEntity config = smsService.selectConfig(clientId);
        if (config == null || config.getRemain() <= 0) {
            throw new EsRuntimeException(SmsErrorConstants.SMS_NOT_ENOUGH);
        }

        String smsCode = smsService.upsert(phone, smsType);

        templateId = StringUtil.isEmpty(templateId) ? SmsConstants.SMS_CODE_TEMPLATE_ID : templateId;
        SmsTemplateEntity template = smsService.findTemplate(templateId);
        if (template == null) {
            throw new EsRuntimeException(SmsErrorConstants.SMS_TEMPLATE_NOT_EXIST);
        }
        String content = "【" + config.getShortName() + "】" + smsService.findTemplate(templateId).getContent();
        content = content.replace("{0}", smsCode);
        String tkey = DateUtil.getDate("yyyyMMddHHmmss");
        String smsUsername = dictService.selectByTypeAndValue(SmsConstants.DICT_TYPE_SMS_CONFIG,
                SmsConstants.SMS_CONFIG_USERNAME).getName();
        String smsPassword = dictService.selectByTypeAndValue(SmsConstants.DICT_TYPE_SMS_CONFIG,
                SmsConstants.SMS_CONFIG_PASSWORD).getName();
        String passwd = Md5Util.str2Base32Md5(Md5Util.str2Base32Md5(smsPassword) + tkey);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> map = new LinkedMultiValueMap<>();
        map.add("username", smsUsername);
        map.add("tkey", tkey);
        map.add("password", passwd);
        map.add("mobile", phone);
        map.add("content", content);

        HttpEntity<MultiValueMap<String, String>> smsRequest = new HttpEntity<>(map, headers);

        String sendSmsURL = "http://hy.mix2.zthysms.com/sendSms.do";
        ResponseEntity<String> smsResponse = restTemplate.postForEntity(sendSmsURL, smsRequest, String.class);
        //将发送记录保存到系统里
        if (smsResponse.getBody() != null && "1".equals(smsResponse.getBody().split(",")[0])) {
            SmsSendEntity smsSend = new SmsSendEntity();
            smsSend.setClientId(clientId);
            smsSend.setContent(content);
            smsSend.setMobile(phone);
            smsSend.setMsgid(smsResponse.getBody().split(",")[1]);
            smsService.send(smsSend);
            return JsonResult.success(smsCode);
        } else {
            return JsonResult.failure("E999", smsResponse.getBody());
        }
    }

    /**
     * 验证码已经被使用
     *
     * @param phone   手机号码
     * @param smsType 短信类型
     * @param smsCode 短信验证码
     * @return 处理结果
     */
    @RequestMapping(value = "/sms/validate", method = RequestMethod.GET)
    @ResponseBody
    public Object validateSmsCode(@RequestParam String phone, @RequestParam Integer smsType,
                                  @RequestParam String smsCode) {
        SmsCodeEntity code = smsService.selectSmsCodeNoUsed(phone, smsType);
        if (code == null || !smsCode.equals(code.getSmsCode())) {
            throw new EsRuntimeException(EsErrorConstants.DATA_NOT_FOUND);
        }
        smsService.useSmsCode(code.getId());
        return JsonResult.success();
    }

    @GetMapping("/register")
    public String register() {
        return "login/register";
    }

    @PostMapping("/register")
    public ModelAndView register(LdapUser user, HttpServletRequest request, HttpServletResponse response) {
        ModelAndView view = new ModelAndView();
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
        LdapShaPasswordEncoder encoder = new LdapShaPasswordEncoder();
        user.setUserPassword(encoder.encode(new String(user.getUserPassword())).getBytes(StandardCharsets.UTF_8));
        ldapUserService.create(user);
        RequestCache requestCache = new HttpSessionRequestCache();
        SavedRequest savedRequest = requestCache.getRequest(request, response);
        if (savedRequest != null) {
            String redirectUrl = savedRequest.getRedirectUrl();
            String clientId = HttpUtil.getUrlValue(redirectUrl, "client_id");
            LdapUserGroup org = ldapUserGroupService.findByClientId(clientId);
            if (org != null) {
                ldapUserGroupService.addUser(org, user.getDn());
            }
        }
        view.addObject("success", true);
        return view;
    }

    @GetMapping("/resetPassword")
    public String resetPassword() {
        return "login/resetPassword";
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
                                      @RequestParam String newPassword) {
        ModelAndView view = new ModelAndView();
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
        LdapShaPasswordEncoder encoder = new LdapShaPasswordEncoder();
        u.setUserPassword(encoder.encode(newPassword).getBytes(StandardCharsets.UTF_8));
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
        LdapShaPasswordEncoder encoder = new LdapShaPasswordEncoder();
        if (u.getUserPassword() != null && !Arrays.equals(u.getUserPassword(), encoder.encode(oldPassword).getBytes(StandardCharsets.UTF_8))) {
            throw new EsRuntimeException(UserErrorConstants.OLD_PASSWORD_WRONG);
        }
        u.setUserPassword(encoder.encode(newPassword).getBytes(StandardCharsets.UTF_8));
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
