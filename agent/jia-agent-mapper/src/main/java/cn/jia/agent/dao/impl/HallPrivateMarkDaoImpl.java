package cn.jia.agent.dao.impl;

import cn.jia.agent.dao.HallPrivateMarkDao;
import cn.jia.agent.entity.HallPrivateMarkEntity;
import cn.jia.agent.mapper.HallPrivateMarkMapper;
import jakarta.inject.Named;
import java.util.Objects;

@Named
public class HallPrivateMarkDaoImpl implements HallPrivateMarkDao {
    private final HallPrivateMarkMapper mapper;
    public HallPrivateMarkDaoImpl(HallPrivateMarkMapper mapper) { this.mapper = Objects.requireNonNull(mapper); }
    @Override public HallPrivateMarkEntity current(String tenant, String client, String owner, String type, String id) {
        return mapper.current(tenant, client, owner, type, id);
    }
    @Override public HallPrivateMarkEntity byKey(String tenant, String client, String owner, String key) {
        return mapper.byKey(tenant, client, owner, key);
    }
    @Override public int insert(HallPrivateMarkEntity mark) { return mapper.insert(mark); }
}
