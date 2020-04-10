package cn.jia.wx.service.impl;

import java.util.Map;

import cn.jia.core.entity.JSONResult;
import cn.jia.core.exception.EsRuntimeException;
import cn.jia.wx.common.ErrorConstants;
import cn.jia.wx.service.PointService;

public class PointServiceImpl implements PointService {

	@Override
	public JSONResult<Map<String, Object>> init(Map<String, Object> record) throws Exception {
		throw new EsRuntimeException(ErrorConstants.REQUEST_TIMEOUT);
	}

	@Override
	public JSONResult<Map<String, Object>> sign(Map<String, Object> sign) throws Exception {
		throw new EsRuntimeException(ErrorConstants.REQUEST_TIMEOUT);
	}

	@Override
	public JSONResult<Map<String, Object>> referral(Map<String, Object> referral) throws Exception {
		throw new EsRuntimeException(ErrorConstants.REQUEST_TIMEOUT);
	}

	@Override
	public JSONResult<Map<String, Object>> luck(Map<String, Object> record) throws Exception {
		throw new EsRuntimeException(ErrorConstants.REQUEST_TIMEOUT);
	}

}
