package cn.jia.core.service;

import cn.jia.core.entity.Log;

public interface LogService {

	Log selectById(Integer id);

	void insert(Log log);

	void update(Log log);

	void delete(Integer id);
}
