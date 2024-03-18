package cn.jia.isp.service;

import cn.jia.isp.entity.IspDomainEntity;
import cn.jia.isp.entity.IspServerEntity;
import com.github.pagehelper.PageInfo;

public interface IspService {
	
	PageInfo<IspServerEntity> listServer(IspServerEntity example, int pageNo, int pageSize);
	
	void createServer(IspServerEntity record);

	IspServerEntity findServer(Long id) throws Exception;

	IspServerEntity updateServer(IspServerEntity record) throws Exception;

	void deleteServer(Long id);

	PageInfo<IspDomainEntity> listDomain(IspDomainEntity example, int pageNo, int pageSize);
}
