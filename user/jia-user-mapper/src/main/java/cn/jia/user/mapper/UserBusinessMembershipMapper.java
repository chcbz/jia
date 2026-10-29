package cn.jia.user.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

public interface UserBusinessMembershipMapper {
    @Insert("INSERT INTO user_business_membership(tenant_id,client_id,user_id,create_time) "
            + "VALUES(#{tenantId},#{clientId},#{userId},#{now}) "
            + "ON DUPLICATE KEY UPDATE user_id=user_business_membership.user_id")
    int ensure(@Param("tenantId") String tenantId, @Param("clientId") String clientId,
               @Param("userId") long userId, @Param("now") long now);
}
