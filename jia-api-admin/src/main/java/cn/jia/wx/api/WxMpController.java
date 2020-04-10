package cn.jia.wx.api;

import cn.jia.core.common.EsSecurityHandler;
import cn.jia.core.entity.JSONRequestPage;
import cn.jia.core.entity.JSONResult;
import cn.jia.core.entity.JSONResultPage;
import cn.jia.core.exception.EsRuntimeException;
import cn.jia.core.util.BeanUtil;
import cn.jia.core.util.JSONUtil;
import cn.jia.core.util.StreamUtil;
import cn.jia.core.util.StringUtils;
import cn.jia.wx.common.ErrorConstants;
import cn.jia.wx.entity.MpInfo;
import cn.jia.wx.entity.MpUser;
import cn.jia.wx.service.MpInfoService;
import cn.jia.wx.service.MpUserService;
import com.github.pagehelper.Page;
import lombok.extern.slf4j.Slf4j;
import me.chanjar.weixin.common.api.WxConsts;
import me.chanjar.weixin.common.bean.menu.WxMenu;
import me.chanjar.weixin.common.error.WxErrorException;
import me.chanjar.weixin.mp.api.WxMpService;
import me.chanjar.weixin.mp.bean.kefu.WxMpKefuMessage;
import me.chanjar.weixin.mp.bean.material.WxMpMaterial;
import me.chanjar.weixin.mp.bean.material.WxMpMaterialNews;
import me.chanjar.weixin.mp.bean.material.WxMpMaterialNews.WxMpMaterialNewsArticle;
import me.chanjar.weixin.mp.bean.material.WxMpMaterialUploadResult;
import me.chanjar.weixin.mp.bean.result.WxMpOAuth2AccessToken;
import me.chanjar.weixin.mp.bean.result.WxMpUserList;
import me.chanjar.weixin.mp.bean.template.WxMpTemplateMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 微信公众号接口
 * @author chc
 * @date 2018年1月14日 下午4:53:24
 */
@Slf4j
@RestController
@RequestMapping("/wx/mp")
public class WxMpController {
	
	@Autowired
	private MpInfoService mpInfoService;
	@Autowired
	private RestTemplate restTemplate;
	@Autowired
	private MpUserService mpUserService;


	/**
	 * 生成JS-SDK签名信息
	 * @param url
	 * @param request
	 * @return
	 * @throws Exception
	 */
	@RequestMapping(value = "/createJsapiSignature", method = RequestMethod.GET)
	public Object createJsapiSignature(@RequestParam("url") String url, HttpServletRequest request) throws Exception {
		return JSONResult.success(mpInfoService.findWxMpService(request).createJsapiSignature(url));
	}
	
	/**
	 * 自定义菜单创建
	 * @param menu 菜单JSON字符串
	 * @param request
	 * @return
	 * @throws Exception
	 */
	@PreAuthorize("hasAuthority('wxmp-menu_create')")
	@RequestMapping("/menu/create")
	public Object menuCreate(@RequestBody WxMenu menu, HttpServletRequest request) throws Exception {
		mpInfoService.findWxMpService(request).getMenuService().menuCreate(menu);
		return JSONResult.success();
	}
	
	/**
	 * 自定义菜单查询接口
	 * @param request
	 * @return
	 * @throws Exception
	 */
	@PreAuthorize("hasAuthority('wxmp-menu_get')")
	@RequestMapping("/menu/get")
	public Object menuGet(HttpServletRequest request) throws Exception {
		return JSONResult.success(mpInfoService.findWxMpService(request).getMenuService().menuGet());
	}
	
	/**
	 * 自定义菜单删除
	 * @param request
	 * @return
	 * @throws Exception
	 */
	@PreAuthorize("hasAuthority('wxmp-menu_delete')")
	@RequestMapping("/menu/delete")
	public Object menuDelete(HttpServletRequest request) throws Exception {
		mpInfoService.findWxMpService(request).getMenuService().menuDelete();
		return JSONResult.success();
	}
	
