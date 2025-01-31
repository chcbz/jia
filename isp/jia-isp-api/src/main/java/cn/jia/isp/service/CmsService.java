package cn.jia.isp.service;

import cn.jia.isp.entity.*;
import com.github.pagehelper.PageInfo;

import java.util.Map;

/**
 * 内容管理系统(CMS)服务接口，定义了对表格、列、配置和行数据的操作
 */
public interface CmsService {

    /**
     * 获取表格列表
     *
     * @param example 查询示例
     * @param pageNo  页码
     * @param pageSize 每页大小
     * @return 表格信息分页结果
     */
	PageInfo<CmsTableEntity> listTable(CmsTableEntity example, int pageNo, int pageSize);

    /**
     * 创建新表格
     *
     * @param record 要创建的表格数据传输对象
     * @return 创建后的表格实体
     */
	CmsTableEntity createTable(CmsTableDTO record);

    /**
     * 根据ID查找表格
     *
     * @param id 表格ID
     * @return 表格实体
     * @throws Exception 如果查找失败
     */
	CmsTableEntity findTable(Long id) throws Exception;

    /**
     * 根据名称查找表格
     *
     * @param name 表格名称
     * @return 表格实体
     */
	CmsTableEntity findTableByName(String name);

    /**
     * 更新表格信息
     *
     * @param record 要更新的表格实体
     * @return 更新后的表格实体
     */
	CmsTableEntity updateTable(CmsTableEntity record);

    /**
     * 删除表格
     *
     * @param id 表格ID
     */
	void deleteTable(Long id);

    /**
     * 获取列列表
     *
     * @param example 查询示例
     * @param pageNo  页码
     * @param pageSize 每页大小
     * @return 列信息分页结果
     */
	PageInfo<CmsColumnEntity> listColumn(CmsColumnEntity example, int pageNo, int pageSize);

    /**
     * 创建新列
     *
     * @param record 要创建的列实体
     * @return 创建后的列实体
     */
	CmsColumnEntity createColumn(CmsColumnEntity record);

    /**
     * 根据ID查找列
     *
     * @param id 列ID
     * @return 列实体
     * @throws Exception 如果查找失败
     */
	CmsColumnEntity findColumn(Long id) throws Exception;

    /**
     * 更新列信息
     *
     * @param record 要更新的列实体
     * @return 更新后的列实体
     */
	CmsColumnEntity updateColumn(CmsColumnEntity record);

    /**
     * 删除列
     *
     * @param id 列ID
     */
	void deleteColumn(Long id);

    /**
     * 根据客户端ID获取配置
     *
     * @param clientId 客户端ID
     * @return 配置实体
     */
	CmsConfigEntity selectConfig(String clientId);

    /**
     * 创建新配置
     *
     * @param config 要创建的配置实体
     */
	void createConfig(CmsConfigEntity config);

    /**
     * 更新配置信息
     *
     * @param config 要更新的配置实体
     */
	void updateConfig(CmsConfigEntity config);

    /**
     * 获取行数据列表
     *
     * @param example 查询示例
     * @param pageNo  页码
     * @param pageSize 每页大小
     * @return 行数据分页结果
     */
	PageInfo<Map<String, Object>> listRow(CmsRowExample example, int pageNo, int pageSize);

    /**
     * 创建新行数据
     *
     * @param record 要创建的行数据传输对象
     * @return 创建的行数据ID
     */
	int createRow(CmsRowDTO record);

    /**
     * 根据条件查找行数据
     *
     * @param record 查询条件
     * @return 行数据
     */
	Map<String, Object> findRow(CmsRowDTO record);

    /**
     * 更新行数据
     *
     * @param record 要更新的行数据传输对象
     * @return 更新的行数据ID
     */
	int updateRow(CmsRowDTO record);

    /**
     * 删除行数据
     *
     * @param record 查询条件
     */
	void deleteRow(CmsRowDTO record);
	
}
