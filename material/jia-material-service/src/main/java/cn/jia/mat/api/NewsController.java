package cn.jia.mat.api;

import cn.jia.base.service.DictService;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.entity.JsonRequestPage;
import cn.jia.core.entity.JsonResult;
import cn.jia.core.entity.JsonResultPage;
import cn.jia.core.exception.EsRuntimeException;
import cn.jia.core.util.*;
import cn.jia.mat.common.MatConstants;
import cn.jia.mat.common.MatErrorConstants;
import cn.jia.mat.entity.MatNewsEntity;
import cn.jia.mat.entity.MatNewsReqVO;
import cn.jia.mat.service.MatNewsService;
import cn.jia.sms.common.SmsConstants;
import cn.jia.sms.entity.SmsConfigEntity;
import cn.jia.sms.entity.SmsSendEntity;
import cn.jia.sms.service.SmsService;
import cn.jia.user.common.UserErrorConstants;
import cn.jia.user.entity.UserEntity;
import cn.jia.user.service.UserService;
import cn.jia.wx.service.MpInfoService;
import com.github.pagehelper.PageInfo;
import lombok.extern.slf4j.Slf4j;
import me.chanjar.weixin.common.error.WxErrorException;
import me.chanjar.weixin.mp.bean.kefu.WxMpKefuMessage;
import me.chanjar.weixin.mp.bean.material.WxMpMaterial;
import me.chanjar.weixin.mp.bean.material.WxMpMaterialNews;
import me.chanjar.weixin.mp.bean.material.WxMpMaterialUploadResult;
import me.chanjar.weixin.mp.bean.material.WxMpNewsArticle;
import org.apache.commons.fileupload.disk.DiskFileItem;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.io.IOUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.HtmlUtils;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/news")
@Slf4j
public class NewsController {
	
	@Autowired
	private MatNewsService newsService;
	@Autowired(required = false)
	private UserService userService;
	@Autowired(required = false)
	private MpInfoService mpInfoService;
	@Autowired
	private RestTemplate restTemplate;
	@Value("${mat.web.realpath:/home/isp/hosts/jia/web}")
	private String webRealPath;
	@Autowired(required = false)
	private DictService dictService;
	@Autowired(required = false)
	private SmsService smsService;
	
	/**
	 * 获取图文信息
	 * @param id 图文ID
	 * @return 图文信息
	 */
	@GetMapping(value = "/get")
	public Object findById(@RequestParam(name = "id") Long id) throws Exception{
		MatNewsEntity news = newsService.get(id);
		if(news == null) {
			throw new EsRuntimeException(MatErrorConstants.MEDIA_NOT_EXIST);
		}
//		String material_url = dictService.getValue(Constants.DICT_TYPE_MODULE_URL, Constants.MODULE_URL_MATERIAL);
//		news.setPicurl(material_url +"/"+news.getPicurl());
//		news.setBodyurl(material_url +"/"+news.getBodyurl());
		return JsonResult.success(news);
	}

	/**
	 * 创建图文
	 * @param news 图文信息
	 * @return 图文信息
	 */
	@PostMapping(value = "/create")
	public Object create(@RequestBody MatNewsEntity news) {
		newsService.create(news);
		return JsonResult.success(news);
	}

	/**
	 * 更新图文信息
	 * @param news 图文信息
	 * @return 图文信息
	 */
	@PostMapping(value = "/update")
	public Object update(@RequestBody MatNewsEntity news) {
		newsService.update(news);
		return JsonResult.success();
	}

	/**
	 * 删除图文
	 * @param id 图文ID
	 * @return 处理结果
	 */
	@GetMapping(value = "/delete")
	public Object delete(@RequestParam(name = "id") Long id) {
		newsService.delete(id);
		return JsonResult.success();
	}
	
