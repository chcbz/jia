package cn.jia.isp.service.impl;

import ch.ethz.ssh2.Connection;
import ch.ethz.ssh2.Session;
import ch.ethz.ssh2.StreamGobbler;
import cn.jia.core.exception.EsRuntimeException;
import cn.jia.core.util.DateUtil;
import cn.jia.core.util.SshUtil;
import cn.jia.isp.common.ErrorConstants;
import cn.jia.isp.dao.IspDomainMapper;
import cn.jia.isp.dao.IspServerMapper;
import cn.jia.isp.entity.IspDomain;
import cn.jia.isp.entity.IspServer;
import cn.jia.isp.service.IspService;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Service
@Slf4j
public class IspServiceImpl implements IspService {
	
	@Autowired
	private IspServerMapper carServerMapper;
	@Autowired
	private IspDomainMapper ispDomainMapper;

	@Override
	public Page<IspServer> listServer(IspServer example, int pageNo, int pageSize) {
		PageHelper.startPage(pageNo, pageSize);
		return carServerMapper.selectByExample(example);
	}
	@Override
	@Async
	public void createServer(IspServer record) {
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
					log.info(line);
				}
				br.close();
				connection.close();
				session.close();

				long now = DateUtil.genTime(new Date());
				record.setCreateTime(now);
				record.setUpdateTime(now);
				carServerMapper.insertSelective(record);

			}
		} catch (Exception e) {
			log.error("执行命令失败,链接conn:" + connection + ",执行的命令：" + cmd, e);
		}
	}
	@Override
	public IspServer findServer(Integer id) {
		return carServerMapper.selectByPrimaryKey(id);
	}
	@Override
	public IspServer updateServer(IspServer record) throws Exception {
		Connection connection = SshUtil.login(record.getIp(), record.getSshUser(), record.getSshPassword());
		if(connection == null){
			throw new EsRuntimeException(ErrorConstants.ISP_CONN_FAILD);
		}
		connection.close();

		long now = DateUtil.genTime(new Date());
		record.setUpdateTime(now);
		carServerMapper.updateByPrimaryKeySelective(record);
		return record;
	}
	@Override
	public void deleteServer(Integer id) {
		carServerMapper.deleteByPrimaryKey(id);
	}

	@Override
	public Page<IspDomain> listDomain(IspDomain example, int pageNo, int pageSize) {
		PageHelper.startPage(pageNo, pageSize);
		return ispDomainMapper.selectByExample(example);
	}
}
