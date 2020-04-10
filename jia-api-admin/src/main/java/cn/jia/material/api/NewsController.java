package cn.jia.material.api;

import cn.jia.core.common.EsSecurityHandler;
import cn.jia.core.entity.JSONRequestPage;
import cn.jia.core.entity.JSONResult;
import cn.jia.core.entity.JSONResultPage;
import cn.jia.core.exception.EsRuntimeException;
import cn.jia.core.service.DictService;
import cn.jia.core.util.*;
import cn.jia.isp.entity.LdapUser;
import cn.jia.isp.service.LdapUserService;
import cn.jia.material.common.Constants;
import cn.jia.material.common.ErrorConstants;
import cn.jia.material.entity.News;
import cn.jia.material.service.NewsService;
import cn.jia.sms.entity.SmsConfig;
import cn.jia.sms.entity.SmsSend;
import cn.jia.sms.service.SmsService;
import cn.jia.wx.service.MpInfoService;
import com.github.pagehelper.Page;
import lombok.extern.slf4j.Slf4j;
import me.chanjar.weixin.common.error.WxErrorException;
import me.chanjar.weixin.mp.bean.kefu.WxMpKefuMessage;
import me.chanjar.weixin.mp.bean.material.WxMpMaterial;
import me.chanjar.weixin.mp.bean.material.WxMpMaterialNews;
import me.chanjar.weixin.mp.bean.material.WxMpMaterialUploadResult;
import org.apache.commons.fileupload.disk.DiskFileItem;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.io.IOUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.HtmlUtils;

import javax.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@RestController
@RequestMapping("/news")
@Slf4j
public class NewsController {
	
	@Autowired
	private NewsService newsService;
	@Autowired
	private DictService dictService;
	@Autowired
	private LdapUserService ldapUserService;
	@Autowired
	private MpInfoService mpInfoService;
	@Autowired
	private RestTemplate restTemplate;
	@Value("${jia.file.path}")
	private String webRealPath;
	@Autowired
	private SmsService smsService;
	
	/**
	 * 获取图文信息
	 * @param id 图文ID
	 * @return 图文信息
	 */
	@PreAuthorize("hasAuthority('news-get')")
	@RequestMapping(value = "/get", method = RequestMethod.GET)
	public Object findById(@RequestParam(name = "id") Integer id) throws Exception{
		News news = newsService.find(id);
		if(news == null) {
			throw new EsRuntimeException(ErrorConstants.MEDIA_NOT_EXIST);
		}
		return JSONResult.success(news);
	}

	/**
	 * 创建图文
	 * @param news 图文信息
	 * @return 图文信息
	 */
	@PreAuthorize("hasAuthority('news-create')")
	@RequestMapping(value = "/create", method = RequestMethod.POST)
	public Object create(@RequestBody News news) {
		news.setClientId(EsSecurityHandler.clientId());
		newsService.create(news);
		return JSONResult.success(news);
	}

	/**
	 * 更新图文信息
	 * @param news 图文信息
	 * @return 图文信息
	 */
	@PreAuthorize("hasAuthority('news-update')")
	@RequestMapping(value = "/update", method = RequestMethod.POST)
	public Object update(@RequestBody News news) {
		newsService.update(news);
		return JSONResult.success();
	}

	/**
	 * 删除图文
	 * @param id 图文ID
	 * @return 处理结果
	 */
	@PreAuthorize("hasAuthority('news-delete')")
	@RequestMapping(value = "/delete", method = RequestMethod.GET)
	public Object delete(@RequestParam(name = "id") Integer id) {
		newsService.delete(id);
		return JSONResult.success();
	}
	
	/**
	 * 获取所有图文信息
	 * @param  page 分页搜索信息
	 * @return 图文列表
	 */
	@PreAuthorize("hasAuthority('news-list')")
	@RequestMapping(value = "/list", method = RequestMethod.POST)
	public Object list(@RequestBody JSONRequestPage<String> page) {
		News example = JSONUtil.fromJson(page.getSearch(), News.class);
		example = example == null ? new News() : example;
		example.setClientId(EsSecurityHandler.clientId());
		Page<News> newsList = newsService.list(page.getPageNum(), page.getPageSize(), example);
		/*String material_url = dictService.selectByDictTypeAndDictValue(Constants.DICT_TYPE_MODULE_URL, Constants.MODULE_URL_MATERIAL).getName();
		List<News> newss = newsList.getResult().stream().peek(news -> {
			news.setPicurl(material_url +"/"+news.getPicurl());
			news.setBodyurl(material_url +"/"+news.getBodyurl());
		}).collect(Collectors.toList());*/
		@SuppressWarnings({ "rawtypes", "unchecked" })
        JSONResultPage<Object> result = new JSONResultPage(newsList.getResult());
		result.setPageNum(newsList.getPageNum());
		result.setTotal(newsList.getTotal());
		return result;
	}

