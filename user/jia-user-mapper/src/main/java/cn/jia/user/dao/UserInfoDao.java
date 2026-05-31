package cn.jia.user.dao;

import cn.jia.core.dao.IBaseDao;
import cn.jia.user.entity.UserEntity;

import java.util.List;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author chc
 * @since 2021-11-20
 */
public interface UserInfoDao extends IBaseDao<UserEntity> {
    List<UserEntity> selectAll();

    UserEntity selectByJiacn(String jiacn);

    UserEntity selectByUsername(String username);

    UserEntity selectByOpenid(String openid);

    UserEntity selectByPhone(String phone);

    List<UserEntity> selectByRole(Long roleId);

    List<UserEntity> selectByGroup(Long groupId);

    List<UserEntity> selectByOrg(Long orgId);

    List<UserEntity> searchByExample(UserEntity user);
}
