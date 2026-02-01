package cn.jia.isp.service;

import cn.jia.isp.entity.CarBrandEntity;
import cn.jia.isp.entity.CarBrandAudiEntity;
import cn.jia.isp.entity.CarBrandMfEntity;
import cn.jia.isp.entity.CarBrandVersionEntity;
import com.github.pagehelper.PageInfo;

/**
 * 车辆服务接口
 */
public interface CarService {

    /**
     * 查询品牌列表
     * @param example 查询条件
     * @param pageNum 页码
     * @param pageSize 每页记录数
     * @return 包含品牌列表的 PageInfo 对象
     */
    PageInfo<CarBrandEntity> listBrand(CarBrandEntity example, int pageNum, int pageSize, String orderBy);

    /**
     * 创建新品牌
     * @param record 品牌实体
     * @return 创建的品牌实体
     */
    CarBrandEntity createBrand(CarBrandEntity record);

    /**
     * 根据 ID 查找品牌
     * @param id 品牌 ID
     * @return 找到的品牌实体
     * @throws Exception 如果品牌未找到
     */
    CarBrandEntity findBrand(Long id) throws Exception;

    /**
     * 更新品牌
     * @param record 品牌实体
     * @return 更新后的品牌实体
     */
    CarBrandEntity updateBrand(CarBrandEntity record);

    /**
     * 根据 ID 删除品牌
     * @param id 品牌 ID
     */
    void deleteBrand(Long id);

    /**
     * 查询 Audi 品牌列表
     * @param example 查询条件
     * @param pageNum 页码
     * @param pageSize 每页记录数
     * @return 包含 Audi 品牌列表的 PageInfo 对象
     */
    PageInfo<CarBrandAudiEntity> listBrandAudi(CarBrandAudiEntity example, int pageNum, int pageSize, String orderBy);

    /**
     * 创建新 Audi 品牌
     * @param record Audi 品牌实体
     * @return 创建的 Audi 品牌实体
     */
    CarBrandAudiEntity createBrandAudi(CarBrandAudiEntity record);

    /**
     * 根据 ID 查找 Audi 品牌
     * @param id Audi 品牌 ID
     * @return 找到的 Audi 品牌实体
     * @throws Exception 如果 Audi 品牌未找到
     */
    CarBrandAudiEntity findBrandAudi(Long id) throws Exception;

    /**
     * 更新 Audi 品牌
     * @param record Audi 品牌实体
     * @return 更新后的 Audi 品牌实体
     */
    CarBrandAudiEntity updateBrandAudi(CarBrandAudiEntity record);

    /**
     * 根据 ID 删除 Audi 品牌
     * @param id Audi 品牌 ID
     */
    void deleteBrandAudi(Long id);

    /**
     * 查询 MF 品牌列表
     * @param example 查询条件
     * @param pageNum 页码
     * @param pageSize 每页记录数
     * @return 包含 MF 品牌列表的 PageInfo 对象
     */
    PageInfo<CarBrandMfEntity> listBrandMf(CarBrandMfEntity example, int pageNum, int pageSize, String orderBy);

    /**
     * 创建新 MF 品牌
     * @param record MF 品牌实体
     * @return 创建的 MF 品牌实体
     */
    CarBrandMfEntity createBrandMf(CarBrandMfEntity record);

    /**
     * 根据 ID 查找 MF 品牌
     * @param id MF 品牌 ID
     * @return 找到的 MF 品牌实体
     * @throws Exception 如果 MF 品牌未找到
     */
    CarBrandMfEntity findBrandMf(Long id) throws Exception;

    /**
     * 更新 MF 品牌
     * @param record MF 品牌实体
     * @return 更新后的 MF 品牌实体
     */
    CarBrandMfEntity updateBrandMf(CarBrandMfEntity record);

    /**
     * 根据 ID 删除 MF 品牌
     * @param id MF 品牌 ID
     */
    void deleteBrandMf(Long id);

    /**
     * 查询品牌版本列表
     * @param example 查询条件
     * @param pageNum 页码
     * @param pageSize 每页记录数
     * @return 包含品牌版本列表的 PageInfo 对象
     */
    PageInfo<CarBrandVersionEntity> listBrandVersion(CarBrandVersionEntity example, int pageNum, int pageSize, String orderBy);

    /**
     * 创建新品牌版本
     * @param record 品牌版本实体
     * @return 创建的品牌版本实体
     */
    CarBrandVersionEntity createBrandVersion(CarBrandVersionEntity record);

    /**
     * 根据 ID 查找品牌版本
     * @param id 品牌版本 ID
     * @return 找到的品牌版本实体
     * @throws Exception 如果品牌版本未找到
     */
    CarBrandVersionEntity findBrandVersion(Long id) throws Exception;

    /**
     * 更新品牌版本
     * @param record 品牌版本实体
     * @return 更新后的品牌版本实体
     */
    CarBrandVersionEntity updateBrandVersion(CarBrandVersionEntity record);

    /**
     * 根据 ID 删除品牌版本
     * @param id 品牌版本 ID
     */
    void deleteBrandVersion(Long id);
}