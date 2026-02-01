package cn.jia.isp.service.impl;

import ch.ethz.ssh2.Connection;
import ch.ethz.ssh2.Session;
import ch.ethz.ssh2.StreamGobbler;
import cn.jia.core.exception.EsRuntimeException;
import cn.jia.core.util.DateUtil;
import cn.jia.core.util.SshUtil;
import cn.jia.isp.common.IspErrorConstants;
import cn.jia.isp.dao.IspDomainDao;
import cn.jia.isp.dao.IspServerDao;
import cn.jia.isp.entity.IspDomainEntity;
import cn.jia.isp.entity.IspServerEntity;
import cn.jia.isp.service.IspService;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

@Named
@Slf4j
public class IspServiceImpl implements IspService {

    @Inject
    private IspServerDao ispServerDao;
    @Inject
    private IspDomainDao ispDomainDao;

    @Override
    public PageInfo<IspServerEntity> listServer(IspServerEntity example, int pageNum, int pageSize, String orderBy) {
        PageHelper.startPage(pageNum, pageSize, orderBy);
        return PageInfo.of(ispServerDao.selectByEntity(example));
    }

    @Override
    @Async
    public void createServer(IspServerEntity record) {
        String cmd = "curl -s -S -L https://install.wydiy.com/shell/console_install.sh | bash -s " +
				record.getConsoleToken() + " " + record.getConsolePort();
        Connection connection = SshUtil.login(record.getIp(), record.getSshUser(), record.getSshPassword());
        try {
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

                long now = DateUtil.nowTime();
                record.setCreateTime(now);
                record.setUpdateTime(now);
                ispServerDao.insert(record);

            }
        } catch (Exception e) {
            log.error("执行命令失败,链接conn:" + connection + ",执行的命令：" + cmd, e);
        }
    }

    @Override
    public IspServerEntity findServer(Long id) {
        return ispServerDao.selectById(id);
    }

    @Override
    public IspServerEntity updateServer(IspServerEntity record) throws Exception {
        Connection connection = SshUtil.login(record.getIp(), record.getSshUser(), record.getSshPassword());
        if (connection == null) {
            throw new EsRuntimeException(IspErrorConstants.ISP_CONN_FAILD);
        }
        connection.close();

        long now = DateUtil.nowTime();
        record.setUpdateTime(now);
        ispServerDao.updateById(record);
        return record;
    }

    @Override
    public void deleteServer(Long id) {
        ispServerDao.deleteById(id);
    }

    @Override
    public PageInfo<IspDomainEntity> listDomain(IspDomainEntity example, int pageNum, int pageSize, String orderBy) {
        PageHelper.startPage(pageNum, pageSize, orderBy);
        return PageInfo.of(ispDomainDao.selectByEntity(example));
    }
}
