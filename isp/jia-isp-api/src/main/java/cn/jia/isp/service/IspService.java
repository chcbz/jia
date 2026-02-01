package cn.jia.isp.service;

import cn.jia.isp.entity.IspDomainEntity;
import cn.jia.isp.entity.IspServerEntity;
import com.github.pagehelper.PageInfo;

/**
 * 接口IspService定义了与ISP（Internet Service Provider）相关的服务方法，
 * 包括服务器和域名的管理操作。它使用PageInfo来分页显示查询结果，
 * 并对服务器和域名实体进行CRUD操作。
 */
public interface IspService {
    /**
     * 根据示例对象查询服务器列表，并进行分页。
     *
     * @param example 查询条件示例对象，包含查询所需条件。
     * @param pageNum  页码，表示查询结果的第几页。
     * @param pageSize 每页大小，表示每页包含的记录数。
     * @return 返回包含服务器实体的分页信息。
     */
    PageInfo<IspServerEntity> listServer(IspServerEntity example, int pageNum, int pageSize, String orderBy);

    /**
     * 创建新的服务器记录。
     *
     * @param record 待创建的服务器实体，包含所有需要插入的字段。
     */
    void createServer(IspServerEntity record);

    /**
     * 根据服务器ID查找服务器详细信息。
     *
     * @param id 服务器ID，唯一标识一个服务器记录。
     * @return 返回找到的服务器实体。
     * @throws Exception 如果查找过程中发生错误，抛出异常。
     */
    IspServerEntity findServer(Long id) throws Exception;

    /**
     * 更新服务器记录。
     *
     * @param record 包含更新后信息的服务器实体。
     * @return 返回更新后的服务器实体。
     * @throws Exception 如果更新过程中发生错误，抛出异常。
     */
    IspServerEntity updateServer(IspServerEntity record) throws Exception;

    /**
     * 删除指定ID的服务器记录。
     *
     * @param id 服务器ID，标识要删除的记录。
     */
    void deleteServer(Long id);

    /**
     * 根据示例对象查询域名列表，并进行分页。
     *
     * @param example 查询条件示例对象，包含查询所需条件。
     * @param pageNum  页码，表示查询结果的第几页。
     * @param pageSize 每页大小，表示每页包含的记录数。
     * @return 返回包含域名实体的分页信息。
     */
    PageInfo<IspDomainEntity> listDomain(IspDomainEntity example, int pageNum, int pageSize, String orderBy);
}
