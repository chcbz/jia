package cn.jia.wx.dao;

import cn.jia.wx.entity.PayInfo;
import com.github.pagehelper.Page;

import java.util.List;

public interface PayInfoMapper {
    int deleteByPrimaryKey(Integer acid);

    int insert(PayInfo record);

    int insertSelective(PayInfo record);

    PayInfo selectByPrimaryKey(Integer acid);

    int updateByPrimaryKeySelective(PayInfo record);

    int updateByPrimaryKey(PayInfo record);
    
    List<PayInfo> selectAll();
    
    Page<PayInfo> selectByExample(PayInfo record);
    
    PayInfo selectByKey(String key);
}