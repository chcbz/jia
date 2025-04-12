package cn.jia.isp.service;

import cn.jia.isp.entity.IspDnsRecordEntity;
import cn.jia.isp.entity.DnsRecordDTO;
import cn.jia.isp.entity.IspDnsZoneEntity;

import java.util.List;

/**
 * DNS服务接口，提供DNS相关操作，如新增主机、更新域名记录等
 */
public interface DnsService {
    /**
     * 新增主机
     *
     * @param record 主机信息实体
     * @return 插入结果，true表示成功，false表示失败
     */
    boolean insertDnsZone(IspDnsZoneEntity record);

    /**
     * 更新动态域名记录信息
     *
     * @param dnsRecordDTO 域名记录DTO，包含需要更新的域名信息
     */
    void updateDnsRecord(DnsRecordDTO dnsRecordDTO);

    /**
     * 域名记录列表
     *
     * @param zoneId 域名区域ID，用于获取该区域下的所有域名记录
     * @return 域名记录列表
     */
    List<IspDnsRecordEntity> listDnsRecord(Long zoneId);

    /**
     * 列出DNS区域列表
     *
     * @param example 查询示例实体，用于指定查询条件
     * @return 匹配条件的DNS区域列表
     */
    List<IspDnsZoneEntity> listDnsZone(IspDnsZoneEntity example);
}
