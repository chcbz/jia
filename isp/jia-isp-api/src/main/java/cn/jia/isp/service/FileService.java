package cn.jia.isp.service;

import cn.jia.isp.entity.IspFileEntity;
import cn.jia.isp.enums.IspFileTypeEnum;
import com.github.pagehelper.PageInfo;

/**
 * 文件服务接口定义
 * 提供文件创建、查找、更新和删除等操作
 */
public interface FileService {

    /**
     * 创建一个新的文件记录
     *
     * @param record 文件记录实体，包含文件的必要信息
     * @return 创建后的文件实体，包括生成的文件ID
     */
	IspFileEntity create(IspFileEntity record);

    /**
     * 根据文件ID查找文件记录
     *
     * @param id 文件记录的唯一标识符
     * @return 对应ID的文件实体，如果找不到则返回null
     */
	IspFileEntity find(Long id);

    /**
     * 列出与示例文件实体匹配的文件记录列表
     *
     * @param example 示例文件实体，用于指定查询条件
     * @param pageNum 页码，用于分页查询
     * @param pageSize 每页大小，用于分页查询
     * @return 包含文件实体的分页信息
     */
	PageInfo<IspFileEntity> list(IspFileEntity example, int pageNum, int pageSize, String orderBy);

    /**
     * 更新文件记录
     *
     * @param record 需要更新的文件记录实体，包括新的属性值
     * @return 更新后的文件实体
     */
	IspFileEntity update(IspFileEntity record);

    /**
     * 删除指定ID的文件记录
     *
     * @param id 文件记录的唯一标识符
     */
	void delete(Long id);

    /**
     * 根据文件URI查找文件记录
     *
     * @param uri 文件的统一资源标识符
     * @return 对应URI的文件实体，如果找不到则返回null
     */
	IspFileEntity findByUri(String uri);

    /**
     * 根据URL创建文件
     *
     * @param url 文件的URL地址
     * @param fileType 文件类型
     * @param fileName 文件名
     * @return 创建后的文件实体，如果创建失败则返回null
     */
    IspFileEntity create(String url, IspFileTypeEnum fileType, String fileName);
}
