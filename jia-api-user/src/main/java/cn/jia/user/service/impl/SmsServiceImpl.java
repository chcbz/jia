package cn.jia.user.service.impl;

import cn.jia.core.entity.JSONResult;
import cn.jia.core.exception.EsRuntimeException;
import cn.jia.user.common.ErrorConstants;
import cn.jia.user.service.SmsService;

public class SmsServiceImpl implements SmsService {

	@Override
	public JSONResult<String> useSmsCode(String phone, Integer smsType) throws Exception {
		throw new EsRuntimeException(ErrorConstants.REQUEST_TIMEOUT);
	}

	@Override
	public JSONResult<String> gen(String phone, Integer smsType) throws Exception {
		throw new EsRuntimeException(ErrorConstants.REQUEST_TIMEOUT);
	}

}
