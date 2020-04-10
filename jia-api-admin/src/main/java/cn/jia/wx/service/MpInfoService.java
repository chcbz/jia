package cn.jia.wx.service;

import cn.jia.wx.entity.MpInfo;
import com.github.pagehelper.Page;
import me.chanjar.weixin.mp.api.WxMpService;

import javax.servlet.http.HttpServletRequest;
import java.util.List;

public interface MpInfoService {
	
	WxMpService findWxMpService(HttpServletRequest request) throws Exception;
	
	WxMpService findWxMpService(String key) throws Exception;
	
	MpInfo create(MpInfo mpInfo);

	MpInfo find(Integer id) throws Exception;
	
	MpInfo findByKey(String key) throws Exception;
	
	Page<MpInfo> list(MpInfo example, int pageNo, int pageSize);

	MpInfo update(MpInfo mpInfo);

	void delete(Integer id);
	
	List<MpInfo> selectAll();
}
