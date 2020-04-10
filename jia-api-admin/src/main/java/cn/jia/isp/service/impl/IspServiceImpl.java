package cn.jia.isp.service.impl;

import ch.ethz.ssh2.Connection;
import ch.ethz.ssh2.Session;
import ch.ethz.ssh2.StreamGobbler;
import cn.jia.admin.websocket.WebSocketServer;
import cn.jia.core.exception.EsRuntimeException;
import cn.jia.core.util.*;
import cn.jia.isp.common.Constants;
import cn.jia.isp.common.ErrorConstants;
import cn.jia.isp.dao.IspDomainMapper;
import cn.jia.isp.dao.IspServerMapper;
import cn.jia.isp.dao.IspSmbVDirMapper;
import cn.jia.isp.entity.IspDomain;
import cn.jia.isp.entity.IspServer;
import cn.jia.isp.entity.IspSmbVDir;
import cn.jia.isp.service.IspService;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@Slf4j
public class IspServiceImpl implements IspService {
	
	@Autowired
	private IspServerMapper ispServerMapper;
	@Autowired
	private IspDomainMapper ispDomainMapper;
	@Autowired
	private IspSmbVDirMapper ispSmbVDirMapper;
	@Autowired
	private RestTemplate restTemplate;

	@Override
	public Page<IspServer> listServer(IspServer example, int pageNo, int pageSize) {
		PageHelper.startPage(pageNo, pageSize);
		return ispServerMapper.selectByExample(example);
	}
	@Override
	@Async
	public void createServer(IspServer record, Integer userId) {
		String cmd = "curl -s -S -L https://install.wydiy.com/shell/console_install.sh | bash -s "+record.getConsoleToken()+" "+record.getConsolePort();
		Connection connection = SshUtil.login(record.getIp(), record.getSshUser(), record.getSshPassword());
		try{
			if (connection != null) {
				Session session = connection.openSession();// 打开一个会话
				session.execCommand(cmd);// 执行命令
				InputStream stdout = new StreamGobbler(session.getStdout());
				BufferedReader br = new BufferedReader(new InputStreamReader(stdout, StandardCharsets.UTF_8));
				String line;
				while ((line = br.readLine()) != null) {
					WebSocketServer.sendMessage(line, userId);
					log.info(line);
				}
				WebSocketServer.sendMessage("server_create_completed", userId);
				br.close();
				session.close();
				connection.close();

				long now = DateUtil.genTime(new Date());
				record.setCreateTime(now);
				record.setUpdateTime(now);
				ispServerMapper.insertSelective(record);

				WebSocketServer.sendMessage("成功初始化服务器，可以进行服务管理！", userId);
			}
		} catch (Exception e) {
			WebSocketServer.sendMessage(e.toString(), userId);
			log.error("执行命令失败,链接conn:" + connection + ",执行的命令：" + cmd, e);
		}
	}
	@Override
	public IspServer findServer(Integer id) {
		return ispServerMapper.selectByPrimaryKey(id);
	}
	@Override
	public IspServer updateServer(IspServer record) throws Exception {
		if(record.getIp() != null || record.getSshUser() != null || record.getSshPassword() != null){
			Connection connection = SshUtil.login(record.getIp(), record.getSshUser(), record.getSshPassword());
			if(connection == null){
				throw new EsRuntimeException(ErrorConstants.ISP_CONN_FAILD);
			}
			connection.close();
		}

		long now = DateUtil.genTime(new Date());
		record.setUpdateTime(now);
		ispServerMapper.updateByPrimaryKeySelective(record);
		return record;
	}
	@Override
	public void deleteServer(Integer id) {
		ispServerMapper.deleteByPrimaryKey(id);
	}

