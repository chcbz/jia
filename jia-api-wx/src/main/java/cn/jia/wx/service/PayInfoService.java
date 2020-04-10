package cn.jia.wx.service;

import java.util.List;

import javax.servlet.http.HttpServletRequest;

import com.github.binarywang.wxpay.service.WxPayService;
import com.github.pagehelper.Page;

import cn.jia.wx.entity.PayInfo;

public interface PayInfoService {
	
	public WxPayService findWxPayService(HttpServletRequest request) throws Exception;
	
	public WxPayService findWxPayService(String key) throws Exception;
	
	public PayInfo create(PayInfo payInfo);

	public PayInfo find(Integer id) throws Exception;
	
	public PayInfo findByKey(String key) throws Exception;
	
	public Page<PayInfo> list(PayInfo example, int pageNo, int pageSize);

	public PayInfo update(PayInfo payInfo);

	public void delete(Integer id);
	
	public List<PayInfo> selectAll();
}
