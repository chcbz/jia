package cn.jia.core.util;

import ch.ethz.ssh2.Connection;
import ch.ethz.ssh2.Session;
import ch.ethz.ssh2.StreamGobbler;

import java.io.*;

public class SshUtil {

    public static Connection login(String ip, String username, String password) {
        boolean flag;
        Connection connection = null;
        try {
            connection = new Connection(ip);
            connection.connect();// 连接
            flag = connection.authenticateWithPassword(username, password);// 认证
            if (flag) {
                System.out.println("================登录成功==================");
                return connection;
            }
        } catch (IOException e) {
            System.out.println("=========登录失败=========" + e);
            connection.close();
        }
        return connection;
    }

    /**
     * 远程执行shll脚本或者命令
     *
     * @param cmd
     *            即将执行的命令
     * @return 命令执行完后返回的结果值
     */
    public static String execmd(Connection connection, String cmd) throws Exception {
        String result = "";
        if (connection != null) {
            Session session = connection.openSession();// 打开一个会话
            session.execCommand(cmd);// 执行命令
            result = processStdout(session.getStdout(), "UTF-8");
            System.out.println(result);
            // 如果为得到标准输出为空，说明脚本执行出错了
            /*if (StringUtils.isBlank(result)) {
                System.out.println("得到标准输出为空,链接conn:" + connection + ",执行的命令：" + cmd);
                result = processStdout(session.getStderr(), DEFAULTCHART);
            } else {
                System.out.println("执行命令成功,链接conn:" + connection + ",执行的命令：" + cmd);
            }*/
            connection.close();
            session.close();
        }
        return result;
    }

    /**
     * 解析脚本执行返回的结果集
     *
     * @param in
     *            输入流对象
     * @param charset
     *            编码
     * @return 以纯文本的格式返回
     */
    private static String processStdout(InputStream in, String charset) {
        InputStream stdout = new StreamGobbler(in);
        StringBuilder buffer = new StringBuilder();
        ;
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(stdout, charset));
            String line;
            while ((line = br.readLine()) != null) {
                buffer.append(line).append("\n");
                System.out.println(line);
            }
            br.close();
        } catch (IOException e) {
            System.out.println("解析脚本出错：" + e.getMessage());
            e.printStackTrace();
        }
        return buffer.toString();
    }
}
