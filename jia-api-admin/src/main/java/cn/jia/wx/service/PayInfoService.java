package cn.jia.wx.service;

import cn.jia.wx.entity.PayInfo;
import com.github.binarywang.wxpay.service.WxPayService;
import com.github.pagehelper.Page;

import javax.servlet.http.HttpServletRequest;
import java.util.List;

public interface PayInfoService {
	
	WxPayService findWxPayService(HttpServletRequest request) throws Exception;
	
	WxPayService findWxPayService(String key) throws Exception;
	
	PayInfo create(PayInfo payInfo);

	PayInfo find(Integer id) throws Exception;
	
	PayInfo findByKey(String key) throws Exception;
	
	Page<PayInfo> list(PayInfo example, int pageNo, int pageSize);

	PayInfo update(PayInfo payInfo);

	void delete(Integer id);
	
	List<PayInfo> selectAll();
}
