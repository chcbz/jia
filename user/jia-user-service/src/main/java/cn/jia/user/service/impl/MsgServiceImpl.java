package cn.jia.user.service.impl;

import cn.jia.core.service.BaseServiceImpl;
import cn.jia.user.common.UserConstants;
import cn.jia.user.dao.UserMsgDao;
import cn.jia.user.entity.MsgEntity;
import cn.jia.user.service.MsgService;
import org.springframework.stereotype.Service;

@Service
public class MsgServiceImpl extends BaseServiceImpl<UserMsgDao, MsgEntity> implements MsgService {
	@Override
	public void recycle(Long id) {
		MsgEntity msg = new MsgEntity();
		msg.setId(id);
		msg.setStatus(UserConstants.MSG_STATUS_DELETE);
		baseDao.updateById(msg);
	}

	@Override
	public void restore(Long id) {
		MsgEntity msg = new MsgEntity();
		msg.setId(id);
		msg.setStatus(UserConstants.MSG_STATUS_READED);
		baseDao.updateById(msg);
	}

	@Override
	public void readAll(Long userId) {
		MsgEntity record = new MsgEntity();
		record.setUserId(userId);
		record.setStatus(UserConstants.MSG_STATUS_READED);
		baseDao.updateByUserId(record);
	}

}
