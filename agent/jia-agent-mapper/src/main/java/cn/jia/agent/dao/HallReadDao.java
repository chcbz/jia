package cn.jia.agent.dao;

import cn.jia.agent.entity.HallItemRow;
import java.util.List;

public interface HallReadDao {
    List<HallItemRow> page(String tenantId, String clientId, String ownerJiacn,
                          String kind, String view, String q, Long beforeUpdatedAt,
                          String beforeSourceType, String beforeId, int limit);
}
