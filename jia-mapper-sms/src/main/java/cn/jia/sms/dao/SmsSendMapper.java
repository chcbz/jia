package cn.jia.sms.dao;

import cn.jia.sms.entity.SmsSend;
import cn.jia.sms.entity.SmsSendExample;
import com.github.pagehelper.Page;

import java.util.List;
import java.util.Map;

public interface SmsSendMapper {
    int deleteByPrimaryKey(String msgid);

    int insert(SmsSend record);

    int insertSelective(SmsSend record);

    SmsSend selectByPrimaryKey(String msgid);

    int updateByPrimaryKeySelective(SmsSend record);

    int updateByPrimaryKey(SmsSend record);
    
    Page<SmsSend> selectByExamplePage(SmsSendExample example);

    /**
     * 统计电话的发送数量
     * @param example 过滤条件
     * @return [{"mobile": "1345*", "num": 3}]
     */
    List<Map<String, Object>> groupByMobile(SmsSendExample example);
}