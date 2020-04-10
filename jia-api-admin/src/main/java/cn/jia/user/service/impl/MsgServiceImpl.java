package cn.jia.user.service.impl;

import cn.jia.core.exception.EsRuntimeException;
import cn.jia.core.util.DateUtil;
import cn.jia.user.common.Constants;
import cn.jia.user.common.ErrorConstants;
import cn.jia.user.dao.MsgMapper;
import cn.jia.user.entity.Msg;
import cn.jia.user.service.MsgService;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;

@Service
public class MsgServiceImpl implements MsgService {
	
	@Autowired
	private MsgMapper msgMapper;
	
	@Override
	public Msg create(Msg msg) {
		Long now = DateUtil.genTime(new Date());
		msg.setCreateTime(now);
		msg.setUpdateTime(now);
		msgMapper.insertSelective(msg);
		return msg;
	}

	@Override
	public Msg find(Integer id) throws Exception {
		Msg msg = msgMapper.selectByPrimaryKey(id);
		if(msg == null) {
			throw new EsRuntimeException(ErrorConstants.DATA_NOT_FOUND);
		}
		return msg;
	}

	@Override
	public Msg update(Msg msg) {
		Long now = DateUtil.genTime(new Date());
		msg.setUpdateTime(now);
		msgMapper.updateByPrimaryKeySelective(msg);
		return msg;
	}

	@Override
	public void recycle(Integer id) {
		Msg msg = new Msg();
		msg.setId(id);
		msg.setStatus(Constants.MSG_STATUS_DELETE);
		Long now = DateUtil.genTime(new Date());
		msg.setUpdateTime(now);
		msgMapper.updateByPrimaryKeySelective(msg);
	}

	@Override
	public void restore(Integer id) {
		Msg msg = new Msg();
		msg.setId(id);
		msg.setStatus(Constants.MSG_STATUS_READED);
		Long now = DateUtil.genTime(new Date());
		msg.setUpdateTime(now);
		msgMapper.updateByPrimaryKeySelective(msg);
	}

	@Override
	public void delete(Integer id) {
		msgMapper.deleteByPrimaryKey(id);
	}

	@Override
	public Page<Msg> list(Msg example, int pageNo, int pageSize) {
		PageHelper.startPage(pageNo, pageSize);
		return msgMapper.selectByExamplePage(example);
	}

	@Override
	public void readAll(Integer userId) {
		Msg record = new Msg();
		record.setUserId(userId);
		record.setStatus(Constants.MSG_STATUS_READED);
		Long now = DateUtil.genTime(new Date());
		record.setUpdateTime(now);
		msgMapper.updateByUserId(record);
	}

}
