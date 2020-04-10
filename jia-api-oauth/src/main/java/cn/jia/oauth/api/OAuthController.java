package cn.jia.oauth.api;

import cn.jia.core.common.EsSecurityHandler;
import cn.jia.core.entity.JSONResult;
import cn.jia.core.exception.EsRuntimeException;
import cn.jia.core.service.DictService;
import cn.jia.core.util.*;
import cn.jia.oauth.common.Constants;
import cn.jia.oauth.common.ErrorConstants;
import cn.jia.oauth.entity.Client;
import cn.jia.oauth.entity.LdapUser;
import cn.jia.oauth.entity.LdapUserGroup;
import cn.jia.oauth.service.ClientService;
import cn.jia.oauth.service.LdapUserGroupService;
import cn.jia.oauth.service.LdapUserService;
import cn.jia.oauth.service.SmsService;
import cn.jia.sms.entity.SmsCode;
import cn.jia.sms.entity.SmsConfig;
import cn.jia.sms.entity.SmsSend;
import cn.jia.sms.entity.SmsTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.LdapContextSource;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.encoding.LdapShaPasswordEncoder;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.oauth2.provider.AuthorizationRequest;
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
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.*;

@Slf4j
@Controller
@RequestMapping("/oauth")
@SessionAttributes("authorizationRequest")
public class OAuthController {
	
	@Autowired
	private ClientService clientService;
	@Autowired
	private LdapUserService ldapUserService;
	@Autowired
	private LdapUserGroupService ldapUserGroupService;
	@Autowired
	private SmsService smsService;
	@Autowired
	private DictService dictService;
	@Autowired
	protected AuthenticationManager authenticationManager;
	@Autowired
	private LdapTemplate ldapTemplate;

	private RestTemplate restTemplate = new RestTemplate();

	@GetMapping("/login")
	public ModelAndView login(HttpServletRequest request, HttpServletResponse response) throws UnsupportedEncodingException {
		ModelAndView view = new ModelAndView();
		view.setViewName("login/login");
		String clientId = "jia_client";
		// 原请求信息的缓存及恢复
		RequestCache requestCache = new HttpSessionRequestCache();
		SavedRequest savedRequest = requestCache.getRequest(request, response);
		if (savedRequest != null) {
			String redirectUrl = savedRequest.getRedirectUrl();
			clientId = HttpUtil.getUrlValue(redirectUrl, "client_id");

			String loginType = HttpUtil.getUrlValue(redirectUrl, "login_type");

			if ("wxmp".equals(loginType)) {
				String baseUrl = "https://"+request.getServerName();
				String appid = "wx9faba829e04f348b";
				String redirect_uri = URLEncoder.encode(baseUrl+"/oauth/third-party/wxmp", "utf-8");
				String state = DataUtil.getRandom(true, 4);
                String loginScope = HttpUtil.getUrlValue(redirectUrl, "login_scope");
				String url= "https://open.weixin.qq.com/connect/oauth2/authorize?appid=" + appid + "&redirect_uri=" + redirect_uri + "&response_type=code&scope=" + loginScope + "&state=" + state + "#wechat_redirect";
				view.setViewName("redirect:" + url);
				return view;
			} else if ("weixin".equals(loginType)) {
				String baseUrl = "https://"+request.getServerName();
				String appid = "wxa2251bed81af2c56";
				String scope = "snsapi_login,snsapi_userinfo";
				String redirect_uri = URLEncoder.encode(baseUrl+"/oauth/third-party/weixin", "utf-8");
				String state = DataUtil.getRandom(true, 4);
				String url="https://open.weixin.qq.com/connect/qrconnect?appid="+appid+"&redirect_uri="+redirect_uri+"&response_type=code&scope="+scope+"&state="+state+"#wechat_redirect";
				view.setViewName("redirect:" + url);
				return view;
			} else if ("weibo".equals(loginType)) {
				String baseUrl = "https://"+request.getServerName();
				String appKey = "888001371";
				String redirectUri = URLEncoder.encode(baseUrl+"/oauth/third-party/weibo", "utf-8");
				String url = "https://api.weibo.com/oauth2/authorize?client_id="+appKey+"&response_type=code&redirect_uri="+redirectUri;
				view.setViewName("redirect:" + url);
				return view;
			}
		}
		LdapUserGroup org = ldapUserGroupService.findByClientId(clientId);
		view.addObject("org", org);
		view.addObject("orgLogo", Base64Util.encode(org.getLogo()));
		Object exception = request.getSession().getAttribute("SPRING_SECURITY_LAST_EXCEPTION");
		if(exception instanceof AuthenticationException) {
			view.addObject("error", ((AuthenticationException) exception).getMessage());
			request.getSession().removeAttribute("SPRING_SECURITY_LAST_EXCEPTION");
		}
		return view;
	}

