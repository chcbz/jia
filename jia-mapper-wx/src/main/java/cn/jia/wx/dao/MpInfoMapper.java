package cn.jia.wx.dao;

import cn.jia.wx.entity.MpInfo;
import com.github.pagehelper.Page;

import java.util.List;

public interface MpInfoMapper {
    int deleteByPrimaryKey(Integer acid);

    int insert(MpInfo record);

    int insertSelective(MpInfo record);

    MpInfo selectByPrimaryKey(Integer acid);

    int updateByPrimaryKeySelective(MpInfo record);

    int updateByPrimaryKey(MpInfo record);
    
    List<MpInfo> selectAll();
    
    Page<MpInfo> selectByExample(MpInfo record);
    
    MpInfo selectByKey(String key);
}