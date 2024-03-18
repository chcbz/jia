package cn.jia.isp.service;

import cn.jia.core.exception.EsRuntimeException;
import cn.jia.isp.entity.IspDnsRecordEntity;
import cn.jia.isp.entity.DnsRecordDTO;
import cn.jia.isp.entity.IspDnsZoneEntity;

import java.util.List;

public interface DnsService {
	/**
	 * 新增主机
	 * @param record
	 * @return
	 */
	public boolean insertDnsZone(IspDnsZoneEntity record);
	
	/**
	 * 更新动态域名记录信息
	 * @param dnsRecordDTO
	 * @throws EsRuntimeException
	 */
	public void updateDnsRecord(DnsRecordDTO dnsRecordDTO) throws EsRuntimeException;
	
	/**
	 * 域名记录列表
	 * @return
	 */
	public List<IspDnsRecordEntity> listDnsRecord(Long zoneId);
	
	public List<IspDnsZoneEntity> listDnsZone(IspDnsZoneEntity example);
}
