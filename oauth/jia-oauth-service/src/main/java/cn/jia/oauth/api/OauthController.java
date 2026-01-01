package cn.jia.oauth.api;

import cn.jia.core.context.EsContextHolder;
import cn.jia.core.entity.JsonResult;
import cn.jia.core.exception.EsErrorConstants;
import cn.jia.core.exception.EsRuntimeException;
import cn.jia.core.util.CollectionUtil;
import cn.jia.core.util.HttpUtil;
import cn.jia.core.util.JsonUtil;
import cn.jia.core.util.StringUtil;
import cn.jia.isp.entity.IspFileEntity;
import cn.jia.isp.enums.IspFileTypeEnum;
import cn.jia.isp.service.FileService;
import cn.jia.user.entity.UserEntity;
import cn.jia.oauth.dto.GithubOauthTokenDTO;
import cn.jia.oauth.dto.GithubOauthUserDTO;
import cn.jia.oauth.dto.WeiBoOauthTokenDTO;
import cn.jia.oauth.dto.WeiBoOauthUserDTO;
import cn.jia.oauth.dto.WeiXinOauthTokenDTO;
import cn.jia.oauth.dto.WeiXinOauthUserDTO;
import cn.jia.oauth.entity.OauthClientEntity;
import cn.jia.oauth.service.ClientService;
import cn.jia.user.entity.PermsEntity;
import cn.jia.user.service.PermsService;
import cn.jia.user.service.UserService;
import com.nimbusds.oauth2.sdk.AuthorizationRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.*;
import java.util.stream.Collectors;

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
    private final UserService userService;
    private final PermsService permsService;
    private final FileService fileService;
    private final RestTemplate restTemplate;

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
    @Value("${oauth.third-party.github.appid:}")
    private String githubAppId;
    @Value("${oauth.third-party.github.secret:}")
    private String githubSecret;

    /**
     * 处理微信公众号第三方登录回调
     *
     * @param code     授权码
     * @param state    状态参数
     * @param request  HTTP请求对象
     * @return 重定向到自动登录处理
     */
    @GetMapping("/third-party/wxmp")
    public String thirdPartyWxMp(@RequestParam String code, @RequestParam String state,
                                 HttpServletRequest request) {
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
        UserEntity user = new UserEntity();
        user.setOpenid(openid);
        //获取用户信息
        if (scope.contains("snsapi_userinfo")) {
            url = base_url + "/sns/userinfo?access_token=" + accessToken + "&openid=" + openid;
            WeiXinOauthUserDTO userDTO = restTemplate.getForObject(url, WeiXinOauthUserDTO.class);
            if (StringUtil.isNotEmpty(Objects.requireNonNull(userDTO).getErrCode())) {
                throw new EsRuntimeException(null, userDTO.getErrMsg());
            }
            user.setWeixinid(userDTO.getUnionId());
            String country = userDTO.getCountry();
            user.setCountry(StringUtil.isEmpty(country) ? null : country);
            String province = userDTO.getProvince();
            user.setProvince(StringUtil.isEmpty(province) ? null : province);
            String city = userDTO.getCity();
            user.setCity(StringUtil.isEmpty(city) ? null : city);
            user.setNickname(new String(userDTO.getNickname().getBytes(StandardCharsets.ISO_8859_1),
                    StandardCharsets.UTF_8));
            user.setSex(userDTO.getSex());
            user.setAvatar(userDTO.getHeadImgUrl());
        }

        request.getSession().setAttribute("user", user);
        return "redirect:/oauth/third-party/autologin";
    }

    /**
     * 处理微信第三方登录回调
     *
     * @param code     授权码
     * @param state    状态参数
     * @param request  HTTP请求对象
     * @return 重定向到自动登录处理
     */
    @GetMapping("/third-party/weixin")
    public String thirdPartyWeiXin(@RequestParam String code, @RequestParam String state,
                                   HttpServletRequest request) {
        String baseUrl = "https://api.weixin.qq.com";
        // 获取token
        String url = baseUrl + "/sns/oauth2/access_token?appid=" + weiXinAppId + "&secret=" + weiXinSecret +
                "&grant_type=authorization_code&code=" + code;
        WeiXinOauthTokenDTO tokenDTO = restTemplate.getForObject(url, WeiXinOauthTokenDTO.class);
        if (Objects.requireNonNull(tokenDTO).getErrCode() != null && !tokenDTO.getErrCode().isEmpty()) {
            throw new EsRuntimeException(null, tokenDTO.getErrMsg());
        }
        String accessToken = tokenDTO.getAccessToken();
        String openid = tokenDTO.getOpenId();
        String unionid = StringUtil.isEmpty(tokenDTO.getUnionId()) ? openid : tokenDTO.getUnionId();

        // 获取用户信息
        url = baseUrl + "/sns/userinfo?access_token=" + accessToken + "&openid=" + openid;
        WeiXinOauthUserDTO userDTO = restTemplate.getForObject(url, WeiXinOauthUserDTO.class);
        if (Objects.requireNonNull(userDTO).getErrCode() != null && !userDTO.getErrCode().isEmpty()) {
            throw new EsRuntimeException(null, userDTO.getErrMsg());
        }

        // 保存用户信息
        UserEntity user = new UserEntity();
        user.setWeixinid(unionid);
        user.setOpenid(openid);
        String country = userDTO.getCountry();
        user.setCountry(StringUtil.isEmpty(country) ? null : country);
        String province = userDTO.getProvince();
        user.setProvince(StringUtil.isEmpty(province) ? null : province);
        String city = userDTO.getCity();
        user.setCity(StringUtil.isEmpty(city) ? null : city);
        user.setNickname(new String(userDTO.getNickname().getBytes(StandardCharsets.ISO_8859_1),
                StandardCharsets.UTF_8));
        user.setSex(userDTO.getSex());
        IspFileEntity ispFileEntity = fileService.create(userDTO.getHeadImgUrl(),
                IspFileTypeEnum.FILE_TYPE_AVATAR, openid + ".jpg");
        Optional.ofNullable(ispFileEntity).map(IspFileEntity::getUri).ifPresent(user::setAvatar);

        request.getSession().setAttribute("user", user);
        return "redirect:/oauth/third-party/autologin";
    }

    /**
     * 处理微博第三方登录回调
     *
     * @param code     授权码
     * @param request  HTTP请求对象
     * @return 重定向到自动登录处理
     */
    @GetMapping("/third-party/weibo")
    public String thirdPartyWeiBo(@RequestParam String code, HttpServletRequest request) {
        String weiBoBaseUrl = "https://api.weibo.com";
        String baseUrl = "https://" + request.getServerName();
        String redirectUri = URLEncoder.encode(baseUrl + "/oauth/third-party/weibo", StandardCharsets.UTF_8);
        // 获取token
        String url = weiBoBaseUrl + "/oauth2/access_token?client_id=" + weiBoAppId + "&client_secret=" + weiBoSecret +
                "&grant_type=authorization_code&code=" + code + "&redirect_uri=" + redirectUri;

        // 设置请求头
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        HttpEntity<?> entity = new HttpEntity<>(headers);
        
        WeiBoOauthTokenDTO tokenDTO;
        try {
            ResponseEntity<String> responseEntity = restTemplate.postForEntity(url, entity, String.class);
            String tokenStr = responseEntity.getBody();
            tokenDTO = JsonUtil.fromJson(tokenStr, WeiBoOauthTokenDTO.class);
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

        String accessToken = Objects.requireNonNull(tokenDTO).getAccessToken();
        String uid = tokenDTO.getUid();
        // 获取用户信息
        url = weiBoBaseUrl + "/2/users/show.json?access_token=" + accessToken + "&uid=" + uid;
        
        WeiBoOauthUserDTO userDTO;
        try {
            headers.clear();
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            HttpEntity<?> userEntity = new HttpEntity<>(headers);
            ResponseEntity<String> userResponseEntity = restTemplate.exchange(url, HttpMethod.GET, userEntity, String.class);
            String userStr = userResponseEntity.getBody();
            userDTO = JsonUtil.fromJson(userStr, WeiBoOauthUserDTO.class);
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
        
        // 保存用户信息
        UserEntity user = new UserEntity();
        user.setWeiboid(uid);
        String location = Objects.requireNonNull(userDTO).getLocation();
        user.setLocation(StringUtil.isEmpty(location) ? null : location);
        String province = userDTO.getProvince();
        user.setProvince(StringUtil.isEmpty(province) ? null : province);
        String city = userDTO.getCity();
        user.setCity(StringUtil.isEmpty(city) ? null : city);
        String remark = userDTO.getDescription();
        user.setRemark(StringUtil.isEmpty(remark) ? null : remark);
        user.setNickname(userDTO.getScreenName());
        String sex = userDTO.getGender();
        user.setSex("m".equals(sex) ? 1 : ("f".equals(sex) ? 2 : 0));
        IspFileEntity ispFileEntity = fileService.create(userDTO.getProfileImageUrl(),
                IspFileTypeEnum.FILE_TYPE_AVATAR, uid + ".jpg");
        Optional.ofNullable(ispFileEntity).map(IspFileEntity::getUri).ifPresent(user::setAvatar);
        request.getSession().setAttribute("user", user);
        return "redirect:/oauth/third-party/autologin";
    }

    /**
     * 处理GitHub第三方登录回调
     *
     * @param code     授权码
     * @param request  HTTP请求对象
     * @return 重定向到自动登录处理
     */
    @GetMapping("/third-party/github")
    public String thirdPartyGithub(@RequestParam String code, HttpServletRequest request) {
        String githubBaseUrl = "https://github.com";
        String apiBaseUrl = "https://api.github.com";
        // 获取token
        String url = githubBaseUrl + "/login/oauth/access_token?client_id=" + githubAppId + "&client_secret=" + githubSecret +
                "&code=" + code;

        // 设置请求头，接收JSON格式的响应
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        HttpEntity<?> entity = new HttpEntity<>(headers);
        ResponseEntity<String> responseEntity = restTemplate.postForEntity(url, entity, String.class);
        String tokenStr = responseEntity.getBody();
        GithubOauthTokenDTO tokenDTO = JsonUtil.fromJson(tokenStr, GithubOauthTokenDTO.class);
        String accessToken = Objects.requireNonNull(tokenDTO).getAccessToken();
        // 获取用户信息
        url = apiBaseUrl + "/user";
        headers.clear();
        headers.setBearerAuth(accessToken);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        HttpEntity<?> userEntity = new HttpEntity<>(headers);
        ResponseEntity<String> userResponseEntity = restTemplate.exchange(url, HttpMethod.GET, userEntity, String.class);
        String userStr = userResponseEntity.getBody();
        GithubOauthUserDTO userDTO = JsonUtil.fromJson(userStr, GithubOauthUserDTO.class);
        if (userDTO == null) {
            return "redirect:/login/index.html";
        }
        // 保存用户信息
        UserEntity user = new UserEntity();
        user.setGithubid(String.valueOf(userDTO.getId())); // 使用GitHub ID作为UID
        user.setUsername(userDTO.getLogin());
        user.setNickname(userDTO.getName() != null ? userDTO.getName() : userDTO.getLogin());
        user.setEmail(userDTO.getEmail());
        IspFileEntity ispFileEntity = fileService.create(userDTO.getAvatar_url(),
                IspFileTypeEnum.FILE_TYPE_AVATAR, userDTO.getId() + ".jpg");
        Optional.ofNullable(ispFileEntity).map(IspFileEntity::getUri).ifPresent(user::setAvatar);
        user.setLocation(userDTO.getLocation());
        user.setRemark(userDTO.getBio());

        request.getSession().setAttribute("user", user);
        return "redirect:/oauth/third-party/autologin";
    }

    /**
     * 第三方登录后自动注册和登录处理
     *
     * @param request  HTTP请求对象
     * @param response HTTP响应对象
     * @return 重定向到原始请求地址或登录页
     */
    @GetMapping("/third-party/autologin")
    public String thirdPartyAutoLogin(HttpServletRequest request, HttpServletResponse response) {
        UserEntity user = (UserEntity) request.getSession().getAttribute("user");
        RequestCache requestCache = new HttpSessionRequestCache();
        SavedRequest savedRequest = requestCache.getRequest(request, response);
        if (savedRequest == null) {
            return "redirect:/login/index.html";
        }
        String redirectUrl = savedRequest.getRedirectUrl();
        String clientId = HttpUtil.getUrlValue(redirectUrl, "client_id");
        EsContextHolder.getContext().setClientId(clientId);
        //保存用户信息
        user = userService.upsert(user);
        //自动登录
        // 用户已经在第三方登录中验证，直接创建已认证的Authentication对象
        // 获取用户的所有权限
        Collection<? extends GrantedAuthority> authorities = new ArrayList<>();
        if (user.getId() != null) {
            List<PermsEntity> authList = permsService.findByUserId(user.getId());
            if (CollectionUtil.isNotNullOrEmpty(authList)) {
                authorities = authList.stream()
                    .map(p -> new SimpleGrantedAuthority(p.getModule() + "-" + p.getFunc()))
                    .collect(Collectors.toList());
            }
        }
        UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
            user.getUsername(), null, authorities
        );
        authToken.setDetails(new WebAuthenticationDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authToken);
        request.getSession().setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                SecurityContextHolder.getContext());
        return "redirect:" + redirectUrl;
    }

    /**
     * 获取访问确认页面
     *
     * @param model   模型数据
     * @return 访问确认视图
     */
    @RequestMapping("/confirm_access")
    public ModelAndView getAccessConfirmation(Map<String, Object> model) {
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