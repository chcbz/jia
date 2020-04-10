package cn.jia.isp.service.impl;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import cn.jia.core.exception.EsRuntimeException;
import cn.jia.isp.common.ErrorConstants;
import cn.jia.isp.dao.DnsRecordHistoryMapper;
import cn.jia.isp.dao.DnsRecordMapper;
import cn.jia.isp.dao.DnsZoneMapper;
import cn.jia.isp.entity.DnsRecord;
import cn.jia.isp.entity.DnsRecordDTO;
import cn.jia.isp.entity.DnsRecordHistory;
import cn.jia.isp.entity.DnsZone;
import cn.jia.isp.service.DnsService;

@Service
public class DnsServiceImpl implements DnsService {
	@Autowired
	private DnsZoneMapper dnsZoneMapper;
	@Autowired
	private DnsRecordMapper dnsRecordMapper;
	@Autowired
	private DnsRecordHistoryMapper dnsRecordHistoryMapper;
	
	/**
	 * 新增主机
	 * @param record
	 * @return
	 */
	@Override
	public boolean insertDnsZone(DnsZone record) {
		return dnsZoneMapper.insertSelective(record) > 0;
	}
	
	/**
	 * 更新动态域名记录信息
	 * @param dnsRecordDTO
	 * @throws EsRuntimeException
	 */
	@Override
	public void updateDnsRecord(DnsRecordDTO dnsRecordDTO) throws EsRuntimeException{
		//判断域名是否存在
		DnsRecord dnsRecord = dnsRecordMapper.selectByDomain(dnsRecordDTO.getDomain());
		if(dnsRecord == null){
			throw new EsRuntimeException(ErrorConstants.DOMAIN_NOT_EXIST);
		}
		//判断key是否正确
		DnsZone dnsZoneExample = new DnsZone();
		dnsZoneExample.setId(dnsRecord.getZoneId());
		dnsZoneExample.setKeyName(dnsRecordDTO.getKeyName()==null?"":dnsRecordDTO.getKeyName());
		List<DnsZone> dnsZoneList = dnsZoneMapper.selectByExample(dnsZoneExample);
		if(dnsZoneList == null || dnsZoneList.size() == 0){
			throw new EsRuntimeException(ErrorConstants.KEY_INCORRECT);
		}
		DnsZone dnsZone = dnsZoneList.get(0);
		//更新域名记录信息
		DnsRecord upDnsRecord = new DnsRecord();
		upDnsRecord.setId(dnsRecord.getId());
		upDnsRecord.setIp(dnsRecordDTO.getIp());
		upDnsRecord.setTtl(dnsRecordDTO.getTtl() != null ? dnsRecordDTO.getTtl() : dnsRecord.getTtl());
		upDnsRecord.setType(dnsRecordDTO.getType() != null ? dnsRecordDTO.getType() : dnsRecord.getType());
		dnsRecordMapper.updateByPrimaryKeySelective(upDnsRecord);
		//插入历史记录表
		DnsRecordHistory history = new DnsRecordHistory();
		history.setDomain(dnsRecord.getDomain());
		history.setZoneId(dnsRecord.getZoneId());
		history.setIp(upDnsRecord.getIp());
		history.setTtl(upDnsRecord.getTtl());
		history.setType(upDnsRecord.getType());
		dnsRecordHistoryMapper.insertSelective(history);
		//返回最新信息
		dnsRecordDTO.setDomain(dnsRecord.getDomain());
		dnsRecordDTO.setIp(upDnsRecord.getIp());
		dnsRecordDTO.setHistoryIp(dnsRecord.getIp());
		dnsRecordDTO.setKeyName(dnsZone.getKeyName());
		dnsRecordDTO.setKeyValue(dnsZone.getKeyValue());
		dnsRecordDTO.setTtl(upDnsRecord.getTtl());
		dnsRecordDTO.setType(upDnsRecord.getType());
		dnsRecordDTO.setZone(dnsZone.getZone());
	}

	@Override
	public List<DnsRecord> listDnsRecord(Integer zoneId) {
		return dnsRecordMapper.selectByZoneId(zoneId);
	}

	@Override
	public List<DnsZone> listDnsZone(DnsZone example) {
		return dnsZoneMapper.selectByExample(example);
	}
}