	/**
	 * 获取所有图文信息
	 * @return 图文列表
	 */
	@PostMapping(value = "/list")
	public Object list(@RequestBody JsonRequestPage<MatNewsReqVO> page) {
		MatNewsReqVO example = Optional.ofNullable(page.getSearch()).orElse(new MatNewsReqVO());
		PageInfo<MatNewsEntity> newsList = newsService.findPage(example, page.getPageSize(), page.getPageNum());
//		String material_url = dictService.getValue(Constants.DICT_TYPE_MODULE_URL, Constants.MODULE_URL_MATERIAL);
//		List<MatNewsEntity> newss = newsList.getResult().stream().map(news -> {
//			news.setPicurl(material_url +"/"+news.getPicurl());
//			news.setBodyurl(material_url +"/"+news.getBodyurl());
//			return news;
//		}).collect(Collectors.toList());
		JsonResultPage<MatNewsEntity> result = new JsonResultPage<>(newsList.getList());
		result.setPageNum(newsList.getPageNum());
		result.setTotal(newsList.getTotal());
		return result;
	}

	/**
	 * 发送图文消息
	 * @param jiacn 用户CN
	 * @param type 发送类型
	 * @param id 图文ID
	 * @param wxAppId 微信APPID
	 * @return 处理结果
	 * @throws Exception 异常信息
	 */
	@GetMapping(value = "/send")
	public Object send(@RequestParam(name = "jiacn") String jiacn, @RequestParam(name = "type") Integer type,
					   @RequestParam(name = "id") Long id,
					   @RequestParam(value="wxappid", required=false) String wxAppId) throws Exception {
		MatNewsEntity news = newsService.get(id);
		if(news == null) {
			throw new EsRuntimeException(MatErrorConstants.MEDIA_NOT_EXIST);
		}
		// 检查用户是否存在
		UserEntity user = userService.findByJiacn(jiacn);
		if (user == null) {
			throw new EsRuntimeException(UserErrorConstants.USER_NOT_EXIST);
		}

		if(MatConstants.SEND_TYPE_WX.equals(type)) {
			if(StringUtil.isEmpty(user.getOpenid())) {
				throw new EsRuntimeException(UserErrorConstants.USER_NOT_EXIST);
			}
			File file = new File(webRealPath+"/"+news.getPicurl());

			DiskFileItem fileItem = (DiskFileItem) new DiskFileItemFactory().createItem("file",
					MediaType.TEXT_PLAIN_VALUE, true, file.getName());
			IOUtils.copy(new FileInputStream(file), fileItem.getOutputStream());
			WxMpMaterial material = new WxMpMaterial();
			material.setFile(file);
			WxMpMaterialUploadResult result;
			try{
				result = mpInfoService.findWxMpService(wxAppId).getMaterialService()
						.materialFileUpload(MatConstants.MEDIA_TYPE_IMAGE, material);
			} catch (WxErrorException e) {
				throw new EsRuntimeException("", e.getError().getErrorMsg());
			}
			if(result == null || StringUtil.isEmpty(result.getMediaId())) {
				throw new EsRuntimeException(MatErrorConstants.MEDIA_NOT_EXIST);
			}

			//上传图文素材
			WxMpMaterialNews wxNews = new WxMpMaterialNews();
			List<WxMpNewsArticle> articles = new ArrayList<>();
			WxMpNewsArticle article = new WxMpNewsArticle();
			article.setThumbMediaId(result.getMediaId());
			article.setTitle(news.getTitle());
			article.setAuthor(news.getAuthor());
			article.setDigest(news.getDigest());
			article.setContent(StreamUtil.readText(new FileInputStream(webRealPath + "/"+news.getBodyurl())));
			articles.add(article);
			wxNews.setArticles(articles);
			for(WxMpNewsArticle art : wxNews.getArticles()) {
				art.setContent(thsrc(art.getContent(), 0, wxAppId));
			}
			WxMpMaterialUploadResult newsUploadResult;
			try{
				newsUploadResult = mpInfoService.findWxMpService(wxAppId).getMaterialService().materialNewsUpload(wxNews);
			} catch (WxErrorException e) {
				throw new EsRuntimeException("", e.getError().getErrorMsg());
			}
			if(newsUploadResult == null || StringUtil.isEmpty(newsUploadResult.getMediaId())) {
				throw new EsRuntimeException(MatErrorConstants.MEDIA_NOT_EXIST);
			}

			//发送客服消息
			WxMpKefuMessage kfParam = new WxMpKefuMessage();
			kfParam.setToUser(user.getOpenid());
			kfParam.setMsgType(MatConstants.WX_MSG_TYPE_MPNEWS);
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
		} else if(MatConstants.SEND_TYPE_MAIL.equals(type)) {
			if(StringUtil.isEmpty(user.getEmail())) {
				throw new EsRuntimeException(UserErrorConstants.USER_NOT_EXIST);
			}
			String from = dictService.getValue(SmsConstants.DICT_TYPE_EMAIL_SERVER, SmsConstants.EMAIL_SERVER_FROM);
			String name = dictService.getValue(SmsConstants.DICT_TYPE_EMAIL_SERVER, SmsConstants.EMAIL_SERVER_NAME);
			String password = dictService.getValue(SmsConstants.DICT_TYPE_EMAIL_SERVER, SmsConstants.EMAIL_SERVER_PASSWORD);
			String smtp = dictService.getValue(SmsConstants.DICT_TYPE_EMAIL_SERVER, SmsConstants.EMAIL_SERVER_SMTP);

			if(!EmailUtil.doSend(news.getTitle(), StreamUtil.readText(new FileInputStream(
					webRealPath + "/"+news.getBodyurl())), from, user.getEmail(), name, password, smtp)) {
				throw new EsRuntimeException();
			}
		} else if(MatConstants.SEND_TYPE_SMS.equals(type)) {
			if(StringUtil.isEmpty(user.getPhone())) {
				throw new EsRuntimeException(UserErrorConstants.USER_NOT_EXIST);
			}
			String tkey = DateUtil.getDate("yyyyMMddHHmmss");
			String smsUsername = dictService.getValue(SmsConstants.DICT_TYPE_SMS_CONFIG, SmsConstants.SMS_CONFIG_USERNAME);
			String smsPassword = dictService.getValue(SmsConstants.DICT_TYPE_SMS_CONFIG, SmsConstants.SMS_CONFIG_PASSWORD);
			String passwd = Md5Util.str2Base32Md5(Md5Util.str2Base32Md5(smsPassword) + tkey);

			String jiaUrl =  dictService.getValue(MatConstants.DICT_TYPE_JIA_CONFIG, MatConstants.JIA_CONFIG_SERVER_URL);
			String mobile = user.getPhone();
			SmsConfigEntity config = smsService.selectConfig(EsContextHolder.getContext().getClientId());
			String content = "【" + config.getShortName() + "】" + news.getTitle() + " " + jiaUrl + "/file/res/"+ news.getBodyurl();

			HttpHeaders headers = new HttpHeaders();
			headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

			MultiValueMap<String, String> map= new LinkedMultiValueMap<>();
			map.add("username", smsUsername);
			map.add("tkey", tkey);
			map.add("password", passwd);
			map.add("mobile", mobile);
			map.add("content", content);

			String sendSmsBatchUrl = "http://api.zthysms.com/sendSmsBatch.do";
			ResponseEntity<String> response = restTemplate.postForEntity(sendSmsBatchUrl, new HttpEntity<>(map, headers) , String.class);

			if("1".equals(response.getBody().split(",")[0])){
				for(String m : mobile.split(",")) {
					SmsSendEntity smsSend = new SmsSendEntity();
					smsSend.setClientId(EsContextHolder.getContext().getClientId());
					smsSend.setContent(content);
					smsSend.setMobile(m);
					smsSend.setMsgid(response.getBody().split(",")[1]);
					smsService.send(smsSend);
				}
			}else {
				log.error(response.getBody());
				throw new EsRuntimeException("", response.getBody());
			}
		}
		return JsonResult.success();
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
