package cn.jia.isp.service.impl;

import cn.jia.core.exception.EsRuntimeException;
import cn.jia.isp.common.IspErrorConstants;
import cn.jia.isp.dao.IspDnsRecordDao;
import cn.jia.isp.dao.IspDnsRecordHistoryDao;
import cn.jia.isp.dao.IspDnsZoneDao;
import cn.jia.isp.entity.IspDnsRecordEntity;
import cn.jia.isp.entity.DnsRecordDTO;
import cn.jia.isp.entity.IspDnsRecordHistoryEntity;
import cn.jia.isp.entity.IspDnsZoneEntity;
import cn.jia.isp.service.DnsService;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.util.List;

@Named
public class DnsServiceImpl implements DnsService {
	@Inject
	private IspDnsZoneDao dnsZoneDao;
	@Inject
	private IspDnsRecordDao dnsRecordDao;
	@Inject
	private IspDnsRecordHistoryDao dnsRecordHistoryDao;
	
	/**
	 * 新增主机
	 * 
	 * @param record 主机信息
	 * @return 是否成功
	 */
	@Override
	public boolean insertDnsZone(IspDnsZoneEntity record) {
		return dnsZoneDao.insert(record) > 0;
	}
	
	/**
	 * 更新动态域名记录信息
	 * 
	 * @param dnsRecordDTO 动态域名记录信息
	 */
	@Override
	public void updateDnsRecord(DnsRecordDTO dnsRecordDTO) {
		//判断域名是否存在
		IspDnsRecordEntity dnsRecord = dnsRecordDao.selectByDomain(dnsRecordDTO.getDomain());
		if(dnsRecord == null){
			throw new EsRuntimeException(IspErrorConstants.DOMAIN_NOT_EXIST);
		}
		//判断key是否正确
		IspDnsZoneEntity dnsZoneExample = new IspDnsZoneEntity();
		dnsZoneExample.setId(dnsRecord.getZoneId());
		dnsZoneExample.setKeyName(dnsRecordDTO.getKeyName()==null?"":dnsRecordDTO.getKeyName());
		List<IspDnsZoneEntity> dnsZoneList = dnsZoneDao.selectByEntity(dnsZoneExample);
		if(dnsZoneList == null || dnsZoneList.size() == 0){
			throw new EsRuntimeException(IspErrorConstants.KEY_INCORRECT);
		}
		IspDnsZoneEntity dnsZone = dnsZoneList.get(0);
		//更新域名记录信息
		IspDnsRecordEntity upDnsRecord = new IspDnsRecordEntity();
		upDnsRecord.setId(dnsRecord.getId());
		upDnsRecord.setIp(dnsRecordDTO.getIp());
		upDnsRecord.setTtl(dnsRecordDTO.getTtl() != null ? dnsRecordDTO.getTtl() : dnsRecord.getTtl());
		upDnsRecord.setType(dnsRecordDTO.getType() != null ? dnsRecordDTO.getType() : dnsRecord.getType());
		dnsRecordDao.updateById(upDnsRecord);
		//插入历史记录表
		IspDnsRecordHistoryEntity history = new IspDnsRecordHistoryEntity();
		history.setDomain(dnsRecord.getDomain());
		history.setZoneId(dnsRecord.getZoneId());
		history.setIp(upDnsRecord.getIp());
		history.setTtl(upDnsRecord.getTtl());
		history.setType(upDnsRecord.getType());
		dnsRecordHistoryDao.insert(history);
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
	public List<IspDnsRecordEntity> listDnsRecord(Long zoneId) {
		return dnsRecordDao.selectByZoneId(zoneId);
	}

	@Override
	public List<IspDnsZoneEntity> listDnsZone(IspDnsZoneEntity example) {
		return dnsZoneDao.selectByEntity(example);
	}
}
