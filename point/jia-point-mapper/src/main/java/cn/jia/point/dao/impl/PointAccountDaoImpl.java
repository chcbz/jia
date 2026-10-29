package cn.jia.point.dao.impl;

import cn.jia.point.dao.PointAccountDao;
import cn.jia.point.mapper.PointAccountMapper;
import jakarta.inject.Named;

@Named
public class PointAccountDaoImpl implements PointAccountDao {
    private final PointAccountMapper mapper;

    public PointAccountDaoImpl(PointAccountMapper mapper) { this.mapper = mapper; }

    @Override public Integer balance(String tenantId, String jiacn) {
        return mapper.balance(tenantId, jiacn);
    }
    @Override public void ensureAccount(String tenantId, String jiacn, long now) {
        mapper.ensureAccount(tenantId, jiacn, now);
    }
    @Override public Integer lockBalance(String tenantId, String jiacn) {
        return mapper.lockBalance(tenantId, jiacn);
    }
    @Override public int change(String tenantId, String jiacn, int delta, long now) {
        return mapper.change(tenantId, jiacn, delta, now);
    }
}
