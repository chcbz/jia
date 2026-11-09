package cn.jia.agent.skill;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.*;
import java.net.*;
import java.sql.*;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/** Shared lifecycle of this task's private mysqld only. Never connects to a configured server. */
public abstract class SkillManagementMySqlFixture {
    @TempDir static Path scratch;
    private static Process mysql;
    private static Path socketDirectory;
    private static String baseUrl;
    protected static JdbcTemplate admin;
    @BeforeAll static void startPrivateMysql() throws Exception {
        String configured=System.getenv("CYF_REVIEW_MYSQLD");
        assertNotNull(configured,"Required CYF_REVIEW_MYSQLD: this suite must FAIL, never skip, if its owned fixture is unavailable");
        Path executable=Path.of(configured).toRealPath();assertTrue(Files.isExecutable(executable));
        Path data=Files.createDirectory(scratch.resolve("data")),log=scratch.resolve("mysql.log");
        String user=System.getProperty("user.name");
        var init=new ProcessBuilder(executable.toString(),"--no-defaults","--initialize-insecure","--user="+user,
                "--datadir="+data,"--basedir="+executable.getParent().getParent()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {assertEquals(0,init.waitFor(),()->readLog(log));}finally {stop(init);}
        int port;
        do {try(var listener=new ServerSocket(0,0,InetAddress.getByName("127.0.0.1"))){port=listener.getLocalPort();}}
        while(Set.of(3306,10018,33060).contains(port));
        // UNIX socket names have a platform length limit independent of datadir.
        // The runner may supply a short disk root; keep data and logs in the batch.
        String socketRoot=System.getenv("CYF_REVIEW_MYSQL_SOCKET_DIR");
        Path socketBase=socketRoot==null?scratch:Path.of(socketRoot).toRealPath();
        String filesystem=Files.getFileStore(socketBase).type();
        assertFalse(Set.of("tmpfs","ramfs").contains(filesystem),"MySQL fixture must use ordinary disk");
        socketDirectory=Files.createTempDirectory(socketBase,"sm-");
        Path socket=socketDirectory.resolve("s");
        assertTrue(socket.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=107,
                "Set CYF_REVIEW_MYSQL_SOCKET_DIR to a shorter ordinary-disk directory");
        mysql=new ProcessBuilder(executable.toString(),"--no-defaults","--user="+user,"--datadir="+data,
            "--basedir="+executable.getParent().getParent(),"--socket="+socket,"--pid-file="+scratch.resolve("mysql.pid"),
            "--port="+port,"--bind-address=127.0.0.1","--mysqlx=OFF","--skip-log-bin","--innodb-buffer-pool-size=67108864")
            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start();
        baseUrl="jdbc:mysql://127.0.0.1:"+port+"/";
        Class.forName("com.mysql.cj.jdbc.Driver");
        while(true) {
            assertTrue(mysql.isAlive(),()->readLog(log));
            try(var connection=DriverManager.getConnection(url("mysql"),"root","")) {assertTrue(connection.isValid(1));break;}
            catch(SQLException starting){Thread.sleep(100);}
        }
        admin=new JdbcTemplate(source("mysql"));
        System.out.println("CYF_SM_C1_MYSQL owned=true port="+port+" version="+admin.queryForObject("SELECT VERSION()",String.class));
    }
    @AfterAll static void stopPrivateMysql() throws Exception {
        stop(mysql);mysql=null;admin=null;
        if(socketDirectory!=null) {
            Files.deleteIfExists(socketDirectory.resolve("s"));
            Files.deleteIfExists(socketDirectory.resolve("s.lock"));
            Files.deleteIfExists(socketDirectory);
            socketDirectory=null;
        }
    }
    private static void stop(Process process) throws InterruptedException {if(process!=null&&process.isAlive()){process.destroy();process.waitFor();}}
    private static String readLog(Path log) {try{return Files.readString(log);}catch(Exception e){return e.getClass().getSimpleName();}}
    private static String url(String database){return baseUrl+database+"?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";}
    protected static DriverManagerDataSource source(String database){return new DriverManagerDataSource(url(database),"root","");}

    protected static String isolatedUrl(){return url("");}

}