	@Override
	public String execCommand(Integer serverId, String cmd) throws Exception {
		IspServer record = ispServerMapper.selectByPrimaryKey(serverId);
		Connection connection = SshUtil.login(record.getIp(), record.getSshUser(), record.getSshPassword());
		StringBuilder result = new StringBuilder();
		try{
			if (connection != null) {
				Session session = connection.openSession();// 打开一个会话
				session.execCommand(cmd);// 执行命令
				InputStream stdout = new StreamGobbler(session.getStdout());
				BufferedReader br = new BufferedReader(new InputStreamReader(stdout, StandardCharsets.UTF_8));
				String line;
				while ((line = br.readLine()) != null) {
					result.append(line);
				}
				br.close();
				session.close();
				connection.close();
			}
		} catch (Exception e) {
			throw new EsRuntimeException(e);
		}
		return result.toString();
	}
	@Override
	@Async
	public void execCommandAsync(Integer serverId, String cmd, Integer userId) {
		IspServer record = ispServerMapper.selectByPrimaryKey(serverId);
		Connection connection = SshUtil.login(record.getIp(), record.getSshUser(), record.getSshPassword());
		try{
			if (connection != null) {
				Session session = connection.openSession();// 打开一个会话
				session.execCommand(cmd);// 执行命令
				InputStream stdout = new StreamGobbler(session.getStdout());
				BufferedReader br = new BufferedReader(new InputStreamReader(stdout, StandardCharsets.UTF_8));
				String line;
				while ((line = br.readLine()) != null) {
					WebSocketServer.sendMessage(line, userId);
					log.info(line);
				}
				WebSocketServer.sendMessage("exec_command_completed", userId);
				br.close();
				session.close();
				connection.close();
			}
		} catch (Exception e) {
			WebSocketServer.sendMessage(e.toString(), userId);
			log.error("执行命令失败,链接conn:" + connection + ",执行的命令：" + cmd, e);
		}
	}

	@Override
	public Page<IspDomain> listDomain(IspDomain example, int pageNo, int pageSize) {
		PageHelper.startPage(pageNo, pageSize);
		return ispDomainMapper.selectByExample(example);
	}

	@Override
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public IspDomain createDomain(IspDomain record) throws Exception {
		IspServer ispServer = ispServerMapper.selectByPrimaryKey(record.getServerId());
		String[] domainSplit = record.getDomainName().split("\\.");
		String domainName = domainSplit[domainSplit.length - 2] + "." + domainSplit[domainSplit.length - 1];
		String subDomainName = domainSplit.length > 2 ? record.getDomainName().substring(0, record.getDomainName().lastIndexOf(domainName) - 1) : "";
		String ownDomainName = StringUtils.isEmpty(subDomainName) ? "@" : subDomainName;
		String allDomainName = StringUtils.isEmpty(subDomainName) ? "*" : "*." + subDomainName;

		if(Constants.DNS_TYPE_TXY.equals(record.getDnsType())){
			Map<String, Object> data = (Map<String, Object>)TxCloudUtil.dnsSend(domainName, "", "", record.getDnsKey(), record.getDnsToken(), "RecordList").get("data");
			List<Map<String, Object>> records = (List<Map<String, Object>>)data.get("records");

			if(records != null && records.size() > 0){
				for(Map<String, Object> o : records){
					if(ownDomainName.equals(o.get("name")) || allDomainName.equals(o.get("name"))){
						TxCloudUtil.dnsSend(domainName, String.valueOf(o.get("id")), "", record.getDnsKey(), record.getDnsToken(), "RecordDelete");
					}
				}
			}
			TxCloudUtil.dnsSend(domainName, allDomainName, ispServer.getIp(), record.getDnsKey(), record.getDnsToken(), "RecordCreate");
			TxCloudUtil.dnsSend(domainName, ownDomainName, ispServer.getIp(), record.getDnsKey(), record.getDnsToken(), "RecordCreate");
		} else if(Constants.DNS_TYPE_ALY.equals(record.getDnsType())){
			Map<String, Object> data = AliCloudUtil.dnsSend(domainName, "", "", record.getDnsKey(), record.getDnsToken(), "RecordList");
			List<Map<String, Object>> records = (List<Map<String, Object>>) Objects.requireNonNull(data).get("records");

			if(records != null && records.size() > 0){
				for(Map<String, Object> o : records){
					if(ownDomainName.equals(o.get("rR")) || allDomainName.equals(o.get("rR"))){
						AliCloudUtil.dnsSend(domainName, String.valueOf(o.get("recordId")), "", record.getDnsKey(), record.getDnsToken(), "RecordDelete");
					}
				}
			}
			AliCloudUtil.dnsSend(domainName, allDomainName, ispServer.getIp(), record.getDnsKey(), record.getDnsToken(), "RecordCreate");
			AliCloudUtil.dnsSend(domainName, ownDomainName, ispServer.getIp(), record.getDnsKey(), record.getDnsToken(), "RecordCreate");
		}

		ispDomainMapper.insertSelective(record);
		return record;
	}
	@Override
	public IspDomain findDomain(Integer id) {
		return ispDomainMapper.selectByPrimaryKey(id);
	}

