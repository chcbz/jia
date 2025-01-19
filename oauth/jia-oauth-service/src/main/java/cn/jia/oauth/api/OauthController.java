package cn.jia.oauth.api;

import cn.jia.base.service.DictService;
import cn.jia.core.common.EsConstants;
import cn.jia.core.common.EsSecurityHandler;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.entity.JsonResult;
import cn.jia.core.errcode.ErrCodeHolder;
import cn.jia.core.exception.EsErrorConstants;
import cn.jia.core.exception.EsRuntimeException;
import cn.jia.core.util.*;
import cn.jia.isp.entity.LdapUser;
import cn.jia.isp.entity.LdapUserGroup;
import cn.jia.isp.service.LdapUserGroupService;
import cn.jia.isp.service.LdapUserService;
import cn.jia.oauth.dto.WeiXinOauthTokenDTO;
import cn.jia.oauth.dto.WeiXinOauthUserDTO;
import cn.jia.oauth.entity.OauthClientEntity;
import cn.jia.oauth.service.ClientService;
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
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.LdapContextSource;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.LdapShaPasswordEncoder;
import org.springframework.security.web.WebAttributes;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.stereotype.Controller;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.ModelAndView;

import javax.naming.InvalidNameException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.*;

@Slf4j
@Controller
@RequestMapping("/oauth")
@SessionAttributes("authorizationRequest")
public class OauthController {

    @Autowired
    private ClientService clientService;
    @Autowired(required = false)
    private LdapUserService ldapUserService;
    @Autowired(required = false)
    private LdapUserGroupService ldapUserGroupService;
    @Autowired(required = false)
    private SmsService smsService;
    @Autowired(required = false)
    private DictService dictService;
    @Autowired(required = false)
    private LdapTemplate ldapTemplate;

    @Autowired
    private RestTemplate restTemplate;

    @Value("${oauth.default.clientId:jia_client}")
    private String defaultClientId;
    @Value("${oauth.third-party.wxmp.appid:}")
    private String wxMpAppId;
    @Value("${oauth.third-party.wxmp.secret:}")
    private String wxMpSecret;
    @Value("${oauth.third-party.weixin.appid:}")
    private String weiXinAppId;
    @Value("${oauth.third-party.weixin.secret:}")
    private String weiXinSecret;
    @Value("${oauth.third-party.weibo.appid:}")
    private String weiBoAppId;
    @Value("${oauth.third-party.weibo.secret:}")
    private String weiBoSecret;