	/**
	 * 发送图文信息
	 * @param jiacn 用户jiacn
	 * @param type 发送类型
	 * @param id 图文ID
	 * @param wxAppId 微信APPID
	 * @param request HTTP请求信息
	 * @return 处理结果
	 * @throws Exception 异常信息
	 */
//	@PreAuthorize("hasAuthority('news-send')")
	@RequestMapping(value = "/send", method = RequestMethod.GET)
	public Object send(@RequestParam(name = "jiacn") String jiacn, @RequestParam(name = "type") Integer type,
					   @RequestParam(name = "id") Integer id, @RequestParam(value="wxappid", required=false) String wxAppId,
					   HttpServletRequest request) throws Exception {
		News news = newsService.find(id);
		if(news == null) {
			throw new EsRuntimeException(ErrorConstants.MEDIA_NOT_EXIST);
		}
		// 检查用户是否存在
		LdapUser user = ldapUserService.findByUid(jiacn);
		if (user == null) {
			throw new EsRuntimeException(ErrorConstants.USER_NOT_EXIST);
		}

		if(Constants.SEND_TYPE_WX.equals(type)) {
			if(StringUtils.isEmpty(user.getOpenid())) {
				throw new EsRuntimeException(cn.jia.user.common.ErrorConstants.OPENID_NOT_EXIST);
			}
		    File file = new File(webRealPath+"/"+news.getPicurl());
		    
	        DiskFileItem fileItem = (DiskFileItem) new DiskFileItemFactory().createItem("file",
	                MediaType.TEXT_PLAIN_VALUE, true, file.getName());
	        IOUtils.copy(new FileInputStream(file), fileItem.getOutputStream());
			WxMpMaterial material = new WxMpMaterial();
			material.setFile(file);
			WxMpMaterialUploadResult result;
			try{
				result = mpInfoService.findWxMpService(wxAppId).getMaterialService().materialFileUpload(Constants.MEDIA_TYPE_IMAGE, material);
			} catch (WxErrorException e) {
				throw new EsRuntimeException("", e.getError().getErrorMsg());
			}
			if(result == null || StringUtils.isEmpty(result.getMediaId())) {
				throw new EsRuntimeException(ErrorConstants.MEDIA_NOT_EXIST);
			}

		    //上传图文素材
			WxMpMaterialNews wxNews = new WxMpMaterialNews();
		    List<WxMpMaterialNews.WxMpMaterialNewsArticle> articles = new ArrayList<>();
			WxMpMaterialNews.WxMpMaterialNewsArticle article = new WxMpMaterialNews.WxMpMaterialNewsArticle();
		    article.setThumbMediaId(result.getMediaId());
		    article.setTitle(news.getTitle());
		    article.setAuthor(news.getAuthor());
		    article.setDigest(news.getDigest());
		    article.setContent(StreamUtil.readText(new FileInputStream(webRealPath + "/"+news.getBodyurl())));
		    articles.add(article);
			wxNews.setArticles(articles);
			for(WxMpMaterialNews.WxMpMaterialNewsArticle art : wxNews.getArticles()) {
				art.setContent(thsrc(art.getContent(), 0, wxAppId));
			}
			WxMpMaterialUploadResult newsUploadResult;
			try{
				newsUploadResult = mpInfoService.findWxMpService(wxAppId).getMaterialService().materialNewsUpload(wxNews);
			} catch (WxErrorException e) {
				throw new EsRuntimeException("", e.getError().getErrorMsg());
			}
			if(newsUploadResult == null || StringUtils.isEmpty(newsUploadResult.getMediaId())) {
				throw new EsRuntimeException(ErrorConstants.MEDIA_NOT_EXIST);
			}

		    //发送客服消息
			WxMpKefuMessage kfParam = new WxMpKefuMessage();
		    kfParam.setToUser(user.getOpenid());
		    kfParam.setMsgType(Constants.WX_MSG_TYPE_MPNEWS);
		    kfParam.setMpNewsMediaId(newsUploadResult.getMediaId());
		    boolean wxMsg;
		    try{
		    	wxMsg = mpInfoService.findWxMpService(wxAppId).getKefuService().sendKefuMessage(kfParam);
			} catch (WxErrorException e) {
				throw new EsRuntimeException("", e.getError().getErrorMsg());
			}
			if(!wxMsg) {
				throw new EsRuntimeException();
			}
		} else if(Constants.SEND_TYPE_MAIL.equals(type)) {
			if(StringUtils.isEmpty(user.getEmail())) {
				throw new EsRuntimeException(cn.jia.user.common.ErrorConstants.EMAIL_NOT_EXIST);
			}
			String from = dictService.selectByDictTypeAndDictValue(cn.jia.sms.common.Constants.DICT_TYPE_EMAIL_SERVER, cn.jia.sms.common.Constants.EMAIL_SERVER_FROM).getName();
			String name = dictService.selectByDictTypeAndDictValue(cn.jia.sms.common.Constants.DICT_TYPE_EMAIL_SERVER, cn.jia.sms.common.Constants.EMAIL_SERVER_NAME).getName();
			String password = dictService.selectByDictTypeAndDictValue(cn.jia.sms.common.Constants.DICT_TYPE_EMAIL_SERVER, cn.jia.sms.common.Constants.EMAIL_SERVER_PASSWORD).getName();
			String smtp = dictService.selectByDictTypeAndDictValue(cn.jia.sms.common.Constants.DICT_TYPE_EMAIL_SERVER, cn.jia.sms.common.Constants.EMAIL_SERVER_SMTP).getName();

			if(!EmailUtil.doSend(news.getTitle(), StreamUtil.readText(new FileInputStream(webRealPath + "/"+news.getBodyurl())), from, user.getEmail(), name, password, smtp)) {
				throw new EsRuntimeException();
			}
		} else if(Constants.SEND_TYPE_SMS.equals(type)) {
			if(StringUtils.isEmpty(user.getTelephoneNumber())) {
				throw new EsRuntimeException(cn.jia.user.common.ErrorConstants.PHONE_NOT_EXIST);
			}
			String tkey = DateUtil.getDate("yyyyMMddHHmmss");
			String smsUsername = dictService.selectByDictTypeAndDictValue(cn.jia.sms.common.Constants.DICT_TYPE_SMS_CONFIG, cn.jia.sms.common.Constants.SMS_CONFIG_USERNAME).getName();
			String smsPassword = dictService.selectByDictTypeAndDictValue(cn.jia.sms.common.Constants.DICT_TYPE_SMS_CONFIG, cn.jia.sms.common.Constants.SMS_CONFIG_PASSWORD).getName();
			String passwd = MD5Util.str2Base32MD5(MD5Util.str2Base32MD5(smsPassword) + tkey);

			String jiaUrl =  dictService.selectByDictTypeAndDictValue(Constants.DICT_TYPE_JIA_CONFIG, Constants.JIA_CONFIG_SERVER_URL).getName();
			String mobile = user.getTelephoneNumber();
			SmsConfig config = smsService.selectConfig(EsSecurityHandler.clientId());
			String content = "【" + config.getShortName() + "】" + news.getTitle() + " " + jiaUrl + "/file/res/"+ news.getBodyurl();

			HttpHeaders headers = new HttpHeaders();
			headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

			MultiValueMap<String, String> map= new LinkedMultiValueMap<>();
			map.add("username", smsUsername);
			map.add("tkey", tkey);
			map.add("password", passwd);
			map.add("mobile", mobile);
			map.add("content", content);

			String sendSmsBatchURL = "http://hy.mix2.zthysms.com/sendSmsBatch.do";
			ResponseEntity<String> response = restTemplate.postForEntity(sendSmsBatchURL, new HttpEntity<>(map, headers) , String.class);

			if("1".equals(response.getBody().split(",")[0])){
				for(String m : mobile.split(",")) {
					SmsSend smsSend = new SmsSend();
					smsSend.setClientId(EsSecurityHandler.clientId());
					smsSend.setContent(content);
					smsSend.setMobile(m);
					smsSend.setMsgid(response.getBody().split(",")[1]);
					smsSend.setTime(DateUtil.genTime(new Date()));
					smsService.send(smsSend);
				}
			}else {
				log.error(response.getBody());
				throw new EsRuntimeException("", response.getBody());
			}
		}
		return JSONResult.success();
	}