	/**
	 * 将公众号的用户同步到自己系统
	 * @param request
	 * @return
	 * @throws Exception
	 */
	@PreAuthorize("hasAuthority('wxmp-user_sync')")
	@RequestMapping("/user/sync")
	public Object userSync(HttpServletRequest request) throws Exception {
		try {
			String appid = request.getParameter("appid");
			WxMpService wxMpService = mpInfoService.findWxMpService(appid);
			WxMpUserList userList = wxMpService.getUserService().userList(null);
			String clientId = EsSecurityHandler.clientId();

			do {
				List<String> openids = userList.getOpenids();
				List<String> batchList = new ArrayList<>();
				for(int i=0;i<openids.size();i++) {
					batchList.add(openids.get(i));
					if(i!=0 && ((i+1)%100==0 || i==openids.size()-1)) {
						List<MpUser> users = new ArrayList<>();
						wxMpService.getUserService().userInfoList(batchList).forEach(wxMpUser -> {
								MpUser user = new MpUser();
								BeanUtil.copyPropertiesIgnoreNull(wxMpUser, user);
								user.setClientId(clientId);
								user.setAppid(appid);
								users.add(user);
							});

						mpUserService.sync(users);
						batchList = new ArrayList<>();
					}
				}
				userList = wxMpService.getUserService().userList(userList.getNextOpenid());
			}while(StringUtils.isNotEmpty(userList.getNextOpenid()));
		} catch (WxErrorException e) {
			throw new EsRuntimeException("", e.getError().getErrorMsg());
		}
		return JSONResult.success();
	}
	
	/**
	 * 拉取用户信息(需scope为 snsapi_userinfo)
	 * @param accessToken
	 * @param openId
	 * @param lang
	 * @param request
	 * @return
	 * @throws Exception
	 */
	@PreAuthorize("hasAuthority('wxmp-oauth2_userinfo')")
	@RequestMapping("/oauth2/userinfo")
	public Object oauth2Userinfo(@RequestParam("access_token") String accessToken,
			@RequestParam("openid") String openId, @RequestParam("lang") String lang, HttpServletRequest request)
			throws Exception {
		WxMpOAuth2AccessToken oAuth2AccessToken = new WxMpOAuth2AccessToken();
		oAuth2AccessToken.setOpenId(openId);
		oAuth2AccessToken.setAccessToken(accessToken);
		return JSONResult.success(mpInfoService.findWxMpService(request).oauth2getUserInfo(oAuth2AccessToken, lang));
	}
	
	/**
	 * 刷新access_token（如果需要）
	 * @param refreshToken
	 * @param request
	 * @return
	 * @throws Exception
	 */
	@PreAuthorize("hasAuthority('wxmp-oauth2_refresh_token')")
	@RequestMapping("/oauth2/refresh_token")
	public Object oauth2RefreshToken(@RequestParam("refresh_token") String refreshToken, HttpServletRequest request)
			throws Exception {
		return JSONResult.success(mpInfoService.findWxMpService(request).oauth2refreshAccessToken(refreshToken));
	}

	/**
	 * 检验授权凭证（access_token）是否有效
	 * @param accessToken
	 * @param openId
	 * @param request
	 * @return
	 * @throws Exception
	 */
	@PreAuthorize("hasAuthority('wxmp-oauth2_auth')")
	@RequestMapping("/oauth2/auth")
	public Object oauth2ValidateAccessToken(@RequestParam("access_token") String accessToken,
			@RequestParam("openid") String openId, HttpServletRequest request) throws Exception {
		WxMpOAuth2AccessToken oAuth2AccessToken = new WxMpOAuth2AccessToken();
		oAuth2AccessToken.setOpenId(openId);
		oAuth2AccessToken.setAccessToken(accessToken);
		return JSONResult.success(mpInfoService.findWxMpService(request).oauth2validateAccessToken(oAuth2AccessToken));
	}
	
	/**
	 * 获取所有客服账号
	 * @param request
	 * @return
	 * @throws Exception
	 */
	@PreAuthorize("hasAuthority('wxmp-customservice_getkflist')")
	@RequestMapping("/customservice/getkflist")
	public Object getkflist(HttpServletRequest request) throws Exception {
		return JSONResult.success(mpInfoService.findWxMpService(request).getKefuService().kfList());
	}
	
	/**
	 * 获取未接入会话列表
	 * @param request
	 * @return
	 * @throws Exception
	 */
	@PreAuthorize("hasAuthority('wxmp-customservice_kfsession_getwaitcase')")
	@RequestMapping("/customservice/kfsession/getwaitcase")
	public Object getwaitcase(HttpServletRequest request) throws Exception {
		return JSONResult.success(mpInfoService.findWxMpService(request).getKefuService().kfSessionGetWaitCase().getKfSessionWaitCaseList());
	}
	