    @PostMapping("/login")
    public String login(@RequestParam String username, @RequestParam String password,
                        @RequestParam String loginType,
                        HttpServletRequest request, HttpServletResponse response) {
        LdapUser ldapUser = new LdapUser();
		try {
			if("phone".equals(loginType)) {
				SmsCode smsCode = smsService.selectSmsCodeNoUsed(username, Constants.SMS_CODE_TYPE_LOGIN, Constants.DEFAULT_CLIENT_ID);
				if(smsCode == null || !password.equals(smsCode.getSmsCode())) {
					String errMessage = dictService.selectByDictTypeAndDictValue(Constants.DICT_TYPE_ERROR_CODE, ErrorConstants.SMS_CODE_WRONG).getName();
					request.getSession().setAttribute("SPRING_SECURITY_LAST_EXCEPTION", new UsernameNotFoundException(errMessage));
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
			String errMessage = dictService.selectByDictTypeAndDictValue(Constants.DICT_TYPE_ERROR_CODE, ErrorConstants.USER_NOT_EXIST).getName();
			request.getSession().setAttribute("SPRING_SECURITY_LAST_EXCEPTION", new UsernameNotFoundException(errMessage));
			return "redirect:/oauth/login";
		}

        UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(ldapUser.getUid(), password);
        try{
            token.setDetails(new WebAuthenticationDetails(request));
            Authentication authenticatedUser = authenticationManager.authenticate(token);

            SecurityContextHolder.getContext().setAuthentication(authenticatedUser);
            request.getSession().setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, SecurityContextHolder.getContext());
        } catch( AuthenticationException e ){
            String errMessage = dictService.selectByDictTypeAndDictValue(Constants.DICT_TYPE_ERROR_CODE, ErrorConstants.USER_PASSWORD_WRONG).getName();
            request.getSession().setAttribute("SPRING_SECURITY_LAST_EXCEPTION", new BadCredentialsException(errMessage));
            return "redirect:/oauth/login";
        }
        RequestCache requestCache = new HttpSessionRequestCache();
        SavedRequest savedRequest = requestCache.getRequest(request, response);
        if(savedRequest != null){
            String redirectUrl = savedRequest.getRedirectUrl();
            return "redirect:" + redirectUrl;
        }
        return "redirect:/oauth/login";
    }

	/**
	 * 生成验证码信息
	 * @param phone 电话号码
	 * @param smsType 验证码类型
	 * @return 最新验证码
	 */
	@RequestMapping(value = "/sms/gen", method = RequestMethod.GET)
	@ResponseBody
	public Object gen(@RequestParam String phone, @RequestParam Integer smsType, @RequestParam(value="templateId", required=false) String templateId,
					  HttpServletRequest request, HttpServletResponse response) throws Exception{
		//检查是否还有额度
		String clientId = Constants.DEFAULT_CLIENT_ID;
		RequestCache requestCache = new HttpSessionRequestCache();
		SavedRequest savedRequest = requestCache.getRequest(request, response);
		if (savedRequest != null) {
			String redirectUrl = savedRequest.getRedirectUrl();
			clientId = HttpUtil.getUrlValue(redirectUrl, "client_id");
		}
		SmsConfig config = smsService.selectConfig(clientId);
		if(config == null || config.getRemain() <= 0) {
			throw new EsRuntimeException(ErrorConstants.SMS_NOT_ENOUGH);
		}

		String smsCode = smsService.upsert(phone, smsType, clientId);

		templateId = StringUtils.isEmpty(templateId) ? Constants.SMS_CODE_TEMPLATE_ID : templateId;
		SmsTemplate template = smsService.findTemplate(templateId);
		if(template == null) {
			throw new EsRuntimeException(ErrorConstants.SMS_TEMPLATE_NOT_EXIST);
		}
		String content = "【" + config.getShortName() + "】" + smsService.findTemplate(templateId).getContent();
		content = content.replace("{0}", smsCode);
		String tkey = DateUtil.getDate("yyyyMMddHHmmss");
		String smsUsername = dictService.selectByDictTypeAndDictValue(Constants.DICT_TYPE_SMS_CONFIG, Constants.SMS_CONFIG_USERNAME).getName();
		String smsPassword = dictService.selectByDictTypeAndDictValue(Constants.DICT_TYPE_SMS_CONFIG, Constants.SMS_CONFIG_PASSWORD).getName();
		String passwd = MD5Util.str2Base32MD5(MD5Util.str2Base32MD5(smsPassword) + tkey);

		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

		MultiValueMap<String, String> map= new LinkedMultiValueMap<>();
		map.add("username", smsUsername);
		map.add("tkey", tkey);
		map.add("password", passwd);
		map.add("mobile", phone);
		map.add("content", content);

		HttpEntity<MultiValueMap<String, String>> smsRequest = new HttpEntity<>(map, headers);

		String sendSmsURL = "http://hy.mix2.zthysms.com/sendSms.do";
		ResponseEntity<String> smsResponse = restTemplate.postForEntity(sendSmsURL, smsRequest , String.class);
		//将发送记录保存到系统里
		if("1".equals(smsResponse.getBody().split(",")[0])){
			SmsSend smsSend = new SmsSend();
			smsSend.setClientId(clientId);
			smsSend.setContent(content);
			smsSend.setMobile(phone);
			smsSend.setMsgid(smsResponse.getBody().split(",")[1]);
			smsSend.setTime(DateUtil.genTime(new Date()));
			smsService.send(smsSend);
			return JSONResult.success(smsCode);
		}else {
			return JSONResult.failure("E999", smsResponse.getBody());
		}
	}

	/**
	 * 验证码已经被使用
	 * @param phone 手机号码
	 * @param smsType 短信类型
	 * @param smsCode 短信验证码
	 * @return 处理结果
	 * @throws Exception 异常
	 */
	@RequestMapping(value = "/sms/validate", method = RequestMethod.GET)
	@ResponseBody
	public Object validateSmsCode(@RequestParam String phone, @RequestParam Integer smsType, @RequestParam String smsCode,
                                  HttpServletRequest request, HttpServletResponse response) throws Exception {
        String clientId = Constants.DEFAULT_CLIENT_ID;
        RequestCache requestCache = new HttpSessionRequestCache();
        SavedRequest savedRequest = requestCache.getRequest(request, response);
        if (savedRequest != null) {
            String redirectUrl = savedRequest.getRedirectUrl();
            clientId = HttpUtil.getUrlValue(redirectUrl, "client_id");
        }
		SmsCode code = smsService.selectSmsCodeNoUsed(phone, smsType, clientId);
		if(code == null || !smsCode.equals(code.getSmsCode())) {
			throw new EsRuntimeException(ErrorConstants.DATA_NOT_FOUND);
		}
		smsService.useSmsCode(code.getId());
		return JSONResult.success();
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
		for(LdapUser u : userList) {
			if(u.getTelephoneNumber().equals(user.getTelephoneNumber())) {
				view.addObject("error", dictService.selectByDictTypeAndDictValue(Constants.DICT_TYPE_ERROR_CODE, ErrorConstants.USER_PHONE_HAS_EXIST).getName());
				return view;
			} else if (u.getEmail().equals(user.getEmail())) {
				view.addObject("error", dictService.selectByDictTypeAndDictValue(Constants.DICT_TYPE_ERROR_CODE, ErrorConstants.USER_EMAIL_HAS_EXIST).getName());
				return view;
			} else if (u.getCn().equals(user.getCn())) {
				view.addObject("error", dictService.selectByDictTypeAndDictValue(Constants.DICT_TYPE_ERROR_CODE, ErrorConstants.USER_HAS_EXIST).getName());
				return view;
			}
		}
        LdapShaPasswordEncoder encoder = new LdapShaPasswordEncoder();
        user.setUserPassword(encoder.encodePassword(new String(user.getUserPassword()), null).getBytes(StandardCharsets.UTF_8));
		ldapUserService.create(user);
		RequestCache requestCache = new HttpSessionRequestCache();
		SavedRequest savedRequest = requestCache.getRequest(request, response);
		if (savedRequest != null) {
			String redirectUrl = savedRequest.getRedirectUrl();
			String clientId = HttpUtil.getUrlValue(redirectUrl, "client_id");
			LdapUserGroup org = ldapUserGroupService.findByClientId(clientId);
			if(org != null) {
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
	 * @param smsCode 短信验证码
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
			view.addObject("error", dictService.selectByDictTypeAndDictValue(Constants.DICT_TYPE_ERROR_CODE, ErrorConstants.USER_NOT_EXIST).getName());
			return view;
		}
		String clientId = Constants.DEFAULT_CLIENT_ID;
		RequestCache requestCache = new HttpSessionRequestCache();
		SavedRequest savedRequest = requestCache.getRequest(request, response);
		if (savedRequest != null) {
			String redirectUrl = savedRequest.getRedirectUrl();
			clientId = HttpUtil.getUrlValue(redirectUrl, "client_id");
		}
		SmsCode code = smsService.selectSmsCodeNoUsed(u.getTelephoneNumber(), Constants.SMS_CODE_TYPE_RESETPWD, clientId);
		if(code == null || !smsCode.equals(code.getSmsCode())) {
			view.addObject("error", dictService.selectByDictTypeAndDictValue(Constants.DICT_TYPE_ERROR_CODE, ErrorConstants.SMS_CODE_WRONG).getName());
			return view;
		}
		smsService.useSmsCode(code.getId());
		LdapShaPasswordEncoder encoder = new LdapShaPasswordEncoder();
		u.setUserPassword(encoder.encodePassword(newPassword, null).getBytes(StandardCharsets.UTF_8));
		ldapUserService.modifyLdapUser(u);
        view.addObject("success", true);
		return view;
	}

	@GetMapping("/third-party/wxmp")
	public String thirdPartyWxMp(@RequestParam String code, @RequestParam String state,
								   HttpServletRequest request, HttpServletResponse response) throws Exception {
		String base_url = "https://api.weixin.qq.com";
		//获取token
		String appid = "wx9faba829e04f348b";
		String secret = "ae96222e32a0c1b9df168f00e6ba9815";
		String grant_type = "authorization_code";
		String url = base_url + "/sns/oauth2/access_token?appid=" + appid + "&secret=" + secret + "&grant_type=" + grant_type + "&code=" + code;
		String tokenStr = restTemplate.getForObject(url, String.class);
		Map<String, Object> tokenMap = JSONUtil.jsonToMap(tokenStr);
		if(Objects.requireNonNull(tokenMap).get("errcode") != null){
			throw new EsRuntimeException(null, String.valueOf(tokenMap.get("errmsg")));
		}
		String accessToken = tokenMap.get("access_token").toString();
		String openid = tokenMap.get("openid").toString();
		String scope = tokenMap.get("scope").toString();
//		String accessToken = "25_yx4ZTlwJTKskepwY2TtV2fthIx3Jp7wgzyGnDjQHpyWQEY3O0v2SSOrCAiGDZ38NuskYZixfLcbrAm7JSc_oshs0pjLyLP5PX54g8bXN5PM";
//		String openid = "o5iiqwUE9Uj_0XY5okQg8kyWb6HE";
		//保存用户信息
		LdapUser ldapUser = new LdapUser();
		ldapUser.setOpenid(openid);
		//获取用户信息
		if(scope.contains("snsapi_userinfo")) {
			url = base_url + "/sns/userinfo?access_token=" + accessToken + "&openid=" + openid;
			String userStr = restTemplate.getForObject(url, String.class);
			Map<String, Object> userMap = JSONUtil.jsonToMap(userStr);
			if(Objects.requireNonNull(userMap).get("errcode") != null){
				throw new EsRuntimeException(null, String.valueOf(userMap.get("errmsg")));
			}
			ldapUser.setWeixinid(userMap.get("unionid") == null ? null : String.valueOf(userMap.get("unionid")));
			String country = userMap.get("country").toString();
			ldapUser.setCountry(StringUtils.isEmpty(country) ? null : country);
			String province = userMap.get("province").toString();
			ldapUser.setProvince(StringUtils.isEmpty(province) ? null : province);
			String city = userMap.get("city").toString();
			ldapUser.setCity(StringUtils.isEmpty(city) ? null : city);
			ldapUser.setNickname(new String(userMap.get("nickname").toString().getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8));
			ldapUser.setSex((Integer)userMap.get("sex"));
			ldapUser.setHeadimg(ImgUtil.fromURL(new URL(StringUtils.valueOf(userMap.get("headimgurl")))));
		}

		request.getSession().setAttribute("user", ldapUser);
		return "redirect:/oauth/third-party/autologin";
	}

	@GetMapping("/third-party/weixin")
	public String thirdPartyWeiXin(@RequestParam String code, @RequestParam String state,
								  HttpServletRequest request, HttpServletResponse response) throws Exception {
		String base_url = "https://api.weixin.qq.com";
		//获取token
		String appid = "wxa2251bed81af2c56";
		String secret = "a270b060f3ba92ae55a13a122bafe100";
		String grant_type = "authorization_code";
		String url = base_url + "/sns/oauth2/access_token?appid=" + appid + "&secret=" + secret + "&grant_type=" + grant_type + "&code=" + code;
		String tokenStr = restTemplate.getForObject(url, String.class);
		Map<String, Object> tokenMap = JSONUtil.jsonToMap(tokenStr);
		if(Objects.requireNonNull(tokenMap).get("errcode") != null){
			throw new EsRuntimeException(null, String.valueOf(tokenMap.get("errmsg")));
		}
		String accessToken = tokenMap.get("access_token").toString();
		String openid = tokenMap.get("openid").toString();
		String unionid = tokenMap.get("unionid") == null ? openid : String.valueOf(tokenMap.get("unionid"));
//		String accessToken = "25_yx4ZTlwJTKskepwY2TtV2fthIx3Jp7wgzyGnDjQHpyWQEY3O0v2SSOrCAiGDZ38NuskYZixfLcbrAm7JSc_oshs0pjLyLP5PX54g8bXN5PM";
//		String openid = "o5iiqwUE9Uj_0XY5okQg8kyWb6HE";
		//保存用户信息
		LdapUser ldapUser = new LdapUser();
		ldapUser.setWeixinid(unionid);
		//获取用户信息
        url = base_url + "/sns/userinfo?access_token=" + accessToken + "&openid=" + openid;
        String userStr = restTemplate.getForObject(url, String.class);
        Map<String, Object> userMap = JSONUtil.jsonToMap(userStr);
        if(Objects.requireNonNull(userMap).get("errcode") != null){
            throw new EsRuntimeException(null, String.valueOf(userMap.get("errmsg")));
        }
        String country = userMap.get("country").toString();
        ldapUser.setCountry(StringUtils.isEmpty(country) ? null : country);
        String province = userMap.get("province").toString();
        ldapUser.setProvince(StringUtils.isEmpty(province) ? null : province);
        String city = userMap.get("city").toString();
        ldapUser.setCity(StringUtils.isEmpty(city) ? null : city);
        ldapUser.setNickname(new String(userMap.get("nickname").toString().getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8));
        ldapUser.setSex((Integer)userMap.get("sex"));
        ldapUser.setHeadimg(ImgUtil.fromURL(new URL(StringUtils.valueOf(userMap.get("headimgurl")))));

		request.getSession().setAttribute("user", ldapUser);
		return "redirect:/oauth/third-party/autologin";
	}

	@GetMapping("/third-party/weibo")
	public String thirdPartyWeiBo(@RequestParam String code, HttpServletRequest request, HttpServletResponse response) throws Exception {
		String base_url = "https://api.weibo.com";
		//获取token
		String appid = "888001371";
		String secret = "6f757edf4ee25af8fee2c844d14fc27f";
		String grant_type = "authorization_code";
		String url = base_url + "/oauth2/access_token?client_id=" + appid + "&client_secret=" + secret + "&grant_type=" + grant_type +
			"&code=" + code + "&redirect_uri=https://api.jia.wydiy.com/oauth/third-party/weibo";
        Map<String, Object> tokenMap;
        try {
            String tokenStr = restTemplate.postForObject(url, null, String.class);
            tokenMap = JSONUtil.jsonToMap(tokenStr);
        } catch (Exception e) {
            if (e instanceof HttpClientErrorException) {
                String errMsg = ((HttpClientErrorException) e).getResponseBodyAsString();
                log.error(errMsg);
                Map<String, Object> errMap = JSONUtil.jsonToMap(errMsg);
                throw new EsRuntimeException(null, String.valueOf(Objects.requireNonNull(errMap).get("error")));
            } else {
                throw new EsRuntimeException();
            }
        }

		String accessToken = Objects.requireNonNull(tokenMap).get("access_token").toString();
		String uid = tokenMap.get("uid").toString();
		//获取用户信息
		url = base_url + "/2/users/show.json?access_token=" + accessToken + "&uid=" + uid;
        Map<String, Object> userMap;
        try {
            String userStr = restTemplate.getForObject(url, String.class);
            userMap = JSONUtil.jsonToMap(userStr);
        } catch (Exception e) {
            if (e instanceof HttpClientErrorException) {
                String errMsg = ((HttpClientErrorException) e).getResponseBodyAsString();
                log.error(errMsg);
                Map<String, Object> errMap = JSONUtil.jsonToMap(errMsg);
                throw new EsRuntimeException(null, String.valueOf(Objects.requireNonNull(errMap).get("error")));
            } else {
                throw new EsRuntimeException();
            }
        }
		//保存用户信息
		LdapUser ldapUser = new LdapUser();
		ldapUser.setWeiboid(uid);
		List<LdapUser> list = ldapUserService.search(ldapUser);
		if(list != null && list.size() > 0){
			ldapUser = list.get(0);
		}else{
			ldapUser.setCn(uid);
			ldapUser.setSn(uid);
		}
		String location = Objects.requireNonNull(userMap).get("location").toString();
		ldapUser.setLocation(StringUtils.isEmpty(location) ? null : location);
		String province = userMap.get("province").toString();
		ldapUser.setProvince(StringUtils.isEmpty(province) ? null : province);
		String city = userMap.get("city").toString();
		ldapUser.setCity(StringUtils.isEmpty(city) ? null : city);
		String remark = userMap.get("description").toString();
		ldapUser.setRemark(StringUtils.isEmpty(remark) ? null : remark);
		ldapUser.setNickname(userMap.get("screen_name").toString());
		String sex = String.valueOf(userMap.get("gender"));
		ldapUser.setSex("m".equals(sex) ? 1 : ("f".equals(sex) ? 2 : 0));
		ldapUser.setHeadimg(ImgUtil.fromURL(new URL(StringUtils.valueOf(userMap.get("profile_image_url")))));

		request.getSession().setAttribute("user", ldapUser);
		return "redirect:/oauth/third-party/autologin";
	}

	@GetMapping("/third-party/autologin")
	public String thirdPartyAutoLogin(HttpServletRequest request, HttpServletResponse response) throws Exception {
		LdapUser user = (LdapUser) request.getSession().getAttribute("user");
		LdapUser ldapUser;
		//保存用户信息
		List<LdapUser> list = ldapUserService.search(user);
		if(list != null && list.size() > 0){
			ldapUser = list.get(0);
			BeanUtil.copyPropertiesIgnoreNull(user, ldapUser);
			ldapUserService.modifyLdapUser(ldapUser);
		}else{
			ldapUser = user;
			ldapUser.setUid(DataUtil.getUUID());
			ldapUser.setCn(ldapUser.getCn() == null ? ldapUser.getUid() : ldapUser.getCn());
			ldapUser.setSn(ldapUser.getSn() == null ? ldapUser.getUid() : ldapUser.getSn());
			String password = DataUtil.getRandom(false, 8);
			LdapShaPasswordEncoder encoder = new LdapShaPasswordEncoder();
			ldapUser.setUserPassword(encoder.encodePassword(password, null).getBytes(StandardCharsets.UTF_8));
			ldapUserService.create(ldapUser);
		}
		//自动登录
		UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(ldapUser.getUid(), new String(ldapUser.getUserPassword(), StandardCharsets.UTF_8));
		try{
			token.setDetails(new WebAuthenticationDetails(request));
			Authentication authenticatedUser = authenticationManager.authenticate(token);

			SecurityContextHolder.getContext().setAuthentication(authenticatedUser);
			request.getSession().setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, SecurityContextHolder.getContext());
		} catch( AuthenticationException e ){
			log.error(e.getMessage(), e);
			return "redirect:/oauth/login";
		}
		RequestCache requestCache = new HttpSessionRequestCache();
		SavedRequest savedRequest = requestCache.getRequest(request, response);
		if(savedRequest != null) {
			String redirectUrl = savedRequest.getRedirectUrl();
			String clientId = HttpUtil.getUrlValue(redirectUrl, "client_id");
			LdapUserGroup org = ldapUserGroupService.findByClientId(clientId);
			if(org != null) {
				LdapContextSource contextSource = (LdapContextSource) ldapTemplate.getContextSource();
				org.getMember().add(contextSource.getBaseLdapName().addAll(ldapUser.getDn()));
				ldapUserGroupService.modify(org);
			}
			return "redirect:" + redirectUrl;
		}
		return "redirect:/oauth/login";
	}

	@RequestMapping("/confirm_access")
	public ModelAndView getAccessConfirmation(Map<String, Object> model, HttpServletRequest request) {
		AuthorizationRequest authorizationRequest = (AuthorizationRequest) model.get("authorizationRequest");
		ModelAndView view = new ModelAndView();
		view.setViewName("login/authorize");
		view.addObject("clientId", authorizationRequest.getClientId());
		view.addObject("scopes",authorizationRequest.getScope());
		return view;
	}

	/**
	 * 根据应用标识码获取客户ID
	 * @param appcn 应用标识码
	 * @return 客户ID
	 */
	@GetMapping(value = "/clientid")
	@ResponseBody
	public Object findClientId(@RequestParam(name = "appcn") String appcn) throws Exception {
		Client client = clientService.findByAppcn(appcn);
		if(client == null) {
			throw new EsRuntimeException(ErrorConstants.DATA_NOT_FOUND);
		}
		return JSONResult.success(client.getClientId());
	}
	
	/**
	 * 获取客户端信息
	 * @return 客户端信息
	 */
	@GetMapping(value = "/client/get")
	@ResponseBody
	public Object find() {
		Client client = clientService.find(EsSecurityHandler.clientId());
		return JSONResult.success(client);
	}
	
	/**
	 * 更新客户端信息
	 * @param client 客户端信息
	 * @return 最新客户端信息
	 */
	@PostMapping(value = "/client/update")
	@ResponseBody
	public Object updateClient(@RequestBody Client client) {
		client.setClientId(EsSecurityHandler.clientId());
		clientService.update(client);
		return JSONResult.success(client);
	}

	/**
	 * 获取用户OAUTH2权限信息
	 * @param user 用户信息
	 * @return 用户信息
	 */
	@GetMapping("/user")
	@ResponseBody
	public Principal info(Principal user){
		return user;
	}

    @GetMapping(value = "/user/info")
    @ResponseBody
    public Object userInfo() throws Exception {
        LdapUser user = ldapUserService.findByUid(EsSecurityHandler.username());
        if(user == null) {
            throw new EsRuntimeException(ErrorConstants.USER_NOT_EXIST);
        }
        user.setUserPassword("***".getBytes());
        return JSONResult.success(user);
    }

	/**
	 * 更新用户信息
	 * @param user 用户信息
	 * @return 最新用户信息
	 * @throws Exception 异常信息
	 */
	@PostMapping(value = "/user/info/update")
	@ResponseBody
	public Object updateUser(LdapUser user) throws Exception {
		LdapUser u = ldapUserService.findByUid(EsSecurityHandler.username());
		if(u == null) {
			throw new EsRuntimeException(ErrorConstants.USER_NOT_EXIST);
		}
		BeanUtil.copyPropertiesIgnoreNull(user, u, "uid");
		ldapUserService.modifyLdapUser(u);
		u.setUserPassword("***".getBytes());
		return JSONResult.success(u);
	}

	/**
	 * 修改用户密码
	 * @param oldPassword 旧密码
	 * @param newPassword 新密码
	 * @return 处理结果
	 * @throws Exception 异常信息
	 */
	@GetMapping(value = "/user/password/change")
	@ResponseBody
	public Object changePassword(@RequestParam String oldPassword, @RequestParam String newPassword) throws Exception {
		LdapUser u = ldapUserService.findByUid(EsSecurityHandler.username());
		if(u == null) {
			throw new EsRuntimeException(ErrorConstants.USER_NOT_EXIST);
		}
		LdapShaPasswordEncoder encoder = new LdapShaPasswordEncoder();
		if(u.getUserPassword() != null && !Arrays.equals(u.getUserPassword(), encoder.encodePassword(oldPassword, null).getBytes(StandardCharsets.UTF_8))) {
			throw new EsRuntimeException(ErrorConstants.OLD_PASSWORD_WRONG);
		}
		u.setUserPassword(encoder.encodePassword(newPassword, null).getBytes(StandardCharsets.UTF_8));
		ldapUserService.modifyLdapUser(u);
		return JSONResult.success();
	}

	/**
	 * 上传用户头像
	 * @param file 头像文件
	 * @return 处理结果
	 * @throws Exception 异常信息
	 */
	@PostMapping(value = "/user/avatar/update")
	@ResponseBody
	public Object updateAvatar(@RequestPart MultipartFile file) throws Exception {
		LdapUser u = ldapUserService.findByUid(EsSecurityHandler.username());
		if(u == null || StringUtils.isEmpty(u.getTelephoneNumber())) {
			throw new EsRuntimeException(ErrorConstants.USER_NOT_EXIST);
		}
		u.setHeadimg(file.getBytes());
		ldapUserService.modifyLdapUser(u);
		return JSONResult.success();
	}
}
