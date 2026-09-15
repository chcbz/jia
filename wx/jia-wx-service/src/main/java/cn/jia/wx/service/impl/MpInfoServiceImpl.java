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

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MpInfoServiceImpl extends BaseServiceImpl<MpInfoDao, MpInfoEntity> implements MpInfoService {
	private final Map<String, WxMpService> wxMpServiceMap = new ConcurrentHashMap<>(16);
	private final Map<String, MpInfoEntity> mpInfoMap = new ConcurrentHashMap<>(16);

	@Value("${wx.external-http.connection-request-timeout-ms:0}")
	private int connectionRequestTimeoutMillis;

	@Value("${wx.external-http.connect-timeout-ms:0}")
	private int connectTimeoutMillis;

	@Value("${wx.external-http.read-timeout-ms:0}")
	private int readTimeoutMillis;

	
//	@PostConstruct
	public void init() {
		List<MpInfoEntity> mpInfoList = baseDao.selectAll();
		for(MpInfoEntity mp : mpInfoList) {
			cache(mp);
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
			loadAndCache(key);
			wxMpService = wxMpServiceMap.get(key);
		}
		if(wxMpService == null) {
			throw new EsRuntimeException(WxErrorConstants.WXMP_NOT_EXIST);
		}
		return wxMpService;
	}

	private synchronized void loadAndCache(String key) {
		if (wxMpServiceMap.containsKey(key)) {
			return;
		}
		MpInfoEntity info = findByKey(key);
		if (info != null) {
			cache(info);
		}
	}

	private void cache(MpInfoEntity info) {
		if (info == null || StringUtil.isEmpty(info.getAppid())) {
			return;
		}
		WxMpService wxMpService = wxMpServiceMap.computeIfAbsent(info.getAppid(), ignored -> createWxMpService(info));
		mpInfoMap.put(info.getAppid(), info);
		if (!StringUtil.isEmpty(info.getOriginal())) {
			wxMpServiceMap.put(info.getOriginal(), wxMpService);
			mpInfoMap.put(info.getOriginal(), info);
		}
	}

	private WxMpService createWxMpService(MpInfoEntity info) {
		WxMpService wxMpService = new WxMpServiceImpl();
		WxMpMapConfigImpl config = new WxMpMapConfigImpl();
		config.setAppId(info.getAppid());
		config.setSecret(info.getSecret());
		config.setToken(info.getToken());
		config.setAesKey(info.getEncodingaeskey());
		if (connectionRequestTimeoutMillis > 0 || connectTimeoutMillis > 0 || readTimeoutMillis > 0) {
			config.setApacheHttpClientBuilder(new WxExternalHttpClientBuilder(connectionRequestTimeoutMillis,
					connectTimeoutMillis, readTimeoutMillis));
		}
		wxMpService.setWxMpConfigStorage(config);
		return wxMpService;
	}

	@Override
	public MpInfoEntity findByKey(String key) {
		return baseDao.findByKey(key);
	}

	@Override
	public MpInfoEntity findCachedByKey(String key) {
		return mpInfoMap.get(key);
	}
}
