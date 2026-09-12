package cn.jia.oauth.mapper;

import cn.jia.oauth.entity.OauthApiKeyEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * OAuth API密钥Mapper接口
 * 提供API密钥的数据访问操作
 */
public interface OauthApiKeyMapper extends BaseMapper<OauthApiKeyEntity> {

    @Update("""
            UPDATE oauth_api_key
            SET status=0,update_time=#{now}
            WHERE BINARY id=BINARY #{keyId}
              AND BINARY tenant_id=BINARY #{tenantId}
              AND BINARY client_id=BINARY #{clientId}
              AND BINARY jiacn=BINARY #{jiacn}
              AND BINARY key_name=BINARY #{keyName}
              AND status=1
            """)
    int disableManagedKey(
            @Param("keyId") String keyId,
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("jiacn") String jiacn,
            @Param("keyName") String keyName,
            @Param("now") long now);
}
