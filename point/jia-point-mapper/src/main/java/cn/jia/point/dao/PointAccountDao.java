package cn.jia.point.dao;

public interface PointAccountDao {
    Integer balance(String tenantId, String jiacn);
    void ensureAccount(String tenantId, String jiacn, long now);
    Integer lockBalance(String tenantId, String jiacn);
    int change(String tenantId, String jiacn, int delta, long now);
}
