package cn.jia.oauth.api;

import cn.jia.core.context.EsContextHolder;
import cn.jia.core.entity.JsonResult;
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
import com.nimbusds.oauth2.sdk.AuthorizationRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.LdapShaPasswordEncoder;
import org.springframework.security.web.WebAttributes;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.ModelAndView;

import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.*;

/**
 * OAuth认证控制器
 * 处理OAuth2认证流程，包括登录、第三方认证、客户端管理等功能
 */
@Slf4j
@Controller
@RequestMapping("/oauth")
@SessionAttributes("authorizationRequest")
@RequiredArgsConstructor
public class OauthController {
    private final ClientService clientService;
    private final LdapUserService ldapUserService;
    private final LdapUserGroupService ldapUserGroupService;

    private final RestTemplate restTemplate;

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

    /**
     * 处理用户登录请求
     * 根据不同的登录类型跳转到相应的认证页面
     *
     * @param request  HTTP请求对象
     * @param response HTTP响应对象
     * @return 登录视图或重定向URL
     */
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

    /**
     * 处理微信公众号第三方登录回调
     *
     * @param code     授权码
     * @param state    状态参数
     * @param request  HTTP请求对象
     * @param response HTTP响应对象
     * @return 重定向到自动登录处理
     * @throws Exception 处理过程中可能抛出的异常
     */
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

    /**
     * 处理微信第三方登录回调
     *
     * @param code     授权码
     * @param state    状态参数
     * @param request  HTTP请求对象
     * @param response HTTP响应对象
     * @return 重定向到自动登录处理
     * @throws Exception 处理过程中可能抛出的异常
     */
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

    /**
     * 处理微博第三方登录回调
     *
     * @param code     授权码
     * @param request  HTTP请求对象
     * @param response HTTP响应对象
     * @return 重定向到自动登录处理
     * @throws Exception 处理过程中可能抛出的异常
     */
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
        if (list != null && !list.isEmpty()) {
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

    /**
     * 第三方登录后自动注册和登录处理
     *
     * @param request  HTTP请求对象
     * @param response HTTP响应对象
     * @return 重定向到原始请求地址或登录页
     * @throws Exception 处理过程中可能抛出的异常
     */
    @GetMapping("/third-party/autologin")
    public String thirdPartyAutoLogin(HttpServletRequest request, HttpServletResponse response) throws Exception {
        LdapUser user = (LdapUser) request.getSession().getAttribute("user");
        LdapUser ldapUser;
        //保存用户信息
        List<LdapUser> list = ldapUserService.search(user);
        if (CollectionUtil.isNotNullOrEmpty(list)) {
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
                ldapUserGroupService.addUser(org, ldapUser.getDn());
            }
            return "redirect:" + redirectUrl;
        }
        return "redirect:/oauth/login";
    }

    /**
     * 获取访问确认页面
     *
     * @param model   模型数据
     * @param request HTTP请求对象
     * @return 访问确认视图
     */
    @RequestMapping("/confirm_access")
    public ModelAndView getAccessConfirmation(Map<String, Object> model, HttpServletRequest request) {
        AuthorizationRequest authorizationRequest = (AuthorizationRequest) model.get("authorizationRequest");
        ModelAndView view = new ModelAndView();
        view.setViewName("oauth/authorize");
        view.addObject("clientId", authorizationRequest.getClientID());
        view.addObject("scopes", authorizationRequest.getScope());
        return view;
    }

    /**
     * 根据应用标识符获取客户ID
     *
     * @param appcn 应用标识符
     * @return 客户端ID
     */
    @RequestMapping(value = "/clientid", method = RequestMethod.GET)
    @ResponseBody
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
     * @return 客户端信息
     */
    @RequestMapping(value = "/client/get", method = RequestMethod.GET)
    @ResponseBody
    public Object find() {
        OauthClientEntity client = clientService.get(EsContextHolder.getContext().getClientId());
        return JsonResult.success(client);
    }

    /**
     * 更新客户端信息
     *
     * @param client 客户端实体
     * @return 更新后的客户端信息
     */
    @RequestMapping(value = "/client/update", method = RequestMethod.POST)
    @ResponseBody
    public Object updateClient(@RequestBody OauthClientEntity client) {
        client.setClientId(EsContextHolder.getContext().getClientId());
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
    @ResponseBody
    public Object info(Principal user) {
        return JsonResult.success(user);
    }
}
