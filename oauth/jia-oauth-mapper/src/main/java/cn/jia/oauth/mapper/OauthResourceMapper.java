package cn.jia.oauth.mapper;

import cn.jia.oauth.entity.OauthResourceEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import java.util.List;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author chc
 * @since 2021-11-07
 */
public interface OauthResourceMapper extends BaseMapper<OauthResourceEntity> {
    List<OauthResourceEntity> selectByExample(OauthResourceEntity record);
}