    @GetMapping("/login")
    public ModelAndView login(HttpServletRequest request, HttpServletResponse response) {
        ModelAndView view = new ModelAndView();
        view.setViewName("login/login");
        String clientId = Optional.ofNullable(HttpUtil.getUrlValue(request.getQueryString(), "client_id"))
                .orElse(defaultClientId);
        String loginType = Optional.ofNullable(HttpUtil.getUrlValue(request.getQueryString(), "login_type"))
                .orElse("");
        String loginScope = Optional.ofNullable(HttpUtil.getUrlValue(request.getQueryString(), "login_scope"))
                .orElse("");
        // 原请求信息的缓存及恢复
        RequestCache requestCache = new HttpSessionRequestCache();
        SavedRequest savedRequest = requestCache.getRequest(request, response);
        if (savedRequest != null) {
            String redirectUrl = savedRequest.getRedirectUrl();
            clientId = Optional.ofNullable(HttpUtil.getUrlValue(redirectUrl, "client_id"))
                    .orElse(clientId);
            loginType = Optional.ofNullable(HttpUtil.getUrlValue(redirectUrl, "login_type"))
                    .orElse(loginType);
            loginScope = Optional.ofNullable(HttpUtil.getUrlValue(redirectUrl, "login_scope"))
                    .orElse("");
        }
        switch (loginType) {
            case "wxmp" -> {
                String baseUrl = "https://" + request.getServerName();
                String redirect_uri = URLEncoder.encode(baseUrl + "/oauth/third-party/wxmp", StandardCharsets.UTF_8);
                String state = DataUtil.getRandom(true, 4);
                String url = "https://open.weixin.qq.com/connect/oauth2/authorize?appid=" + wxMpAppId +
                        "&redirect_uri=" + redirect_uri + "&response_type=code&scope=" + loginScope +
                        "&state=" + state + "#wechat_redirect";
                view.setViewName("redirect:" + url);
                return view;
            }
            case "weixin" -> {
                String baseUrl = "https://" + request.getServerName();
                String scope = "snsapi_login,snsapi_userinfo";
                String redirect_uri = URLEncoder.encode(baseUrl + "/oauth/third-party/weixin", StandardCharsets.UTF_8);
                String state = DataUtil.getRandom(true, 4);
                String url = "https://open.weixin.qq.com/connect/qrconnect?appid=" + weiXinAppId +
                        "&redirect_uri=" + redirect_uri + "&response_type=code&scope=" + scope +
                        "&state=" + state + "#wechat_redirect";
                view.setViewName("redirect:" + url);
                return view;
            }
            case "weibo" -> {
                String baseUrl = "https://" + request.getServerName();
                String redirectUri = URLEncoder.encode(baseUrl + "/oauth/third-party/weibo", StandardCharsets.UTF_8);
                String url = "https://api.weibo.com/oauth2/authorize?client_id=" + weiBoAppId +
                        "&response_type=code&redirect_uri=" + redirectUri;
                view.setViewName("redirect:" + url);
                return view;
            }
        }
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

    @PostMapping("/login")
    public String login(@RequestParam String username, @RequestParam String password,
                        @RequestParam String loginType,
                        HttpServletRequest request, HttpServletResponse response) {
        LdapUser ldapUser = new LdapUser();
        try {
            if ("phone".equals(loginType)) {
                SmsCodeEntity smsCode = smsService.selectSmsCodeNoUsed(username, SmsConstants.SMS_CODE_TYPE_LOGIN);
                if (smsCode == null || !password.equals(smsCode.getSmsCode())) {
                    String errMessage = ErrCodeHolder.getMessage(EsConstants.DICT_TYPE_ERROR_CODE);
                    request.getSession().setAttribute(WebAttributes.AUTHENTICATION_EXCEPTION, new UsernameNotFoundException(errMessage));
                    return "redirect:/oauth/login";
                }
                ldapUser.setTelephoneNumber(username);

                ldapUser = ldapUserService.findByExample(ldapUser);
                password = new String(ldapUser.getUserPassword(), StandardCharsets.UTF_8);
            } else {
                ldapUser.setCn(username);
                ldapUser = ldapUserService.findByExample(ldapUser);
            }
        } catch (Exception e) {
            String errMessage = ErrCodeHolder.getMessage(EsConstants.DICT_TYPE_ERROR_CODE);
            request.getSession().setAttribute(WebAttributes.AUTHENTICATION_EXCEPTION, new UsernameNotFoundException(errMessage));
            return "redirect:/oauth/login";
        }

        UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(ldapUser.getUid(), password);
        try {
            token.setDetails(new WebAuthenticationDetails(request));
//            Authentication authenticatedUser = authenticationManager.authenticate(token);
//
//            SecurityContextHolder.getContext().setAuthentication(authenticatedUser);
            request.getSession().setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, SecurityContextHolder.getContext());
        } catch (AuthenticationException e) {
            String errMessage = ErrCodeHolder.getMessage(EsConstants.DICT_TYPE_ERROR_CODE);
            request.getSession().setAttribute(WebAttributes.AUTHENTICATION_EXCEPTION, new BadCredentialsException(errMessage));
            return "redirect:/oauth/login";
        }
        RequestCache requestCache = new HttpSessionRequestCache();
        SavedRequest savedRequest = requestCache.getRequest(request, response);
        if (savedRequest != null) {
            String redirectUrl = savedRequest.getRedirectUrl();
            return "redirect:" + redirectUrl;
        }
        return "redirect:/oauth/login";
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
                                  @RequestParam String smsCode,
                                  HttpServletRequest request, HttpServletResponse response) {
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
                LdapContextSource contextSource = (LdapContextSource) ldapTemplate.getContextSource();
                try {
                    org.getMember().add(contextSource.getBaseLdapName().addAll(user.getDn()));
                } catch (InvalidNameException e) {
                    view.addObject("error", e.getMessage());
                    return view;
                }
                ldapUserGroupService.modify(org);
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
    public ModelAndView resetPassword(@RequestParam String phone, @RequestParam String smsCode, @RequestParam String newPassword,
                                      HttpServletRequest request, HttpServletResponse response) {
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

    @GetMapping("/third-party/wxmp")
    public String thirdPartyWxMp(@RequestParam String code, @RequestParam String state,
                                 HttpServletRequest request, HttpServletResponse response) throws Exception {
        String base_url = "https://api.weixin.qq.com";
        //获取token
        String grant_type = "authorization_code";
        String url = base_url + "/sns/oauth2/access_token?appid=" + wxMpAppId + "&secret=" + wxMpSecret +
                "&grant_type=" + grant_type + "&code=" + code;
        WeiXinOauthTokenDTO tokenDTO = restTemplate.getForObject(url, WeiXinOauthTokenDTO.class);
        if (StringUtil.isNotEmpty(Objects.requireNonNull(tokenDTO).getErrCode())) {
            throw new EsRuntimeException(null, tokenDTO.getErrMsg());
        }
        String accessToken = tokenDTO.getAccessToken();
        String openid = tokenDTO.getOpenId();
        String scope = tokenDTO.getScope();
        //保存用户信息
        LdapUser ldapUser = new LdapUser();
        ldapUser.setOpenid(openid);
        //获取用户信息
        if (scope.contains("snsapi_userinfo")) {
            url = base_url + "/sns/userinfo?access_token=" + accessToken + "&openid=" + openid;
            WeiXinOauthUserDTO userDTO = restTemplate.getForObject(url, WeiXinOauthUserDTO.class);
            if (StringUtil.isNotEmpty(Objects.requireNonNull(userDTO).getErrCode())) {
                throw new EsRuntimeException(null, userDTO.getErrMsg());
            }
            ldapUser.setWeixinid(userDTO.getUnionId());
            String country = userDTO.getCountry();
            ldapUser.setCountry(StringUtil.isEmpty(country) ? null : country);
            String province = userDTO.getProvince();
            ldapUser.setProvince(StringUtil.isEmpty(province) ? null : province);
            String city = userDTO.getCity();
            ldapUser.setCity(StringUtil.isEmpty(city) ? null : city);
            ldapUser.setNickname(new String(userDTO.getNickname().getBytes(StandardCharsets.ISO_8859_1),
                    StandardCharsets.UTF_8));
            ldapUser.setSex(userDTO.getSex());
            ldapUser.setHeadimg(ImgUtil.fromUrl(new URL(userDTO.getHeadImgUrl())));
        }

        request.getSession().setAttribute("user", ldapUser);
        return "redirect:/oauth/third-party/autologin";
    }

    @GetMapping("/third-party/weixin")
    public String thirdPartyWeiXin(@RequestParam String code, @RequestParam String state,
                                   HttpServletRequest request, HttpServletResponse response) throws Exception {
        String base_url = "https://api.weixin.qq.com";
        //获取token
        String grant_type = "authorization_code";
        String url = base_url + "/sns/oauth2/access_token?appid=" + weiXinAppId + "&secret=" + weiXinSecret +
                "&grant_type=" + grant_type + "&code=" + code;
        WeiXinOauthTokenDTO tokenDTO = restTemplate.getForObject(url, WeiXinOauthTokenDTO.class);
        if (StringUtil.isNotEmpty(Objects.requireNonNull(tokenDTO).getErrCode())) {
            throw new EsRuntimeException(null, tokenDTO.getErrMsg());
        }
        String accessToken = tokenDTO.getAccessToken();
        String openid = tokenDTO.getOpenId();
        String unionid = StringUtil.isEmpty(tokenDTO.getUnionId()) ? openid : tokenDTO.getUnionId();
        //保存用户信息
        LdapUser ldapUser = new LdapUser();
        ldapUser.setWeixinid(unionid);
        //获取用户信息
        url = base_url + "/sns/userinfo?access_token=" + accessToken + "&openid=" + openid;
        WeiXinOauthUserDTO userDTO = restTemplate.getForObject(url, WeiXinOauthUserDTO.class);
        if (StringUtil.isNotEmpty(Objects.requireNonNull(userDTO).getErrCode())) {
            throw new EsRuntimeException(null, userDTO.getErrMsg());
        }
        String country = userDTO.getCountry();
        ldapUser.setCountry(StringUtil.isEmpty(country) ? null : country);
        String province = userDTO.getProvince();
        ldapUser.setProvince(StringUtil.isEmpty(province) ? null : province);
        String city = userDTO.getCity();
        ldapUser.setCity(StringUtil.isEmpty(city) ? null : city);
        ldapUser.setNickname(new String(userDTO.getNickname().getBytes(StandardCharsets.ISO_8859_1),
                StandardCharsets.UTF_8));
        ldapUser.setSex(userDTO.getSex());
        ldapUser.setHeadimg(ImgUtil.fromUrl(new URL(userDTO.getHeadImgUrl())));

        request.getSession().setAttribute("user", ldapUser);
        return "redirect:/oauth/third-party/autologin";
    }

    @GetMapping("/third-party/weibo")
    public String thirdPartyWeiBo(@RequestParam String code, HttpServletRequest request, HttpServletResponse response) throws Exception {
        String weiBoBaseUrl = "https://api.weibo.com";
        String baseUrl = "https://" + request.getServerName();
        String redirectUri = URLEncoder.encode(baseUrl + "/oauth/third-party/weibo", StandardCharsets.UTF_8);
        //获取token
        String grant_type = "authorization_code";
        String url = weiBoBaseUrl + "/oauth2/access_token?client_id=" + weiBoAppId + "&client_secret=" + weiBoSecret +
                "&grant_type=" + grant_type + "&code=" + code + "&redirect_uri=" + redirectUri;
        Map<String, Object> tokenMap;
        try {
            String tokenStr = restTemplate.postForObject(url, null, String.class);
            tokenMap = JsonUtil.jsonToMap(tokenStr);
        } catch (Exception e) {
            if (e instanceof HttpClientErrorException) {
                String errMsg = ((HttpClientErrorException) e).getResponseBodyAsString();
                log.error(errMsg);
                Map<String, Object> errMap = JsonUtil.jsonToMap(errMsg);
                throw new EsRuntimeException(null, String.valueOf(Objects.requireNonNull(errMap).get("error")));
            } else {
                throw new EsRuntimeException();
            }
        }

        String accessToken = Objects.requireNonNull(tokenMap).get("access_token").toString();
        String uid = tokenMap.get("uid").toString();
        //获取用户信息
        url = weiBoBaseUrl + "/2/users/show.json?access_token=" + accessToken + "&uid=" + uid;
        Map<String, Object> userMap;
        try {
            String userStr = restTemplate.getForObject(url, String.class);
            userMap = JsonUtil.jsonToMap(userStr);
        } catch (Exception e) {
            if (e instanceof HttpClientErrorException) {
                String errMsg = ((HttpClientErrorException) e).getResponseBodyAsString();
                log.error(errMsg);
                Map<String, Object> errMap = JsonUtil.jsonToMap(errMsg);
                throw new EsRuntimeException(null, String.valueOf(Objects.requireNonNull(errMap).get("error")));
            } else {
                throw new EsRuntimeException();
            }
        }
        //保存用户信息
        LdapUser ldapUser = new LdapUser();
        ldapUser.setWeiboid(uid);
        List<LdapUser> list = ldapUserService.search(ldapUser);
        if (list != null && list.size() > 0) {
            ldapUser = list.get(0);
        } else {
            ldapUser.setCn(uid);
            ldapUser.setSn(uid);
        }
        String location = Objects.requireNonNull(userMap).get("location").toString();
        ldapUser.setLocation(StringUtil.isEmpty(location) ? null : location);
        String province = userMap.get("province").toString();
        ldapUser.setProvince(StringUtil.isEmpty(province) ? null : province);
        String city = userMap.get("city").toString();
        ldapUser.setCity(StringUtil.isEmpty(city) ? null : city);
        String remark = userMap.get("description").toString();
        ldapUser.setRemark(StringUtil.isEmpty(remark) ? null : remark);
        ldapUser.setNickname(userMap.get("screen_name").toString());
        String sex = String.valueOf(userMap.get("gender"));
        ldapUser.setSex("m".equals(sex) ? 1 : ("f".equals(sex) ? 2 : 0));
        ldapUser.setHeadimg(ImgUtil.fromUrl(new URL(StringUtil.valueOf(userMap.get("profile_image_url")))));

        request.getSession().setAttribute("user", ldapUser);
        return "redirect:/oauth/third-party/autologin";
    }

    @GetMapping("/third-party/autologin")
    public String thirdPartyAutoLogin(HttpServletRequest request, HttpServletResponse response) throws Exception {
        LdapUser user = (LdapUser) request.getSession().getAttribute("user");
        LdapUser ldapUser;
        //保存用户信息
        List<LdapUser> list = ldapUserService.search(user);
        if (list != null && list.size() > 0) {
            ldapUser = list.get(0);
            BeanUtil.copyPropertiesIgnoreNull(user, ldapUser);
            ldapUserService.modifyLdapUser(ldapUser);
        } else {
            ldapUser = user;
            ldapUser.setUid(DataUtil.getUuid());
            ldapUser.setCn(ldapUser.getCn() == null ? ldapUser.getUid() : ldapUser.getCn());
            ldapUser.setSn(ldapUser.getSn() == null ? ldapUser.getUid() : ldapUser.getSn());
            String password = DataUtil.getRandom(false, 8);
            LdapShaPasswordEncoder encoder = new LdapShaPasswordEncoder();
            ldapUser.setUserPassword(encoder.encode(password).getBytes(StandardCharsets.UTF_8));
            ldapUserService.create(ldapUser);
        }
        //自动登录
        UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(ldapUser.getUid(), new String(ldapUser.getUserPassword(), StandardCharsets.UTF_8));
        try {
            token.setDetails(new WebAuthenticationDetails(request));
//            Authentication authenticatedUser = authenticationManager.authenticate(token);
//
//            SecurityContextHolder.getContext().setAuthentication(authenticatedUser);
            request.getSession().setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, SecurityContextHolder.getContext());
        } catch (AuthenticationException e) {
            log.error(e.getMessage(), e);
            return "redirect:/oauth/login";
        }
        RequestCache requestCache = new HttpSessionRequestCache();
        SavedRequest savedRequest = requestCache.getRequest(request, response);
        if (savedRequest != null) {
            String redirectUrl = savedRequest.getRedirectUrl();
            String clientId = HttpUtil.getUrlValue(redirectUrl, "client_id");
            LdapUserGroup org = ldapUserGroupService.findByClientId(clientId);
            if (org != null) {
                LdapContextSource contextSource = (LdapContextSource) ldapTemplate.getContextSource();
                org.getMember().add(contextSource.getBaseLdapName().addAll(ldapUser.getDn()));
                ldapUserGroupService.modify(org);
            }
            return "redirect:" + redirectUrl;
        }
        return "redirect:/oauth/login";
    }

//    @RequestMapping("/confirm_access")
//    public ModelAndView getAccessConfirmation(Map<String, Object> model, HttpServletRequest request) {
//        AuthorizationRequest authorizationRequest = (AuthorizationRequest) model.get("authorizationRequest");
//        ModelAndView view = new ModelAndView();
//        view.setViewName("login/authorize");
//        view.addObject("clientId", authorizationRequest.getClientId());
//        view.addObject("scopes", authorizationRequest.getScope());
//        return view;
//    }

    /**
     * 根据应用标识符获取客户ID
     *
     * @param appcn
     * @return
     */
    @RequestMapping(value = "/clientid", method = RequestMethod.GET)
    public Object findClientId(@RequestParam(name = "appcn") String appcn) {
        OauthClientEntity client = clientService.findByAppcn(appcn);
        if (client == null) {
            throw new EsRuntimeException(EsErrorConstants.DATA_NOT_FOUND);
        }
        return JsonResult.success(client.getClientId());
    }

    /**
     * 获取客户端信息
     *
     * @return
     */
    @RequestMapping(value = "/client/get", method = RequestMethod.GET)
    public Object find() {
        OauthClientEntity client = clientService.get(EsSecurityHandler.clientId());
        return JsonResult.success(client);
    }

    /**
     * 更新客户端信息
     *
     * @param client
     * @return
     */
    @RequestMapping(value = "/client/update", method = RequestMethod.POST)
    public Object updateClient(@RequestBody OauthClientEntity client) {
        client.setClientId(EsSecurityHandler.clientId());
        clientService.update(client);
        return JsonResult.success(client);
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
        LdapUser user = ldapUserService.findByUid(EsSecurityHandler.username());
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
        LdapUser u = ldapUserService.findByUid(EsSecurityHandler.username());
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
     * @throws Exception 异常信息
     */
    @GetMapping(value = "/user/password/change")
    @ResponseBody
    public Object changePassword(@RequestParam String oldPassword, @RequestParam String newPassword) throws Exception {
        LdapUser u = ldapUserService.findByUid(EsSecurityHandler.username());
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
        LdapUser u = ldapUserService.findByUid(EsSecurityHandler.username());
        if (u == null || StringUtil.isEmpty(u.getTelephoneNumber())) {
            throw new EsRuntimeException(UserErrorConstants.USER_NOT_EXIST);
        }
        u.setHeadimg(file.getBytes());
        ldapUserService.modifyLdapUser(u);
        return JsonResult.success();
    }
}
