package cn.jia.wx.service.impl;

import java.util.List;
import java.util.Map;

import cn.jia.core.entity.JSONResult;
import cn.jia.core.exception.EsRuntimeException;
import cn.jia.wx.common.ErrorConstants;
import cn.jia.wx.service.UserService;

public class UserServiceImpl implements UserService {

	@Override
	public JSONResult<Map<String, Object>> find(String type, String key) throws Exception {
		throw new EsRuntimeException(ErrorConstants.REQUEST_TIMEOUT);
	}

	@Override
	public JSONResult<Map<String, Object>> create(Map<String, Object> user) throws Exception {
		throw new EsRuntimeException(ErrorConstants.REQUEST_TIMEOUT);
	}

	@Override
	public JSONResult<Map<String, Object>> sync(List<Map<String, Object>> userList) throws Exception {
		throw new EsRuntimeException(ErrorConstants.REQUEST_TIMEOUT);
	}

}
