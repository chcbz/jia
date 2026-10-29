package cn.jia.point.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Explicit tenant equality; production tenant zero is NOT public fallback data. */
public interface PointAccountMapper {
    String SCOPE = "tenant_id=#{tenantId} AND jiacn=#{jiacn} "
            + "AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId}) "
            + "AND OCTET_LENGTH(jiacn)=OCTET_LENGTH(#{jiacn})";

    @Select("SELECT balance FROM point_account WHERE " + SCOPE)
    Integer balance(@Param("tenantId") String tenantId, @Param("jiacn") String jiacn);

    // The duplicate branch only locks the existing account; it never resets its balance.
    @Insert("INSERT INTO point_account(tenant_id,jiacn,balance,version,create_time,update_time) "
            + "VALUES(#{tenantId},#{jiacn},0,0,#{now},#{now}) "
            + "ON DUPLICATE KEY UPDATE jiacn=point_account.jiacn")
    int ensureAccount(@Param("tenantId") String tenantId, @Param("jiacn") String jiacn,
                      @Param("now") long now);

    @Select("SELECT balance FROM point_account WHERE " + SCOPE + " FOR UPDATE")
    Integer lockBalance(@Param("tenantId") String tenantId, @Param("jiacn") String jiacn);

    @Update("UPDATE point_account SET balance=balance+#{delta},version=version+1,update_time=#{now} "
            + "WHERE " + SCOPE + " AND balance+CAST(#{delta} AS SIGNED) BETWEEN 0 AND 2147483647 "
            + "AND version < 9223372036854775807")
    int change(@Param("tenantId") String tenantId, @Param("jiacn") String jiacn,
               @Param("delta") int delta, @Param("now") long now);
}
