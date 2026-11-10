package cn.jia.kefu.mapper;

import cn.jia.kefu.entity.KefuMsgSubscribeEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author chc
 * @since 2021-02-18
 */
public interface KefuMsgSubscribeMapper extends BaseMapper<KefuMsgSubscribeEntity> {

    java.util.List<KefuMsgSubscribeEntity> selectScheduledRecipients(
            @org.apache.ibatis.annotations.Param("tenantId") String tenantId,
            @org.apache.ibatis.annotations.Param("clientId") String clientId,
            @org.apache.ibatis.annotations.Param("typeCode") String typeCode,
            @org.apache.ibatis.annotations.Param("afterId") long afterId,
            @org.apache.ibatis.annotations.Param("limit") int limit);

    KefuMsgSubscribeEntity selectScheduledRecipient(
            @org.apache.ibatis.annotations.Param("tenantId") String tenantId,
            @org.apache.ibatis.annotations.Param("clientId") String clientId,
            @org.apache.ibatis.annotations.Param("id") long id,
            @org.apache.ibatis.annotations.Param("typeCode") String typeCode,
            @org.apache.ibatis.annotations.Param("jiacn") String jiacn);
}
