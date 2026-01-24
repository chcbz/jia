package cn.jia.oauth.api;

import cn.jia.core.context.EsContext;
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
import jakarta.servlet.http.Cookie;
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
    public String thirdPartyWxMp(@RequestParam String code, @RequestParam String state, HttpServletRequest request) {
        log.info("处理微信公众号第三方登录回调，code: {}, state: {}", code, state);
        String base_url = "https://api.weixin.qq.com";
        //获取token
        String grant_type = "authorization_code";
        String url = base_url + "/sns/oauth2/access_token?appid=" + wxMpAppId + "&secret=" + wxMpSecret +
                "&grant_type=" + grant_type + "&code=" + code;
        log.debug("请求微信API获取token，URL: {}", url);
        
        // 设置请求头，确保接收JSON格式响应
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        HttpEntity<?> entity = new HttpEntity<>(headers);
        
        WeiXinOauthTokenDTO tokenDTO;
        try {
            ResponseEntity<String> responseEntity = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            String tokenStr = responseEntity.getBody();
            tokenDTO = JsonUtil.fromJson(tokenStr, WeiXinOauthTokenDTO.class);
        } catch (Exception e) {
            if (e instanceof HttpClientErrorException) {
                String errMsg = ((HttpClientErrorException) e).getResponseBodyAsString();
                log.error("获取微信公众号token失败: {}", errMsg);
                Map<String, Object> errMap = JsonUtil.jsonToMap(errMsg);
                throw new EsRuntimeException(null, String.valueOf(Objects.requireNonNull(errMap).get("errcode")));
            } else {
                log.error("获取微信公众号token时发生异常", e);
                throw new EsRuntimeException();
            }
        }
        
        if (StringUtil.isNotEmpty(Objects.requireNonNull(tokenDTO).getErrCode())) {
            log.error("获取微信公众号token失败，错误码: {}, 错误信息: {}", tokenDTO.getErrCode(), tokenDTO.getErrMsg());
            throw new EsRuntimeException(null, tokenDTO.getErrMsg());
        }
        String accessToken = tokenDTO.getAccessToken();
        String openid = tokenDTO.getOpenId();
        String scope = tokenDTO.getScope();
        log.debug("微信公众号token获取成功，openid: {}, scope: {}", openid, scope);
        //保存用户信息
        UserEntity user = new UserEntity();
        user.setOpenid(openid);
        //获取用户信息
        if (scope.contains("snsapi_userinfo")) {
            url = base_url + "/sns/userinfo?access_token=" + accessToken + "&openid=" + openid;
            log.debug("请求微信API获取用户信息，URL: {}", url);
            
            WeiXinOauthUserDTO userDTO;
            try {
                ResponseEntity<String> userResponseEntity = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
                String userStr = userResponseEntity.getBody();
                userDTO = JsonUtil.fromJson(userStr, WeiXinOauthUserDTO.class);
            } catch (Exception e) {
                if (e instanceof HttpClientErrorException) {
                    String errMsg = ((HttpClientErrorException) e).getResponseBodyAsString();
                    log.error("获取微信公众号用户信息失败: {}", errMsg);
                    Map<String, Object> errMap = JsonUtil.jsonToMap(errMsg);
                    throw new EsRuntimeException(null, String.valueOf(Objects.requireNonNull(errMap).get("errcode")));
                } else {
                    log.error("获取微信公众号用户信息时发生异常", e);
                    throw new EsRuntimeException();
                }
            }
            
            if (StringUtil.isNotEmpty(Objects.requireNonNull(userDTO).getErrCode())) {
                log.error("获取微信公众号用户信息失败，错误码: {}, 错误信息: {}", userDTO.getErrCode(), userDTO.getErrMsg());
                throw new EsRuntimeException(null, userDTO.getErrMsg());
            }
            user.setWeixinid(userDTO.getUnionId());
            String country = userDTO.getCountry();
            user.setCountry(StringUtil.isEmpty(country) ? null : country);
            String province = userDTO.getProvince();
            user.setProvince(StringUtil.isEmpty(province) ? null : province);
            String city = userDTO.getCity();
            user.setCity(StringUtil.isEmpty(city) ? null : city);
            user.setNickname(userDTO.getNickname());
            user.setSex(userDTO.getSex());
            user.setAvatar(userDTO.getHeadImgUrl());
            log.debug("微信公众号用户信息获取成功，昵称: {}", user.getNickname());
        }

        request.getSession().setAttribute("user", user);
        log.info("微信公众号第三方登录回调处理完成，用户openid: {}", openid);
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
    public String thirdPartyWeiXin(@RequestParam String code, @RequestParam String state, HttpServletRequest request) {
        log.info("处理微信第三方登录回调，code: {}, state: {}", code, state);
        String baseUrl = "https://api.weixin.qq.com";
        // 获取token
        String url = baseUrl + "/sns/oauth2/access_token?appid=" + weiXinAppId + "&secret=" + weiXinSecret +
                "&grant_type=authorization_code&code=" + code;
        log.debug("请求微信API获取token，URL: {}", url);
        
        // 设置请求头，确保接收JSON格式响应
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        HttpEntity<?> entity = new HttpEntity<>(headers);
        
        WeiXinOauthTokenDTO tokenDTO;
        try {
            ResponseEntity<String> responseEntity = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            String tokenStr = responseEntity.getBody();
            tokenDTO = JsonUtil.fromJson(tokenStr, WeiXinOauthTokenDTO.class);
        } catch (Exception e) {
            if (e instanceof HttpClientErrorException) {
                String errMsg = ((HttpClientErrorException) e).getResponseBodyAsString();
                log.error("获取微信token失败: {}", errMsg);
                Map<String, Object> errMap = JsonUtil.jsonToMap(errMsg);
                throw new EsRuntimeException(null, String.valueOf(Objects.requireNonNull(errMap).get("errcode")));
            } else {
                log.error("获取微信token时发生异常", e);
                throw new EsRuntimeException();
            }
        }
        
        if (Objects.requireNonNull(tokenDTO).getErrCode() != null && !tokenDTO.getErrCode().isEmpty()) {
            log.error("获取微信token失败，错误码: {}, 错误信息: {}", tokenDTO.getErrCode(), tokenDTO.getErrMsg());
            throw new EsRuntimeException(null, tokenDTO.getErrMsg());
        }
        String openid = tokenDTO.getOpenId();
        String unionid = StringUtil.isEmpty(tokenDTO.getUnionId()) ? openid : tokenDTO.getUnionId();
        log.debug("微信token获取成功，openid: {}, unionid: {}", openid, unionid);

        // 保存用户信息
        UserEntity user = new UserEntity();
        user.setWeixinid(unionid);
        user.setOpenid(openid);
        
        request.getSession().setAttribute("user", user);
        log.info("微信第三方登录回调处理完成，用户openid: {}", openid);
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
        log.info("处理微博第三方登录回调，code: {}", code);
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
            log.debug("请求微博API获取token，URL: {}", url);
            ResponseEntity<String> responseEntity = restTemplate.postForEntity(url, entity, String.class);
            String tokenStr = responseEntity.getBody();
            tokenDTO = JsonUtil.fromJson(tokenStr, WeiBoOauthTokenDTO.class);
        } catch (Exception e) {
            if (e instanceof HttpClientErrorException) {
                String errMsg = ((HttpClientErrorException) e).getResponseBodyAsString();
                log.error("获取微博token失败: {}", errMsg);
                Map<String, Object> errMap = JsonUtil.jsonToMap(errMsg);
                throw new EsRuntimeException(null, String.valueOf(Objects.requireNonNull(errMap).get("error")));
            } else {
                log.error("获取微博token时发生异常", e);
                throw new EsRuntimeException();
            }
        }

        String accessToken = Objects.requireNonNull(tokenDTO).getAccessToken();
        String uid = tokenDTO.getUid();
        log.debug("微博token获取成功，uid: {}", uid);
        // 获取用户信息
        url = weiBoBaseUrl + "/2/users/show.json?access_token=" + accessToken + "&uid=" + uid;
        
        WeiBoOauthUserDTO userDTO;
        try {
            headers.clear();
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            HttpEntity<?> userEntity = new HttpEntity<>(headers);
            log.debug("请求微博API获取用户信息，URL: {}", url);
            ResponseEntity<String> userResponseEntity = restTemplate.exchange(url, HttpMethod.GET, userEntity, String.class);
            String userStr = userResponseEntity.getBody();
            userDTO = JsonUtil.fromJson(userStr, WeiBoOauthUserDTO.class);
        } catch (Exception e) {
            if (e instanceof HttpClientErrorException) {
                String errMsg = ((HttpClientErrorException) e).getResponseBodyAsString();
                log.error("获取微博用户信息失败: {}", errMsg);
                Map<String, Object> errMap = JsonUtil.jsonToMap(errMsg);
                throw new EsRuntimeException(null, String.valueOf(Objects.requireNonNull(errMap).get("error")));
            } else {
                log.error("获取微博用户信息时发生异常", e);
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
        log.debug("微博用户信息获取成功，昵称: {}", user.getNickname());
        
        request.getSession().setAttribute("user", user);
        log.info("微博第三方登录回调处理完成，用户uid: {}", uid);
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
        log.info("处理GitHub第三方登录回调，code: {}", code);
        String githubBaseUrl = "https://github.com";
        String apiBaseUrl = "https://api.github.com";
        // 获取token
        String url = githubBaseUrl + "/login/oauth/access_token?client_id=" + githubAppId + "&client_secret=" + githubSecret +
                "&code=" + code;

        // 设置请求头，接收JSON格式的响应
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        HttpEntity<?> entity = new HttpEntity<>(headers);
        log.debug("请求GitHub API获取token，URL: {}", url);
        ResponseEntity<String> responseEntity = restTemplate.postForEntity(url, entity, String.class);
        String tokenStr = responseEntity.getBody();
        GithubOauthTokenDTO tokenDTO = JsonUtil.fromJson(tokenStr, GithubOauthTokenDTO.class);
        String accessToken = Objects.requireNonNull(tokenDTO).getAccessToken();
        log.debug("GitHub token获取成功，accessToken: {}", accessToken);
        // 获取用户信息
        url = apiBaseUrl + "/user";
        headers.clear();
        headers.setBearerAuth(accessToken);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        HttpEntity<?> userEntity = new HttpEntity<>(headers);
        log.debug("请求GitHub API获取用户信息，URL: {}", url);
        ResponseEntity<String> userResponseEntity = restTemplate.exchange(url, HttpMethod.GET, userEntity, String.class);
        String userStr = userResponseEntity.getBody();
        GithubOauthUserDTO userDTO = JsonUtil.fromJson(userStr, GithubOauthUserDTO.class);
        if (userDTO == null) {
            log.warn("GitHub用户信息获取失败，返回空数据");
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
        log.debug("GitHub用户信息获取成功，用户名: {}", user.getUsername());

        request.getSession().setAttribute("user", user);
        log.info("GitHub第三方登录回调处理完成，用户ID: {}", userDTO.getId());
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
        log.info("开始处理第三方登录自动登录流程");
        UserEntity user = (UserEntity) request.getSession().getAttribute("user");
        RequestCache requestCache = new HttpSessionRequestCache();
        SavedRequest savedRequest = requestCache.getRequest(request, response);
        if (savedRequest == null) {
            log.warn("没有找到保存的请求，重定向到登录页");
            return "redirect:/login/index.html";
        }
        String redirectUrl = savedRequest.getRedirectUrl();
        String clientId = HttpUtil.getUrlValue(redirectUrl, "client_id");
        log.debug("从重定向URL中获取到client_id: {}", clientId);
        EsContextHolder.getContext().setClientId(clientId);
        //保存用户信息
        user = userService.upsert(user);
        log.debug("用户信息保存完成，用户ID: {}", user.getId());
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
                log.debug("获取到用户权限数量: {}", authorities.size());
            }
        }
        UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
            user.getJiacn(), null, authorities
        );
        authToken.setDetails(new WebAuthenticationDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authToken);
        request.getSession().setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                SecurityContextHolder.getContext());
        EsContext context = EsContextHolder.getContext();
        context.setUsername(user.getUsername());
        context.setJiacn(user.getJiacn());
        Cookie cookie = EsContextHolder.genCookie();
        response.addCookie(cookie);
        log.info("第三方登录自动登录流程完成，重定向到: {}", redirectUrl);
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
        log.info("进入访问确认页面");
        AuthorizationRequest authorizationRequest = (AuthorizationRequest) model.get("authorizationRequest");
        ModelAndView view = new ModelAndView();
        view.setViewName("oauth/authorize");
        view.addObject("clientId", authorizationRequest.getClientID());
        view.addObject("scopes", authorizationRequest.getScope());
        log.debug("访问确认页面，客户端ID: {}, 作用域: {}", authorizationRequest.getClientID(), authorizationRequest.getScope());
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
        log.info("根据应用标识符获取客户ID，appcn: {}", appcn);
        OauthClientEntity client = clientService.findByAppcn(appcn);
        if (client == null) {
            log.warn("未找到对应的应用标识符: {}", appcn);
            throw new EsRuntimeException(EsErrorConstants.DATA_NOT_FOUND);
        }
        log.info("成功获取客户ID，appcn: {}, client_id: {}", appcn, client.getClientId());
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
        String clientId = EsContextHolder.getContext().getClientId();
        log.info("获取客户端信息，client_id: {}", clientId);
        OauthClientEntity client = clientService.get(clientId);
        log.debug("客户端信息获取完成，client_id: {}", clientId);
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
        String clientId = EsContextHolder.getContext().getClientId();
        log.info("更新客户端信息，client_id: {}", clientId);
        client.setClientId(clientId);
        clientService.update(client);
        log.info("客户端信息更新完成，client_id: {}", clientId);
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
        log.info("获取用户OAUTH2权限信息，用户名: {}", user.getName());
        return JsonResult.success(user);
    }
}