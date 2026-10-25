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
            SELECT * FROM oauth_client
            WHERE client_id = #{clientId}
              AND CAST(client_id AS BINARY) = CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id) = OCTET_LENGTH(#{clientId})
            LIMIT 2
            """)
    List<OauthClientEntity> selectExactByClientId(@Param("clientId") String clientId);
}