	@Override
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public IspDomain updateDomain(IspDomain record) throws Exception {
		if(StringUtils.isNotEmpty(record.getDnsKey())){
			IspServer ispServer = ispServerMapper.selectByPrimaryKey(record.getServerId());
			String[] domainSplit = "\\.".split(record.getDomainName());
			String domainName = domainSplit[domainSplit.length - 2] + "." + domainSplit[domainSplit.length - 1];
			String subDomainName = domainSplit.length > 2 ? record.getDomainName().substring(0, record.getDomainName().lastIndexOf(domainName) - 1) : "";
			String ownDomainName = StringUtils.isEmpty(subDomainName) ? "@" : subDomainName;
			String allDomainName = StringUtils.isEmpty(subDomainName) ? "*" : "*." + subDomainName;

			if(Constants.DNS_TYPE_TXY.equals(record.getDnsType())){
				Map<String, Object> data = (Map<String, Object>)TxCloudUtil.dnsSend(domainName, "", "", record.getDnsKey(), record.getDnsToken(), "RecordList").get("data");
				List<Map<String, Object>> records = (List<Map<String, Object>>)data.get("records");

				if(records != null && records.size() > 0){
					for(Map<String, Object> o : records){
						if(ownDomainName.equals(o.get("name")) || allDomainName.equals(o.get("name"))){
							TxCloudUtil.dnsSend(domainName, String.valueOf(o.get("id")), "", record.getDnsKey(), record.getDnsToken(), "RecordDelete");
						}
					}
				}
				TxCloudUtil.dnsSend(domainName, allDomainName, ispServer.getIp(), record.getDnsKey(), record.getDnsToken(), "RecordCreate");
				TxCloudUtil.dnsSend(domainName, ownDomainName, ispServer.getIp(), record.getDnsKey(), record.getDnsToken(), "RecordCreate");
			} else if(Constants.DNS_TYPE_ALY.equals(record.getDnsType())){
				Map<String, Object> data = AliCloudUtil.dnsSend(domainName, "", "", record.getDnsKey(), record.getDnsToken(), "RecordList");
				List<Map<String, Object>> records = (List<Map<String, Object>>) Objects.requireNonNull(data).get("records");

				if(records != null && records.size() > 0){
					for(Map<String, Object> o : records){
						if(ownDomainName.equals(o.get("rR")) || allDomainName.equals(o.get("rR"))){
							AliCloudUtil.dnsSend(domainName, String.valueOf(o.get("recordId")), "", record.getDnsKey(), record.getDnsToken(), "RecordDelete");
						}
					}
				}
				AliCloudUtil.dnsSend(domainName, allDomainName, ispServer.getIp(), record.getDnsKey(), record.getDnsToken(), "RecordCreate");
				AliCloudUtil.dnsSend(domainName, ownDomainName, ispServer.getIp(), record.getDnsKey(), record.getDnsToken(), "RecordCreate");
			}
		}

		ispDomainMapper.updateByPrimaryKeySelective(record);
		return record;
	}
	@Override
	public void deleteDomain(Integer id) {
		ispDomainMapper.deleteByPrimaryKey(id);
	}

