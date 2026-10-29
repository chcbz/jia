package cn.jia.oauth.mapper;

import cn.jia.oauth.entity.OauthClientEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author chc
 * @since 2021-11-07
 */
public interface OauthClientMapper extends BaseMapper<OauthClientEntity> {
    @Select("""
            SELECT id,client_id,client_id_issued_at,client_secret,client_secret_expires_at,
                   client_name,client_authentication_methods,appcn,authorization_grant_types,
                   redirect_uris,post_logout_redirect_uris,scopes,client_settings,token_settings,
                   create_time,update_time FROM oauth_client
            WHERE client_id = #{clientId}
              AND CAST(client_id AS BINARY) = CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id) = OCTET_LENGTH(#{clientId})
            LIMIT 2
            """)
    List<OauthClientEntity> selectExactByClientId(@Param("clientId") String clientId);
}
