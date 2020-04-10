package cn.jia.sms.dao;

import cn.jia.sms.entity.SmsBuy;
import cn.jia.sms.entity.SmsBuyExample;
import com.github.pagehelper.Page;

public interface SmsBuyMapper {
    int deleteByPrimaryKey(Integer id);

    int insert(SmsBuy record);

    int insertSelective(SmsBuy record);

    SmsBuy selectByPrimaryKey(Integer id);

    int updateByPrimaryKeySelective(SmsBuy record);

    int updateByPrimaryKey(SmsBuy record);

    SmsBuy selectLastest(String clientId);

    Page<SmsBuy> selectByExamplePage(SmsBuyExample record);
}