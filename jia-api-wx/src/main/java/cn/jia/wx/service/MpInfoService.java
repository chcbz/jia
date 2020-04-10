package cn.jia.wx.service;

import java.util.List;

import javax.servlet.http.HttpServletRequest;

import com.github.pagehelper.Page;

import cn.jia.wx.entity.MpInfo;
import me.chanjar.weixin.mp.api.WxMpService;

public interface MpInfoService {
	
	public WxMpService findWxMpService(HttpServletRequest request) throws Exception;
	
	public WxMpService findWxMpService(String key) throws Exception;
	
	public MpInfo create(MpInfo mpInfo);

	public MpInfo find(Integer id) throws Exception;
	
	public MpInfo findByKey(String key) throws Exception;
	
	public Page<MpInfo> list(MpInfo example, int pageNo, int pageSize);

	public MpInfo update(MpInfo mpInfo);

	public void delete(Integer id);
	
	public List<MpInfo> selectAll();
}
