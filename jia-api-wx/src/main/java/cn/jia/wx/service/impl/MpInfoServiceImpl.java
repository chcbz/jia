package cn.jia.wx.service.impl;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.PostConstruct;
import javax.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.alibaba.druid.util.StringUtils;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;

import cn.jia.core.common.EsSecurityHandler;
import cn.jia.core.exception.EsRuntimeException;
import cn.jia.core.util.DateUtil;
import cn.jia.wx.common.ErrorConstants;
import cn.jia.wx.dao.MpInfoMapper;
import cn.jia.wx.entity.MpInfo;
import cn.jia.wx.service.MpInfoService;
import me.chanjar.weixin.mp.api.WxMpInMemoryConfigStorage;
import me.chanjar.weixin.mp.api.WxMpService;
import me.chanjar.weixin.mp.api.impl.WxMpServiceImpl;

@Service
public class MpInfoServiceImpl implements MpInfoService {
	
	@Autowired
	private MpInfoMapper mpInfoMapper;
	private Map<String, WxMpService> wxMpServiceMap;
	
	@PostConstruct
	public void init() {
		wxMpServiceMap = new HashMap<>();
		List<MpInfo> mpInfoList = mpInfoMapper.selectAll();
		for(MpInfo mp : mpInfoList) {
			WxMpService wxMpService = new WxMpServiceImpl();
			WxMpInMemoryConfigStorage config = new WxMpInMemoryConfigStorage();
			config.setAppId(mp.getAppid());
			config.setSecret(mp.getSecret());
			config.setToken(mp.getToken());
			config.setAesKey(mp.getEncodingaeskey());
			wxMpService.setWxMpConfigStorage(config);
			wxMpServiceMap.put(mp.getAppid(), wxMpService);
			wxMpServiceMap.put(mp.getOriginal(), wxMpService);
		}
	}
	
	@Override
	public WxMpService findWxMpService(HttpServletRequest request) throws Exception {
		String appid = request.getParameter("appid");
		if(StringUtils.isEmpty(appid)) {
			throw new EsRuntimeException(ErrorConstants.APPID_NOT_NULL);
		}
		return findWxMpService(appid);
	}
	
	@Override
	public WxMpService findWxMpService(String key) throws Exception {
		if(StringUtils.isEmpty(key)) {
			throw new EsRuntimeException(ErrorConstants.APPID_NOT_NULL);
		}
		WxMpService wxMpService = wxMpServiceMap.get(key);
		if(wxMpService == null) {
			MpInfo info = mpInfoMapper.selectByKey(key);
			if(info != null) {
				wxMpService = new WxMpServiceImpl();
				WxMpInMemoryConfigStorage config = new WxMpInMemoryConfigStorage();
				config.setAppId(info.getAppid());
				config.setSecret(info.getSecret());
				config.setToken(info.getToken());
				config.setAesKey(info.getEncodingaeskey());
				wxMpService.setWxMpConfigStorage(config);
				wxMpServiceMap.put(info.getAppid(), wxMpService);
				wxMpServiceMap.put(info.getOriginal(), wxMpService);
			}
		}
		if(wxMpService == null) {
			throw new EsRuntimeException(ErrorConstants.WXMP_NOT_EXIST);
		}
		return wxMpService;
	}

	@Override
	public MpInfo create(MpInfo mpInfo) {
		//保存公众号信息
		Long now = DateUtil.genTime(new Date());
		mpInfo.setClientId(EsSecurityHandler.clientId());
		mpInfo.setCreateTime(now);
		mpInfo.setUpdateTime(now);
		mpInfoMapper.insertSelective(mpInfo);
		return mpInfo;
	}

	@Override
	public MpInfo find(Integer id) throws Exception {
		return mpInfoMapper.selectByPrimaryKey(id);
	}
	
	@Override
	public MpInfo findByKey(String key) throws Exception {
		return mpInfoMapper.selectByKey(key);
	}
	
	@Override
	public Page<MpInfo> list(MpInfo example, int pageNo, int pageSize) {
		PageHelper.startPage(pageNo, pageSize);
		return mpInfoMapper.selectByExample(example);
	}

	@Override
	public MpInfo update(MpInfo mpInfo) {
		//保存公众号信息
		Long now = DateUtil.genTime(new Date());
		mpInfo.setUpdateTime(now);
		mpInfoMapper.updateByPrimaryKeySelective(mpInfo);
		return mpInfo;
	}

	@Override
	public void delete(Integer id) {
		mpInfoMapper.deleteByPrimaryKey(id);
	}

	@Override
	public List<MpInfo> selectAll() {
		return mpInfoMapper.selectAll();
	}

}
