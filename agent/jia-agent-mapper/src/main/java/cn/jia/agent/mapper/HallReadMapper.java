package cn.jia.agent.mapper;

import cn.jia.agent.entity.HallItemRow;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.SelectProvider;
import java.util.List;

public interface HallReadMapper {
    @SelectProvider(type = HallReadSql.class, method = "page")
    List<HallItemRow> page(@Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn, @Param("kind") String kind,
            @Param("view") String view, @Param("q") String q,
            @Param("beforeUpdatedAt") Long beforeUpdatedAt,
            @Param("beforeSourceType") String beforeSourceType, @Param("beforeId") String beforeId,
            @Param("limit") int limit, @Param("formalEnabled") boolean formalEnabled);
}