	/**
	 * 替换字符串中的src
	 * @param ynr 图文内容
	 * @param index 从第几个字符开始替换
	 * @return 替换后字符串
	 */
	private String thsrc(String ynr, int index, String wxAppId) throws Exception {
		int srcStart = ynr.indexOf("src=\"", index); // 获取src出现的位置
		int srcEnd = ynr.indexOf("\"", srcStart + 7);
		srcStart = srcStart + 5;
		String src = ynr.substring(srcStart, srcEnd); // 获取图片路径
		//下载文件到本地
		byte[] content = restTemplate.getForObject(HtmlUtils.htmlUnescape(src), byte[].class);
		File tempFile = File.createTempFile("wximg", ".jpg");
		StreamUtil.io(new ByteArrayInputStream(content), new FileOutputStream(tempFile));
		// 执行上传图片方法
		String newPath;
		try{
			newPath = mpInfoService.findWxMpService(wxAppId).getMaterialService().mediaImgUpload(tempFile).getUrl();
		} catch (WxErrorException e) {
			throw new EsRuntimeException("", e.getError().getErrorMsg());
		}
		log.info("thsrc new image path: " + newPath);
		// 替换字符串中该图片路径
		ynr = ynr.replace(src, newPath);
		// 查看字符串下方是否还有img标签
		int sfhyImg = ynr.indexOf("<img", srcEnd);
		if (sfhyImg == -1) {
			return ynr;
		} else {
			return thsrc(ynr, srcEnd, wxAppId);
		}
	}
}
