package cn.jia.sms.service.impl;

import java.util.Map;

import cn.jia.core.entity.JSONResult;
import cn.jia.core.exception.EsRuntimeException;
import cn.jia.sms.common.ErrorConstants;
import cn.jia.sms.service.WxMpService;

public class WxMpServiceImpl implements WxMpService {

	@Override
	public JSONResult<Map<String, Object>> customMessageSend(String appid, Map<String, Object> message) throws Exception {
		throw new EsRuntimeException(ErrorConstants.REQUEST_TIMEOUT);
	}

}
