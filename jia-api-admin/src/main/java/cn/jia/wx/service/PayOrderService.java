package cn.jia.wx.service;

import cn.jia.wx.entity.PayOrder;
import com.github.pagehelper.Page;

import java.util.List;

public interface PayOrderService {
	
	PayOrder create(PayOrder payOrder);

	PayOrder find(Integer id);

	Page<PayOrder> list(PayOrder example, int pageNo, int pageSize);

	PayOrder update(PayOrder payOrder);

	void delete(Integer id);
	
	List<PayOrder> selectAll();
}
