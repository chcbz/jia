package cn.jia.user.dao;

import cn.jia.user.entity.Org;
import com.github.pagehelper.Page;
import org.apache.ibatis.annotations.Param;

public interface OrgMapper {
    int deleteByPrimaryKey(Integer id);

    int insert(Org record);

    int insertSelective(Org record);

    Org selectByPrimaryKey(Integer id);

    int updateByPrimaryKeySelective(Org record);

    int updateByPrimaryKey(Org record);

    Page<Org> selectAll();

    Page<Org> selectByExample(Org example);

    Org findByClientId(String clientId);

    Org findByCode(String code);

    Page<Org> selectByParentId(Integer pId);

    Page<Org> selectByUserId(Integer userId);

    Org findFirstChild(Integer pId);

    String findDirector(@Param("orgId") Integer orgId, @Param("roleId") Integer roleId);

    Org findPreOrg(Integer orgId);

    Org findNextOrg(Integer orgId);

    Org findParentOrg(Integer orgId);
}