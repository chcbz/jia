package cn.jia.agent.dao.impl;

import cn.jia.agent.dao.HallReadDao;
import cn.jia.agent.entity.HallItemRow;
import cn.jia.agent.mapper.HallReadMapper;
import jakarta.inject.Named;
import java.util.List;
import java.util.Objects;

@Named
public class HallReadDaoImpl implements HallReadDao {
    private final HallReadMapper mapper;
    public HallReadDaoImpl(HallReadMapper mapper) { this.mapper = Objects.requireNonNull(mapper); }
    @Override public List<HallItemRow> page(String tenantId, String clientId, String ownerJiacn,
            String kind, String view, String q, Long beforeUpdatedAt,
            String beforeSourceType, String beforeId, int limit) {
        return mapper.page(tenantId, clientId, ownerJiacn, kind, view, q,
                beforeUpdatedAt, beforeSourceType, beforeId, limit);
    }
}