	/**
	 * 客服接口-发消息
	 * @param message
	 * @param request
	 * @return
	 * @throws Exception
	 */
	@PreAuthorize("hasAuthority('wxmp-message_custom_send')")
	@RequestMapping("/message/custom/send")
	public Object customMessageSend(@RequestBody WxMpKefuMessage message, HttpServletRequest request) throws Exception {
		return JSONResult.success(mpInfoService.findWxMpService(request).getKefuService().sendKefuMessage(message));
	}
	
	/**
	 * 发送模板消息
	 * @param templateMessage
	 * @param request
	 * @return
	 * @throws Exception
	 */
	@PreAuthorize("hasAuthority('wxmp-message_template_send')")
	@RequestMapping("/message/template/send")
	public Object templateMessageSend(@RequestBody WxMpTemplateMessage templateMessage, HttpServletRequest request) throws Exception {
		return JSONResult.success(mpInfoService.findWxMpService(request).getTemplateMsgService().sendTemplateMsg(templateMessage));
	}
	
	/**
	 * 获取素材列表
	 * @param type
	 * @param offset
	 * @param count
	 * @param request
	 * @return
	 * @throws Exception
	 */
	@PreAuthorize("hasAuthority('wxmp-material_batchget')")
	@RequestMapping("/material/batchget")
	public Object materialBatchGet(@RequestParam("type") String type, @RequestParam("offset") Integer offset,
			@RequestParam("count") Integer count, HttpServletRequest request) throws Exception {
		if(WxConsts.MaterialType.NEWS.equals(type)) {
			return JSONResult.success(mpInfoService.findWxMpService(request).getMaterialService().materialNewsBatchGet(offset, count));
		}else {
			return JSONResult.success(mpInfoService.findWxMpService(request).getMaterialService().materialFileBatchGet(type, offset, count));
		}
		
	}
	
	/**
	 * 添加图文素材
	 * @param news
	 * @param request
	 * @return
	 * @throws Exception
	 */
	@PreAuthorize("hasAuthority('wxmp-material_add_news')")
	@RequestMapping("/material/add_news")
	public Object materialAddNews(@RequestBody WxMpMaterialNews news, HttpServletRequest request) throws Exception {
		for(WxMpMaterialNewsArticle article : news.getArticles()) {
			article.setContent(thsrc(article.getContent(), 0, request));
		}
		WxMpMaterialUploadResult result = mpInfoService.findWxMpService(request).getMaterialService().materialNewsUpload(news);
		if(result != null && StringUtils.isNotEmpty(result.getMediaId())) {
			return JSONResult.success(result);
		}else {
			return JSONResult.failure(String.valueOf(Objects.requireNonNull(result).getErrCode()), result.getErrMsg());
		}
	}
	
	/**
	 * 替换字符串中的src
	 * @param ynr 图文内容
	 * @param index 从第几个字符开始替换
	 * @return
	 */
	private String thsrc(String ynr, int index, HttpServletRequest request) throws Exception {
		int srcStart = ynr.indexOf("src=\"", index); // 获取src出现的位置
		int srcEnd = ynr.indexOf("\"", srcStart + 7);
		srcStart = srcStart + 5;
		String src = ynr.substring(srcStart, srcEnd); // 获取图片路径
		//下载文件到本地
		byte[] content = restTemplate.getForObject(src, byte[].class);
		File tempFile = File.createTempFile("wximg", ".jpg");
		StreamUtil.io(new ByteArrayInputStream(content), new FileOutputStream(tempFile));
		// 执行上传图片方法
		String newPath = mpInfoService.findWxMpService(request).getMaterialService().mediaImgUpload(tempFile).getUrl();
		// 替换字符串中该图片路径
		ynr = ynr.replace(src, newPath);
		// 查看字符串下方是否还有img标签
		int sfhyImg = ynr.indexOf("<img", srcEnd);
		if (sfhyImg == -1) {
			return ynr;
		} else {
			return thsrc(ynr, srcEnd, request);
		}
	}
	
	/**
	 *
	 * @param file 素材文件
	 * @param type 类型
	 * @param request
	 * @return
	 * @throws Exception
	 */
	@PreAuthorize("hasAuthority('wxmp-material_add_material')")
	@RequestMapping("/material/add_material")
	public Object materialAdd(@RequestPart(required = false, value = "file") MultipartFile file, @RequestParam(value="type") String type, 
		HttpServletRequest request) throws Exception {
		WxMpMaterial material = new WxMpMaterial();
		String filePath = request.getServletContext().getRealPath("/")+"tmp/";
		File pathFile = new File(filePath);
		if(!pathFile.exists()) {
			pathFile.mkdirs();
		}
		File tmpFile = new File(filePath+file.getOriginalFilename());
		file.transferTo(tmpFile);
		material.setFile(tmpFile);
		WxMpMaterialUploadResult result = mpInfoService.findWxMpService(request).getMaterialService().materialFileUpload(type, material);
		if(result != null && StringUtils.isNotEmpty(result.getMediaId())) {
			return JSONResult.success(result);
		}else {
			return JSONResult.failure(String.valueOf(result.getErrCode()), result.getErrMsg());
		}
	}
	
