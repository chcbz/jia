package cn.jia.wx.dao;

import cn.jia.wx.entity.PayOrder;
import com.github.pagehelper.Page;

import java.util.List;

public interface PayOrderMapper {
    int deleteByPrimaryKey(Integer id);

    int insert(PayOrder record);

    int insertSelective(PayOrder record);

    PayOrder selectByPrimaryKey(Integer id);

    int updateByPrimaryKeySelective(PayOrder record);

    int updateByPrimaryKey(PayOrder record);

    List<PayOrder> selectAll();

    Page<PayOrder> selectByExample(PayOrder record);
}