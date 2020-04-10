package cn.jia.wx.service.impl;

import cn.jia.core.util.DateUtil;
import cn.jia.wx.dao.PayOrderMapper;
import cn.jia.wx.entity.PayOrder;
import cn.jia.wx.service.PayOrderService;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

@Service
public class PayOrderServiceImpl implements PayOrderService {
	
	@Autowired
	private PayOrderMapper payOrderMapper;

	@Override
	public PayOrder create(PayOrder payOrder) {
		Long now = DateUtil.genTime(new Date());
		payOrder.setCreateTime(now);
		payOrder.setUpdateTime(now);
		payOrderMapper.insertSelective(payOrder);
		return payOrder;
	}

	@Override
	public PayOrder find(Integer id) {
		return payOrderMapper.selectByPrimaryKey(id);
	}
	
	@Override
	public Page<PayOrder> list(PayOrder example, int pageNo, int pageSize) {
		PageHelper.startPage(pageNo, pageSize);
		return payOrderMapper.selectByExample(example);
	}

	@Override
	public PayOrder update(PayOrder payOrder) {
		Long now = DateUtil.genTime(new Date());
		payOrder.setUpdateTime(now);
		payOrderMapper.updateByPrimaryKeySelective(payOrder);
		return payOrder;
	}

	@Override
	public void delete(Integer id) {
		payOrderMapper.deleteByPrimaryKey(id);
	}

	@Override
	public List<PayOrder> selectAll() {
		return payOrderMapper.selectAll();
	}

}
