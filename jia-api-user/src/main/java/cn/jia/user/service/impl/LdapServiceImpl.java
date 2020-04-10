package cn.jia.user.service.impl;

import java.util.List;
import java.util.Map;

import cn.jia.core.entity.JSONResult;
import cn.jia.core.exception.EsRuntimeException;
import cn.jia.user.common.ErrorConstants;
import cn.jia.user.service.LdapService;

public class LdapServiceImpl implements LdapService {

	@Override
	public JSONResult<List<Map<String, Object>>> userFind(Map<String, Object> user) throws Exception {
		throw new EsRuntimeException(ErrorConstants.REQUEST_TIMEOUT);
	}

	@Override
	public JSONResult<String> userCreate(Map<String, Object> user) throws Exception {
		throw new EsRuntimeException(ErrorConstants.REQUEST_TIMEOUT);
	}

}
