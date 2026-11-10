package cn.jia.kefu.mapper;

import cn.jia.kefu.entity.KefuMsgTypeEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author chc
 * @since 2021-06-27
 */
public interface KefuMsgTypeMapper extends BaseMapper<KefuMsgTypeEntity> {

    KefuMsgTypeEntity selectScheduledMsgType(
            @org.apache.ibatis.annotations.Param("tenantId") String tenantId,
            @org.apache.ibatis.annotations.Param("clientId") String clientId,
            @org.apache.ibatis.annotations.Param("typeCode") String typeCode);
}
