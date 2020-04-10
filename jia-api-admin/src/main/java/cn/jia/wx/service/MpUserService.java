package cn.jia.wx.service;

import cn.jia.wx.entity.MpUser;
import com.github.pagehelper.Page;

import java.util.List;

public interface MpUserService {

	MpUser create(MpUser mpUser);

	MpUser find(Integer id) throws Exception;

	MpUser findByOpenId(String openId);
	
	Page<MpUser> list(MpUser example, int pageNo, int pageSize);

	MpUser update(MpUser mpUser);

	void delete(Integer id);

	void sync(List<MpUser> userList) throws Exception;
}
