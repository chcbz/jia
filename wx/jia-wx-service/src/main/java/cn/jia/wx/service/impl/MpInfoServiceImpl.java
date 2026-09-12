package cn.jia.wx.service.impl;

import cn.jia.core.exception.EsRuntimeException;
import cn.jia.core.service.BaseServiceImpl;
import cn.jia.core.util.StringUtil;
import cn.jia.wx.common.WxErrorConstants;
import cn.jia.wx.config.WxExternalHttpClientBuilder;
import cn.jia.wx.dao.MpInfoDao;
import cn.jia.wx.entity.MpInfoEntity;
import cn.jia.wx.service.MpInfoService;
import jakarta.servlet.http.HttpServletRequest;
import me.chanjar.weixin.mp.api.WxMpService;
import me.chanjar.weixin.mp.api.impl.WxMpServiceImpl;
import me.chanjar.weixin.mp.config.impl.WxMpMapConfigImpl;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class MpInfoServiceImpl extends BaseServiceImpl<MpInfoDao, MpInfoEntity> implements MpInfoService {
	private final Map<String, WxMpService> wxMpServiceMap = new HashMap<>(16);

	@Value("${wx.external-http.connection-request-timeout-ms:250}")
	private int connectionRequestTimeoutMillis = 250;

	@Value("${wx.external-http.connect-timeout-ms:500}")
	private int connectTimeoutMillis = 500;

	@Value("${wx.external-http.read-timeout-ms:1750}")
	private int readTimeoutMillis = 1750;

	@Value("${wx.external-http.total-timeout-ms:2500}")
	private int totalTimeoutMillis = 2500;
	
//	@PostConstruct
	public void init() {
		List<MpInfoEntity> mpInfoList = baseDao.selectAll();
		for(MpInfoEntity mp : mpInfoList) {
			WxMpService wxMpService = createWxMpService(mp);
			wxMpServiceMap.put(mp.getAppid(), wxMpService);
			wxMpServiceMap.put(mp.getOriginal(), wxMpService);
		}
	}
	
	@Override
	public WxMpService findWxMpService(HttpServletRequest request) {
		String appid = request.getParameter("appid");
		if(StringUtil.isEmpty(appid)) {
			throw new EsRuntimeException(WxErrorConstants.APPID_NOT_NULL);
		}
		return findWxMpService(appid);
	}
	
	@Override
	public WxMpService findWxMpService(String key) {
		if(StringUtil.isEmpty(key)) {
			throw new EsRuntimeException(WxErrorConstants.APPID_NOT_NULL);
		}
		WxMpService wxMpService = wxMpServiceMap.get(key);
		if(wxMpService == null) {
			MpInfoEntity info = findByKey(key);
			if(info != null) {
				wxMpService = createWxMpService(info);
				wxMpServiceMap.put(info.getAppid(), wxMpService);
				wxMpServiceMap.put(info.getOriginal(), wxMpService);
			}
		}
		if(wxMpService == null) {
			throw new EsRuntimeException(WxErrorConstants.WXMP_NOT_EXIST);
		}
		return wxMpService;
	}

	private WxMpService createWxMpService(MpInfoEntity info) {
		WxMpService wxMpService = new WxMpServiceImpl();
		WxMpMapConfigImpl config = new WxMpMapConfigImpl();
		config.setAppId(info.getAppid());
		config.setSecret(info.getSecret());
		config.setToken(info.getToken());
		config.setAesKey(info.getEncodingaeskey());
		config.setApacheHttpClientBuilder(new WxExternalHttpClientBuilder(connectionRequestTimeoutMillis,
				connectTimeoutMillis, readTimeoutMillis, totalTimeoutMillis));
		wxMpService.setWxMpConfigStorage(config);
		return wxMpService;
	}

	@Override
	public MpInfoEntity findByKey(String key) {
		return baseDao.findByKey(key);
	}
}
