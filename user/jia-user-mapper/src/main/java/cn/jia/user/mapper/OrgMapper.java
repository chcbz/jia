package cn.jia.user.mapper;

import cn.jia.user.entity.OrgEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author chc
 * @since 2021-11-20
 */
public interface OrgMapper extends BaseMapper<OrgEntity> {

    List<OrgEntity> selectByUserId(Long userId);

    OrgEntity findFirstChild(Long id);

    String findDirector(@Param("orgId") Long orgId, @Param("roleId") Long roleId);

    OrgEntity findPreOrg(Long id);

    OrgEntity findNextOrg(Long id);

    OrgEntity findParentOrg(Long id);
}
