package cn.jia.isp.service;

import java.util.List;

import cn.jia.core.exception.EsRuntimeException;
import cn.jia.isp.entity.DnsRecord;
import cn.jia.isp.entity.DnsRecordDTO;
import cn.jia.isp.entity.DnsZone;

public interface DnsService {
	/**
	 * 新增主机
	 * @param record
	 * @return
	 */
	public boolean insertDnsZone(DnsZone record);
	
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
	public List<DnsRecord> listDnsRecord(Integer zoneId);
	
	public List<DnsZone> listDnsZone(DnsZone example);
}
