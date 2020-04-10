package cn.jia.isp.service.impl;

import java.util.Map;

import cn.jia.core.entity.JSONResult;
import cn.jia.core.exception.EsRuntimeException;
import cn.jia.isp.common.ErrorConstants;
import cn.jia.isp.service.OAuthService;

public class OAuthServiceImpl implements OAuthService {

	@Override
	public JSONResult<Map<String, Object>> addResource(String resourceId) throws Exception {
		throw new EsRuntimeException(ErrorConstants.REQUEST_TIMEOUT);
	}

}
