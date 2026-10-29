package cn.jia.user.dao;

/** Business affiliation is separate from global identity and is NOT a permission grant. */
public interface UserBusinessMembershipDao {
    void deleteByUserId(java.io.Serializable userId);
    void ensure(String tenantId, String clientId, long userId, long now);
}