	/**
	 * 获取公众号信息
	 * @param id
	 * @return
	 */
	@PreAuthorize("hasAuthority('wxmp-info_get')")
	@RequestMapping(value = "/info/get", method = RequestMethod.GET)
	public Object findMpInfoById(@RequestParam(name = "id", required = true) Integer id) throws Exception {
		MpInfo info = mpInfoService.find(id);
		if(info == null) {
			throw new EsRuntimeException(ErrorConstants.DATA_NOT_FOUND);
		}
		return JSONResult.success(info);
	}

	/**
	 * 创建公众号
	 * @param info
	 * @return
	 */
	@PreAuthorize("hasAuthority('wxmp-info_create')")
	@RequestMapping(value = "/info/create", method = RequestMethod.POST)
	public Object createMpInfo(@RequestBody MpInfo info) {
		info.setClientId(EsSecurityHandler.clientId());
		mpInfoService.create(info);
		return JSONResult.success(info);
	}

	/**
	 * 更新公众号信息
	 * @param info
	 * @return
	 */
	@PreAuthorize("hasAuthority('wxmp-info_update')")
	@RequestMapping(value = "/info/update", method = RequestMethod.POST)
	public Object updateMpInfo(@RequestBody MpInfo info) {
		mpInfoService.update(info);
		return JSONResult.success(info);
	}

	/**
	 * 删除公众号
	 * @param id
	 * @return
	 */
	@PreAuthorize("hasAuthority('wxmp-info_delete')")
	@RequestMapping(value = "/info/delete", method = RequestMethod.GET)
	public Object deleteMpInfo(@RequestParam(name = "id") Integer id) {
		mpInfoService.delete(id);
		return JSONResult.success();
	}
	
	/**
	 * 公众号列表
	 * @param page
	 * @param request
	 * @return
	 */
	@PreAuthorize("hasAuthority('wxmp-info_list')")
	@RequestMapping(value = "/info/list", method = RequestMethod.POST)
	public Object listMpInfo(@RequestBody JSONRequestPage<String> page, HttpServletRequest request) {
		MpInfo example = JSONUtil.fromJson(page.getSearch(), MpInfo.class);
		if(example == null) {
			example = new MpInfo();
		}
		example.setClientId(EsSecurityHandler.clientId());
		Page<MpInfo> list = mpInfoService.list(example, page.getPageNum(), page.getPageSize());
		JSONResultPage<Object> result = new JSONResultPage<>(list.getResult());
		result.setPageNum(list.getPageNum());
		result.setTotal(list.getTotal());
		return result;
	}

	/**
	 * 获取公众号用户信息
	 * @param id 用户ID
	 * @return
	 */
	@PreAuthorize("hasAuthority('wxmp-user_get')")
	@RequestMapping(value = "/user/get", method = RequestMethod.GET)
	public Object findMpUserById(@RequestParam(name = "id") Integer id) throws Exception {
		MpUser user = mpUserService.find(id);
		if(user == null) {
			throw new EsRuntimeException(ErrorConstants.DATA_NOT_FOUND);
		}
		return JSONResult.success(user);
	}

	/**
	 * 公众号用户列表
	 * @param page 分页查询信息
	 * @return
	 */
	@PreAuthorize("hasAuthority('wxmp-user_list')")
	@RequestMapping(value = "/user/list", method = RequestMethod.POST)
	public Object listMpUser(@RequestBody JSONRequestPage<String> page) {
		MpUser example = JSONUtil.fromJson(page.getSearch(), MpUser.class);
		if(example == null) {
			example = new MpUser();
		}
		example.setClientId(EsSecurityHandler.clientId());
		Page<MpUser> list = mpUserService.list(example, page.getPageNum(), page.getPageSize());
		JSONResultPage<Object> result = new JSONResultPage<>(list.getResult());
		result.setPageNum(list.getPageNum());
		result.setTotal(list.getTotal());
		return result;
	}
}
