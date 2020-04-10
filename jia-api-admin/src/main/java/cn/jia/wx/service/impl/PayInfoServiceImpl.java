package cn.jia.wx.service.impl;

import cn.jia.core.exception.EsRuntimeException;
import cn.jia.core.util.BeanUtil;
import cn.jia.core.util.DateUtil;
import cn.jia.core.util.StringUtils;
import cn.jia.wx.common.ErrorConstants;
import cn.jia.wx.dao.PayInfoMapper;
import cn.jia.wx.entity.PayInfo;
import cn.jia.wx.service.PayInfoService;
import com.github.binarywang.wxpay.config.WxPayConfig;
import com.github.binarywang.wxpay.service.WxPayService;
import com.github.binarywang.wxpay.service.impl.WxPayServiceImpl;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletRequest;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;



@Service
public class PayInfoServiceImpl implements PayInfoService {
	
	@Autowired
	private PayInfoMapper payInfoMapper;
	private Map<String, WxPayService> wxPayServiceMap = new HashMap<>();
	
	/*@PostConstruct
	public void init() {
		wxPayServiceMap = new HashMap<>();
		List<PayInfo> payInfoList = payInfoMapper.selectAll();
		for(PayInfo pay : payInfoList) {
			WxPayService wxPayService = new WxPayServiceImpl();
			WxPayConfig config = new WxPayConfig();
			BeanUtil.copyPropertiesIgnoreNull(pay, config);
			wxPayService.setConfig(config);
			wxPayServiceMap.put(pay.getAppId(), wxPayService);
		}
	}*/
	
	@Override
	public WxPayService findWxPayService(HttpServletRequest request) throws Exception {
		String appid = request.getParameter("appid");
		if(StringUtils.isEmpty(appid)) {
			throw new EsRuntimeException(ErrorConstants.APPID_NOT_NULL);
		}
		return findWxPayService(appid);
	}
	
	@Override
	public WxPayService findWxPayService(String key) throws Exception {
		if(StringUtils.isEmpty(key)) {
			throw new EsRuntimeException(ErrorConstants.APPID_NOT_NULL);
		}
		WxPayService wxPayService = wxPayServiceMap.get(key);
		if(wxPayService == null) {
			PayInfo info = payInfoMapper.selectByKey(key);
			if(info != null) {
				wxPayService = new WxPayServiceImpl();
				WxPayConfig config = new WxPayConfig();
				BeanUtil.copyPropertiesIgnoreNull(info, config);
				wxPayService.setConfig(config);
				wxPayServiceMap.put(info.getAppId(), wxPayService);
			}
		}
		if(wxPayService == null) {
			throw new EsRuntimeException(ErrorConstants.WXMP_NOT_EXIST);
		}
		return wxPayService;
	}

	@Override
	public PayInfo create(PayInfo payInfo) {
		//保存公众号信息
		Long now = DateUtil.genTime(new Date());
		payInfo.setCreateTime(now);
		payInfo.setUpdateTime(now);
		payInfoMapper.insertSelective(payInfo);
		return payInfo;
	}

	@Override
	public PayInfo find(Integer id) {
		return payInfoMapper.selectByPrimaryKey(id);
	}
	
	@Override
	public PayInfo findByKey(String key) {
		return payInfoMapper.selectByKey(key);
	}
	
	@Override
	public Page<PayInfo> list(PayInfo example, int pageNo, int pageSize) {
		PageHelper.startPage(pageNo, pageSize);
		return payInfoMapper.selectByExample(example);
	}

	@Override
	public PayInfo update(PayInfo payInfo) {
		//保存公众号信息
		Long now = DateUtil.genTime(new Date());
		payInfo.setUpdateTime(now);
		payInfoMapper.updateByPrimaryKeySelective(payInfo);
		return payInfo;
	}

	@Override
	public void delete(Integer id) {
		payInfoMapper.deleteByPrimaryKey(id);
	}

	@Override
	public List<PayInfo> selectAll() {
		return payInfoMapper.selectAll();
	}

}