	@Override
	@Async
	public void createSSL(Integer id, Integer userId) {
		IspDomain ispDomain = ispDomainMapper.selectByPrimaryKey(id);
		IspServer ispServer = ispServerMapper.selectByPrimaryKey(ispDomain.getServerId());
		String cmd = "curl -s -S -L https://install.wydiy.com/shell/https_install.sh | bash -s '"+ispDomain.getDomainName()+"' "+ispDomain.getDnsType() + " " + ispDomain.getDnsKey() + " " +ispDomain.getDnsToken();
		Connection connection = SshUtil.login(ispServer.getIp(), ispServer.getSshUser(), ispServer.getSshPassword());
		try{
			if (connection != null) {
				Session session = connection.openSession();// 打开一个会话
				session.execCommand(cmd);// 执行命令
				InputStream stdout = new StreamGobbler(session.getStdout());
				BufferedReader br = new BufferedReader(new InputStreamReader(stdout, StandardCharsets.UTF_8));
				String line;
				while ((line = br.readLine()) != null) {
					WebSocketServer.sendMessage(line, userId);
					log.info(line);
				}
				WebSocketServer.sendMessage("ssl_create_completed", userId);
				br.close();
				session.close();
				connection.close();

				IspDomain upDomain = new IspDomain();
				upDomain.setNo(id);
				upDomain.setSslFlag(Constants.COMMON_ENABLE);
				ispDomainMapper.updateByPrimaryKeySelective(upDomain);

				WebSocketServer.sendMessage("成功安装SSL！", userId);
			}
		} catch (Exception e) {
			WebSocketServer.sendMessage(e.toString(), userId);
			log.error("执行命令失败,链接conn:" + connection + ",执行的命令：" + cmd, e);
		}
	}

	@Override
	@Async
	public void createSQL(IspDomain record, Integer userId) {
		IspDomain ispDomain = ispDomainMapper.selectByPrimaryKey(record.getNo());
		IspServer ispServer = ispServerMapper.selectByPrimaryKey(ispDomain.getServerId());
		String cmd = "curl -s -S -L https://install.wydiy.com/shell/mysql_createdb.sh | bash -s '"+ispDomain.getDomainName().replaceAll("\\.", "_")+"' "+record.getSqlPasswd();
		Connection connection = SshUtil.login(ispServer.getIp(), ispServer.getSshUser(), ispServer.getSshPassword());
		try{
			if (connection != null) {
				Session session = connection.openSession();// 打开一个会话
				session.execCommand(cmd);// 执行命令
				InputStream stdout = new StreamGobbler(session.getStdout());
				BufferedReader br = new BufferedReader(new InputStreamReader(stdout, StandardCharsets.UTF_8));
				String line;
				while ((line = br.readLine()) != null) {
					WebSocketServer.sendMessage(line, userId);
					log.info(line);
				}
				WebSocketServer.sendMessage("sql_create_completed", userId);
				br.close();
				session.close();
				connection.close();

				IspDomain upDomain = new IspDomain();
				upDomain.setNo(record.getNo());
				upDomain.setSqlService(Constants.COMMON_ENABLE);
				upDomain.setSqlQuota(record.getSqlQuota());
				upDomain.setSqlPasswd(record.getSqlPasswd());
				ispDomainMapper.updateByPrimaryKeySelective(upDomain);

				WebSocketServer.sendMessage("成功安装SQL！", userId);
			}
		} catch (Exception e) {
			WebSocketServer.sendMessage(e.toString(), userId);
			log.error("执行命令失败,链接conn:" + connection + ",执行的命令：" + cmd, e);
		}
	}

	@Override
	@Async
	public void createCMS(Integer id, Integer userId) throws Exception {
		IspDomain ispDomain = ispDomainMapper.selectByPrimaryKey(id);
		if(ispDomain.getSslFlag().equals(0)){
			throw new EsRuntimeException(ErrorConstants.SSL_NOT_INSTALL);

		}
		if(ispDomain.getSqlService().equals(0)){
			throw new EsRuntimeException(ErrorConstants.MYSQL_NOT_INSTALL);
		}
		IspServer ispServer = ispServerMapper.selectByPrimaryKey(ispDomain.getServerId());
		String cmd = "curl -s -S -L https://install.wydiy.com/shell/cms_install.sh | bash -s '"+ispDomain.getDomainName()+"' "+ ispDomain.getSqlPasswd();
		Connection connection = SshUtil.login(ispServer.getIp(), ispServer.getSshUser(), ispServer.getSshPassword());
		try{
			if (connection != null) {
				Session session = connection.openSession();// 打开一个会话
				session.execCommand(cmd);// 执行命令
				InputStream stdout = new StreamGobbler(session.getStdout());
				BufferedReader br = new BufferedReader(new InputStreamReader(stdout, StandardCharsets.UTF_8));
				String line;
				while ((line = br.readLine()) != null) {
					WebSocketServer.sendMessage(line, userId);
					log.info(line);
				}
				WebSocketServer.sendMessage("cms_create_completed", userId);
				br.close();
				session.close();
				connection.close();

				IspDomain upDomain = new IspDomain();
				upDomain.setNo(id);
				upDomain.setCmsFlag(Constants.COMMON_ENABLE);
				ispDomainMapper.updateByPrimaryKeySelective(upDomain);

				WebSocketServer.sendMessage("成功安装CMS！", userId);
			}
		} catch (Exception e) {
			WebSocketServer.sendMessage(e.toString(), userId);
			log.error("执行命令失败,链接conn:" + connection + ",执行的命令：" + cmd, e);
		}
	}

