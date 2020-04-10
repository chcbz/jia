package cn.jia.user.service;

import com.github.pagehelper.Page;

import cn.jia.user.entity.Msg;

public interface MsgService {
	
	Msg create(Msg msg);

	Msg find(Integer id) throws Exception;

	Msg update(Msg msg);

	void recycle(Integer id);

	void restore(Integer id);

	void delete(Integer id);
	
	Page<Msg> list(Msg example, int pageNo, int pageSize);
	
	void readAll(Integer userId);
}
