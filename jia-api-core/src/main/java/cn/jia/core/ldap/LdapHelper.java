package cn.jia.core.ldap;

import cn.jia.core.util.BeanUtil;
import cn.jia.core.util.StringUtils;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import org.springframework.ldap.control.PagedResultsCookie;
import org.springframework.ldap.control.PagedResultsDirContextProcessor;
import org.springframework.ldap.core.ContextMapper;
import org.springframework.ldap.core.DirContextOperations;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.filter.AndFilter;
import org.springframework.ldap.filter.EqualsFilter;
import org.springframework.ldap.filter.WhitespaceWildcardsFilter;
import org.springframework.ldap.odm.core.ObjectDirectoryMapper;
import org.springframework.ldap.odm.core.impl.DefaultObjectDirectoryMapper;
import org.springframework.ldap.support.LdapUtils;

import javax.naming.Name;
import javax.naming.directory.SearchControls;
import java.beans.IntrospectionException;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Map;

@SuppressWarnings("unchecked")
public class LdapHelper<T> {
    private LdapTemplate ldapTemplate;

    public static <T> LdapHelper<T> newInstance(LdapTemplate ldapTemplate) {
        LdapHelper<T> ldapDAO = new LdapHelper<>();
        ldapDAO.ldapTemplate = ldapTemplate;
        return ldapDAO;
    }

    /**
     * 创建实体
     * @param t 实体信息
     */
    public void create(T t) {
        ldapTemplate.create(t);
    }
    /**
     * 更新实体
     * @param t 实体信息
     */
    public void update(T t) {
        ldapTemplate.update(t);
    }
    /**
     * 删除属性
     * @param t 实体信息
     */
    public void delete(T t) {
        ldapTemplate.delete(t);
    }
    /**
     * 根据唯一DN进行查找
     * @param t 过滤条件
     * @return 实体信息
     */
    public T findByPrimaryKey(final T t) {
        return (T) ldapTemplate.lookup(buildDn(t),  getContextMapper(t));
    }

    /**
     * 根据dn直接获取实体信息
     * @param t 过滤条件
     * @param dn DN信息
     * @return 实体信息
     */
    public T findByDN(final T t,String dn) {
        return (T) ldapTemplate.lookup(dn,  getContextMapper(t));
    }
    /**
     * 根据实体条件精确查找进行查找
     * @param t 过滤条件
     * @return  返回查找的集合
     */
    public List<T> findByEqualFilter(final T t) {
        return  ldapTemplate.search(buildDn(t), getEqualFilter(t).encode(), getContextMapper(t));

    }

    /**
     * 根据实体条件进行查找
     * @param t 过滤条件
     * @return  返回查找的集合
     */
    public List<T> findByFilter(final T t) {
        return  ldapTemplate.search(buildDn(t), getFilter(t).encode(), getContextMapper(t));

    }

    /**
     * 根据实体类型查找所有实体
     * @param t 过滤条件
     * @return  返回实体集合
     */
    public List<T> findAll(final T t) {
        return  ldapTemplate.search(buildDn(t),  getObjectclass(t).encode(), getContextMapper(t));

    }
    /**
     * 根据实体的分页属性进行查获分页信息
     * @param t 过滤条件
     * @return 结果集
     */
    public Page<T> getPages(T t){
        Page<T> basePage = PageHelper.getLocalPage();
        int totalRow = findByFilter(t).size();
        basePage.addAll(getAllPageMap(null,t,(basePage.getPageSize()*basePage.getPageNum())));
        basePage.setTotal(totalRow);
        basePage.setPages((totalRow+basePage.getPageSize()-1)/basePage.getPageSize());
        return basePage;
    }
    /**
     * 根据传入记录数查处所需要的信息
     * @param cookie 分页Cookie
     * @param t 实体信息
     * @param pageSize 每页大小
     * @return 结果
     */
    private List<T> getAllPageMap(PagedResultsCookie cookie, T t, Integer pageSize) {
        PagedResultsDirContextProcessor control = new PagedResultsDirContextProcessor (pageSize, cookie);
        SearchControls searchControls = new SearchControls();
        searchControls.setSearchScope(SearchControls.SUBTREE_SCOPE);
        return ldapTemplate.search(buildDn(t),  getFilter(t).encode(),searchControls, getContextMapper(t), control);
    }

    /**
     * 查找全部信息所需要的filter检索方法
     * @param t 条件Bean
     * @return objectclass检错条件
     */
    private AndFilter getObjectclass(T t){
        AndFilter filter = new AndFilter();
        try {
            Map<String, Object> map = BeanUtil.convertBean(t);
            for(String mkey:map.keySet()){      //根据实体只获得他对应的objectclass的值
                if ("objectclass".equals(mkey)) {
                    filter.and(new EqualsFilter(mkey, map.get(mkey).toString()));
                }
            }
        } catch (IntrospectionException | IllegalAccessException | InvocationTargetException e) {
            e.printStackTrace();
        }
        return filter;
    }

    /**
     * 根据条件模糊查找的条件方法
     * @param t 条件Bean
     * @return 过滤条件
     */
    private AndFilter getFilter(T t) {
        AndFilter filter = new AndFilter();
        try {
            Map<String, Object> map = BeanUtil.convertBean(t);
            for(String mkey:map.keySet()){
                if ("objectclass".equals(mkey)) {
                    filter.and(new EqualsFilter(mkey, map.get(mkey).toString()));
                }
                if(StringUtils.isNotBlank(map.get(mkey).toString()) && !"objectclass".equals(mkey))
                    filter.and(new WhitespaceWildcardsFilter(mkey, map.get(mkey).toString()));
            }
        } catch (IntrospectionException | IllegalAccessException | InvocationTargetException e) {
            e.printStackTrace();
        }
        return filter;
    }

    /**
     * 根据条件精确查找的条件方法
     * @param t 条件Bean
     * @return 过滤条件
     */
    private AndFilter getEqualFilter(T t) {
        AndFilter filter = new AndFilter();
        try {
            Map<String, Object> map = BeanUtil.convertBean(t);
            for(String mkey:map.keySet()){
                if ("objectclass".equals(mkey)) {
                    filter.and(new EqualsFilter(mkey, map.get(mkey).toString()));
                }
                if(StringUtils.isNotBlank(map.get(mkey).toString()) && !"objectclass".equals(mkey))
                    filter.and(new EqualsFilter(mkey, map.get(mkey).toString()));
            }
        } catch (IntrospectionException | IllegalAccessException | InvocationTargetException e) {
            e.printStackTrace();
        }
        return filter;
    }
    /**
     * 构造查询实体UUID方法
     * @param t 条件
     * @return DN信息
     */
    private Name buildDn(T t) {
        Name dn = null;
        if(t != null) {
            try {
                Map<String, Object> map = BeanUtil.convertBean(t);
                dn = (Name)map.get("dn");
            } catch (IntrospectionException | IllegalAccessException | InvocationTargetException e) {
                e.printStackTrace();
            }
        } else {
            return LdapUtils.emptyLdapName();
        }
        return dn;
    }

    /**
     * 构造查找组织的dn
     * @param t 实体
     * @return DN信息
     */
    public String findDn(T t) {
        Name dn = buildDn( t);
        return dn.toString();
    }
    /**
     * 查询获得实体属性构造器
     * @param t 实体信息
     * @return 构造器
     */
    private ContextMapper getContextMapper(final T t) {
        ObjectDirectoryMapper odm = new DefaultObjectDirectoryMapper();
        return ctx -> odm.mapFromLdapDataEntry((DirContextOperations) ctx, t.getClass());
    }

}
