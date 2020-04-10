package cn.jia.isp.service;

import cn.jia.isp.entity.IspDomain;
import cn.jia.isp.entity.IspServer;
import com.github.pagehelper.Page;

public interface IspService {
	
	Page<IspServer> listServer(IspServer example, int pageNo, int pageSize);
	
	void createServer(IspServer record);

	IspServer findServer(Integer id) throws Exception;

	IspServer updateServer(IspServer record) throws Exception;

	void deleteServer(Integer id);

	Page<IspDomain> listDomain(IspDomain example, int pageNo, int pageSize);
}
