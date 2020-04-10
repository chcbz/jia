package cn.jia.wx.service.impl;

import java.util.List;
import java.util.Map;

import cn.jia.core.entity.JSONResult;
import cn.jia.core.exception.EsRuntimeException;
import cn.jia.wx.common.ErrorConstants;
import cn.jia.wx.service.GiftService;

public class GiftServiceImpl implements GiftService {

	@Override
	public JSONResult<List<Map<String, Object>>> list(Map<String, Object> page) throws Exception {
		throw new EsRuntimeException(ErrorConstants.REQUEST_TIMEOUT);
	}

	@Override
	public JSONResult<Map<String, Object>> findById(Integer id) throws Exception {
		throw new EsRuntimeException(ErrorConstants.REQUEST_TIMEOUT);
	}

	@Override
	public JSONResult<Map<String, Object>> usage(Map<String, Object> giftUsage) throws Exception {
		throw new EsRuntimeException(ErrorConstants.REQUEST_TIMEOUT);
	}

}