	@Override
	public Page<IspSmbVDir> listSmbVDir(IspSmbVDir example, int pageNo, int pageSize) {
		PageHelper.startPage(pageNo, pageSize);
		return ispSmbVDirMapper.selectByExample(example);
	}

	@Override
	public void createSmbVDir(IspSmbVDir record) throws Exception {
		String available = "yes".equals(record.getAvailable()) ? "yes" : "no";
		String writable = "yes".equals(record.getWritable()) ? "yes" : "no";
		String browseable = "yes".equals(record.getBrowseable()) ? "yes" : "no";
		String printable = "yes".equals(record.getPrintable()) ? "yes" : "no";
		this.execCommand(record.getServerId(), "/home/isp/bin/samba.sh vdiradd " + record.getUser() + " " + record.getName() + " " + record.getPath() + " " + available + " " + writable + " " + browseable + " " + printable);

		long now = DateUtil.genTime(new Date());
		record.setCreateTime(now);
		record.setUpdateTime(now);
		ispSmbVDirMapper.insertSelective(record);
	}

	@Override
	public IspSmbVDir findSmbVDir(Integer id) {
		return ispSmbVDirMapper.selectByPrimaryKey(id);
	}

	@Override
	public IspSmbVDir updateSmbVDir(IspSmbVDir record) throws Exception {
		IspSmbVDir ispSmbVDir = ispSmbVDirMapper.selectByPrimaryKey(record.getId());
		this.execCommand(ispSmbVDir.getServerId(), "/home/isp/bin/samba.sh vdirdel " + ispSmbVDir.getUser() + " " + ispSmbVDir.getName());
		String available = "yes".equals(record.getAvailable()) ? "yes" : (record.getAvailable() == null ? ispSmbVDir.getAvailable() : "no");
		String writable = "yes".equals(record.getWritable()) ? "yes" : (record.getWritable() == null ? ispSmbVDir.getWritable() : "no");
		String browseable = "yes".equals(record.getBrowseable()) ? "yes" : (record.getBrowseable() == null ? ispSmbVDir.getBrowseable() : "no");
		String printable = "yes".equals(record.getPrintable()) ? "yes" : (record.getPrintable() == null ? ispSmbVDir.getPrintable() : "no");
		String path = record.getPath() == null ? ispSmbVDir.getPath() : record.getPath();
		this.execCommand(ispSmbVDir.getServerId(), "/home/isp/bin/samba.sh vdiradd " + ispSmbVDir.getUser() + " " + ispSmbVDir.getName() + " " + path + " " + available + " " + writable + " " + browseable + " " + printable);

		long now = DateUtil.genTime(new Date());
		record.setUpdateTime(now);
		ispSmbVDirMapper.updateByPrimaryKeySelective(record);
		return record;
	}

	@Override
	public void deleteSmbVDir(Integer id) throws Exception {
		IspSmbVDir ispSmbVDir = ispSmbVDirMapper.selectByPrimaryKey(id);
		this.execCommand(ispSmbVDir.getServerId(), "/home/isp/bin/samba.sh vdirdel " + ispSmbVDir.getUser() + " " + ispSmbVDir.getName());
		ispSmbVDirMapper.deleteByPrimaryKey(id);
	}
}
