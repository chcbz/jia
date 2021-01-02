package cn.jia.wx.dao;

import cn.jia.wx.entity.MpInfo;
import cn.jia.wx.entity.MpInfoExample;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.github.pagehelper.Page;

import java.util.List;

public interface MpInfoMapper extends BaseMapper<MpInfo> {

    List<MpInfo> selectAll();
    
    Page<MpInfo> selectByExample(MpInfoExample record);
    
    MpInfo selectByKey(String key);
}