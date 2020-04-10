package cn.jia.user.dao;

import cn.jia.core.entity.Action;
import cn.jia.user.entity.Auth;
import com.github.pagehelper.Page;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface AuthMapper {
	int insert(Auth record);

	int insertSelective(Auth record);

	List<Auth> selectByRoleId(Integer roleId);

	void batchAdd(@Param("authList") List<Auth> authList);

	void batchDel(@Param("authList") List<Auth> authList);
	
	Page<Action> selectPermsByRole(Integer roleId);

	Page<Action> selectPermsByUser(@Param("userId") Integer userId, @Param("clientId") String clientId);
}