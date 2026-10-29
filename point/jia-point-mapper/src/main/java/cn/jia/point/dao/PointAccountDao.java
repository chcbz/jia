package cn.jia.point.dao;

public interface PointAccountDao {
    java.util.List<cn.jia.point.entity.PointAccountBalance> balances(String tenantId, java.util.Collection<String> jiacns);
    Integer balance(String tenantId, String jiacn);
    void ensureAccount(String tenantId, String jiacn, long now);
    Integer lockBalance(String tenantId, String jiacn);
    int change(String tenantId, String jiacn, int delta, long now);
}
