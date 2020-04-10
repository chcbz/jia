package cn.jia.isp.service;

import cn.jia.isp.entity.IspDomain;
import cn.jia.isp.entity.IspServer;
import cn.jia.isp.entity.IspSmbVDir;
import com.github.pagehelper.Page;

public interface IspService {
	
	Page<IspServer> listServer(IspServer example, int pageNo, int pageSize);
	
	void createServer(IspServer record, Integer userId);

	IspServer findServer(Integer id);

	IspServer updateServer(IspServer record) throws Exception;

	void deleteServer(Integer id);

	String execCommand(Integer serverId, String cmd) throws Exception;

	void execCommandAsync(Integer serverId, String cmd, Integer userId);

	Page<IspDomain> listDomain(IspDomain example, int pageNo, int pageSize);

	IspDomain createDomain(IspDomain record) throws Exception;

	IspDomain findDomain(Integer id) throws Exception;

	IspDomain updateDomain(IspDomain record) throws Exception;

	void deleteDomain(Integer id);

	void createSSL(Integer id, Integer userId);

	void createSQL(IspDomain record, Integer userId);

	void createCMS(Integer id, Integer userId) throws Exception;

	Page<IspSmbVDir> listSmbVDir(IspSmbVDir example, int pageNo, int pageSize);

	void createSmbVDir(IspSmbVDir record) throws Exception;

	IspSmbVDir findSmbVDir(Integer id);

	IspSmbVDir updateSmbVDir(IspSmbVDir record) throws Exception;

	void deleteSmbVDir(Integer id) throws Exception;
}
