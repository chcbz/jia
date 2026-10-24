package cn.jia.agent.config;

import cn.jia.agent.dao.impl.AgentRuntimeV2ExecutionStoreImpl;
import cn.jia.agent.mapper.AgentRuntimeV2ExecutionMapper;
import cn.jia.agent.mapper.AgentRuntimeV2EffectMapper;
import cn.jia.agent.service.impl.AgentRuntimeV2ExecutionEvidenceValidator;
import cn.jia.agent.service.impl.AgentRuntimeV2HistoricalSettlementEvidenceValidator;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.util.*;
import java.util.regex.Pattern;

/** Private checkpoint ONLY. Never installed in main output or application startup.
 * The native runtime account does DML only; the explicit separate bootstrap entry
 * point installs source-backed native DDL in an empty, fixed private schema.
 * Compact authority parents below are trusted TEST scaffolds, NOT proof of the
 * complete Core/OAuth/identity production catalog or authenticated admission.
 */
public final class AgentRuntimeV2PrivateMysqlFixture {
    static final Path ROOT=Path.of("/mnt/Workspace/cyf-r4-private-mysql-repair-SfroSG/private-fixture");
    static final Path CONFIG=ROOT.resolve("private-mysql.properties");
    static final String SCHEMA="cyf_r4_private_20261005_gsrybe";
    static final String PORT="13316";
    static final String RUNTIME_USER="cyf_r4_runtime", ADMIN_USER="cyf_r4_bootstrap";
    static final String URL="jdbc:mysql://127.0.0.1:"+PORT+"/"+SCHEMA+"?sslMode=DISABLED&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
    private static final Set<String> KEYS=Set.of("checkpoint","schema","port","serverUuid","runtimeUser","runtimePassword","adminUser","adminPassword");
    private record SourcePin(String relative,String sha256){}
    private static final Path API_SOURCE=Path.of("/home/chc/wsps/cyf-worktrees/multi-region-tenancy/api");
    private static final Map<String,SourcePin> SOURCE_PINS=Map.ofEntries(
        Map.entry("db/single-tenant-task-owner-ddl.sql",new SourcePin("agent/jia-agent-mapper/src/main/resources/db/single-tenant-task-owner-ddl.sql","a6a065babe303e2771bef3f45dba4b915ebb0d7a49a08b62df395d8b0c6964bf")),
        Map.entry("db/agent-command-transport-schema.sql",new SourcePin("agent/jia-agent-mapper/src/main/resources/db/agent-command-transport-schema.sql","bde156d1df67eec5e5a6c208f95e69cb1fdec8d5938d34f99dafa025940c7aa5")),
        Map.entry("db/agent-outbox-relay-v2-manual-20260925.sql",new SourcePin("agent/jia-agent-mapper/src/main/resources/db/agent-outbox-relay-v2-manual-20260925.sql","1865ed85d5063050562cb3c1b9010bc8b15e33bb4cc65e753a944c8cb2e43bb0")),
        Map.entry("db/agent-command-consumer-v2-manual-20260925.sql",new SourcePin("agent/jia-agent-mapper/src/main/resources/db/agent-command-consumer-v2-manual-20260925.sql","dab51ed8394a539da1870a31b794fffae38e5f853a668d338d8f65ef5067fa91")),
        Map.entry("db/agent-runtime-v2-persistence-manual-20260930.sql",new SourcePin("agent/jia-agent-mapper/src/main/resources/db/agent-runtime-v2-persistence-manual-20260930.sql","c33adc202e00d7c1c9479db9417d13727d740e861799db032d260102d6d8c2a0")),
        Map.entry("db/agent-runtime-v2-registration-manual-20260930.sql",new SourcePin("agent/jia-agent-mapper/src/main/resources/db/agent-runtime-v2-registration-manual-20260930.sql","d5835d2e357372997efc7ed9ca2402683b31adeefc9d6fae2d7a36e0ca8601c1")),
        Map.entry("db/agent-runtime-v2-registration-activation-manual-20260930.sql",new SourcePin("agent/jia-agent-mapper/src/main/resources/db/agent-runtime-v2-registration-activation-manual-20260930.sql","2d8cba08edaaa99202b0f317c05b2292e15c1335e92aec8621dfd142907d7e71")),
        Map.entry("db/agent-runtime-v2-challenge-decision-manual-20261001.sql",new SourcePin("agent/jia-agent-mapper/src/main/resources/db/agent-runtime-v2-challenge-decision-manual-20261001.sql","db10339f2703ee4dd74cff0f3be29402c794518f3ee4b7f514ef20498986a889")),
        Map.entry("db/agent-runtime-v2-dispatch-manual-20261001.sql",new SourcePin("agent/jia-agent-mapper/src/main/resources/db/agent-runtime-v2-dispatch-manual-20261001.sql","6d23eeb19e03e6348b7596a70aed99190ab61e15791568adef7d584eea9f3677")),
        Map.entry("db/agent-runtime-v2-execution-manual-20261001.sql",new SourcePin("agent/jia-agent-mapper/src/main/resources/db/agent-runtime-v2-execution-manual-20261001.sql","5ff81b668a5b08cb854828f6bbfe73ce156b82746b431066a8c90900f6bddc1f")),
        Map.entry("db/agent-runtime-v2-effects-manual-20261003.sql",new SourcePin("agent/jia-agent-service/src/main/resources/db/agent-runtime-v2-effects-manual-20261003.sql","bd4c45fc48133f22d95c6b8bc019f7e3fa2cbaccfd2172ccfe957e8105534530")),
        Map.entry("db/agent-personal-workspace-v1.sql",new SourcePin("agent/jia-agent-mapper/src/main/resources/db/agent-personal-workspace-v1.sql","07ff16f896378b1c76da6fe0b2e2e28e32b2bd624ec41a36cde3f8e73666baae")),
        Map.entry("db/agent-personal-workspace-v1_11-executions.sql",new SourcePin("agent/jia-agent-mapper/src/main/resources/db/agent-personal-workspace-v1_11-executions.sql","43dc0a2f4089830c2460427becb003190b5e8484951436d67f56e4e5417f6e26")),
        Map.entry("db/agent-personal-workspace-v1_13-task-execution.sql",new SourcePin("agent/jia-agent-service/src/main/resources/db/agent-personal-workspace-v1_13-task-execution.sql","3b5eb2706a2b5a3e66c73ea875b955eb3b9e97812f2db47afd0088fbf3f53267")),
        Map.entry("db/agent-personal-workspace-v1_13-task-publication.sql",new SourcePin("agent/jia-agent-service/src/main/resources/db/agent-personal-workspace-v1_13-task-publication.sql","0d13d5b4deebf2950ca1460631f9ed71c475023bef382396d96f7d593c78d960")),
        Map.entry("db/agent-task-formal-delivery-r2.sql",new SourcePin("agent/jia-agent-mapper/src/main/resources/db/agent-task-formal-delivery-r2.sql","746c84478845b09d671a979a19677ac5d00a6ca240a7e57bf26ba5a7de75f782")),
        Map.entry("db/task-event-schema.sql",new SourcePin("agent/jia-agent-mapper/src/main/resources/db/task-event-schema.sql","4676ef0a3e7af7ec0d3f5c1f40cd73a2765e27ce55c05e347068059ede3b131d")),
        Map.entry("db/runtime-v2-drain-authority-manual-20261001.sql",new SourcePin("oauth/jia-oauth-mapper/src/main/resources/db/runtime-v2-drain-authority-manual-20261001.sql","8c765eacca106449dbbf124abd3437b053f776ec8b296c494b87bad19948612b")),
        Map.entry("db/schema.sql",new SourcePin("agent/jia-agent-mapper/src/main/resources/db/schema.sql","b71aede84364dd8382a8e36552dbdce33aa042b88e0551cc168954676adff474")));
    private final Map<String,String> config;
    private AgentRuntimeV2PrivateMysqlFixture(Map<String,String> config){this.config=Map.copyOf(config);}

    // Ordered, UNMODIFIED production migrations. Pin each classpath resource at
    // the source stop/build handoff; never accept SQL supplied by config or env.
    static final List<String> RUNTIME_RESOURCES=List.of(
        "db/agent-command-transport-schema.sql",
        "db/agent-outbox-relay-v2-manual-20260925.sql",
        "db/agent-command-consumer-v2-manual-20260925.sql",
        "db/agent-runtime-v2-persistence-manual-20260930.sql",
        "db/agent-runtime-v2-registration-manual-20260930.sql",
        "db/agent-runtime-v2-registration-activation-manual-20260930.sql",
        "db/agent-runtime-v2-challenge-decision-manual-20261001.sql",
        "db/agent-runtime-v2-dispatch-manual-20261001.sql",
        "db/agent-runtime-v2-execution-manual-20261001.sql",
        "db/agent-runtime-v2-effects-manual-20261003.sql");
    static final List<String> FILE_RESOURCES=List.of(
        "db/agent-personal-workspace-v1.sql",
        "db/agent-personal-workspace-v1_11-executions.sql",
        "db/agent-personal-workspace-v1_13-task-execution.sql",
        "db/agent-personal-workspace-v1_13-task-publication.sql",
        "db/agent-task-formal-delivery-r2.sql",
        "db/task-event-schema.sql",
        "db/single-tenant-task-owner-ddl.sql");

    static void checkEnvironment(Map<String,String> environment, List<String> jvmArgs) {
        for(String key:environment.keySet())if(Set.of("NODE_OPTIONS","JAVA_TOOL_OPTIONS","JDK_JAVA_OPTIONS","_JAVA_OPTIONS","LD_PRELOAD","LD_LIBRARY_PATH","LD_AUDIT").contains(key)||key.startsWith("FAKETIME")||key.startsWith("LIBFAKETIME")||key.startsWith("CYF_R4_PRIVATE_MYSQL")||key.startsWith("CYF_R4_FILE_DIAGNOSTIC"))throw rejected("environment override");
        if(!jvmArgs.equals(List.of("-Djava.io.tmpdir="+ROOT.resolve("native-java-tmp"))))throw rejected("JVM override");
    }
    static Map<String,String> parseConfiguration(String bytes) {
        Map<String,String> parsed=new LinkedHashMap<>();
        for(String line:bytes.split("\\n",-1)) {
            if(line.isEmpty())continue;
            int equals=line.indexOf('=');
            if(equals<1||line.indexOf('=',equals+1)>=0)throw rejected("configuration format");
            String key=line.substring(0,equals),value=line.substring(equals+1);
            if(!KEYS.contains(key)||parsed.putIfAbsent(key,value)!=null)throw rejected("configuration keys");
        }
        if(!parsed.keySet().equals(KEYS)||!"private-r4-stage-b-v1".equals(parsed.get("checkpoint"))||!SCHEMA.equals(parsed.get("schema"))||!PORT.equals(parsed.get("port"))||!RUNTIME_USER.equals(parsed.get("runtimeUser"))||!ADMIN_USER.equals(parsed.get("adminUser")))throw rejected("configuration identity");
        for(String key:List.of("runtimePassword","adminPassword"))if(!parsed.get(key).matches("[0-9a-f]{64}"))throw rejected("dedicated credential format");
        if(parsed.get("runtimePassword").equals(parsed.get("adminPassword"))||!parsed.get("serverUuid").matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw rejected("role/server identity");
        return Map.copyOf(parsed);
    }
    static void checkConfigPath(Path path) {
        if(!CONFIG.equals(path))throw rejected("configuration locator");
    }
    public static AgentRuntimeV2PrivateMysqlFixture load() throws Exception {
        checkEnvironment(System.getenv(),java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments());
        checkConfigPath(CONFIG);
        String bytes=readPinnedConfiguration(ROOT,new DirectoryPin(2068,341120,1001,0700),new DirectoryPin(2068,347914,1001,0700),1001,phase->{}).orElseThrow(()->rejected("configuration absent"));
        return new AgentRuntimeV2PrivateMysqlFixture(parseConfiguration(bytes));
    }

    /** Linux/aarch64 test-only primitive using the EXISTING JNA dependency.
     * openat walks from held ancestors; statx(AT_EMPTY_PATH) describes the real
     * opened FD, not another pathname. inotify watches held directory/file
     * objects throughout resolution/read: even short ABA with identical coarse
     * timestamps is rejected, as are queue overflow and unsupported primitives.
     * This protects lookup/read consistency, not a malicious same-UID process
     * able to ptrace this JVM or replace executable/classpath bytes.
     */
    interface ConfigLibC extends com.sun.jna.Library {
        int openat(int directory,String name,int flags);
        int close(int fd);
        int statx(int directory,String name,int flags,int mask,com.sun.jna.Pointer result);
        long read(int fd,byte[] bytes,long size);
        int inotify_init1(int flags);
        int inotify_add_watch(int fd,String path,int mask);
    }
    record DirectoryPin(long dev,long inode,int uid,int mode) {}
    private record OpenedStat(long dev,long inode,int uid,int gid,int mode,long size,long ctimeSeconds,int ctimeNanos,long mtimeSeconds,int mtimeNanos) {}
    private static final class LookupFailure extends SecurityException {
        final int errno;
        LookupFailure(int errno){super("private native checkpoint rejected: held lookup failed");this.errno=errno;}
    }
    private static final class PinnedRead implements AutoCloseable {
        // Verified AArch64 Linux UAPI: asm/fcntl.h and linux/stat.h. Never guess
        // another platform's flags/ABI or fall back to pathname-only checks.
        static final int NOFOLLOW=0100000,DIRECTORY=040000,CLOEXEC=02000000,NONBLOCK=04000;
        static final int WATCH=0x00000002|0x00000004|0x00000040|0x00000080|0x00000100|0x00000200|0x00000400|0x00000800|0x00002000|0x00004000|0x00008000;
        final ConfigLibC libc;
        final int notification;
        final List<Integer> descriptors=new ArrayList<>();
        final List<OpenedStat> directoryStats=new ArrayList<>();
        final List<String> directoryNames=new ArrayList<>();
        PinnedRead() {
            if(!"Linux".equals(System.getProperty("os.name"))||!"aarch64".equals(System.getProperty("os.arch")))throw rejected("opened identity platform unsupported");
            try {libc=com.sun.jna.Native.load("c",ConfigLibC.class);notification=libc.inotify_init1(NONBLOCK|CLOEXEC);}
            catch(LinkageError|RuntimeException failure){throw rejected("opened identity primitive unavailable");}
            if(notification<0)throw rejected("directory lifetime primitive unavailable");
        }
        OpenedStat stat(int fd,String name) {
            try(var memory=new com.sun.jna.Memory(256)) {
                memory.clear();
                if(libc.statx(fd,name,name.isEmpty()?0x1000:0x100,0x7ff,memory)!=0)throw new LookupFailure(com.sun.jna.Native.getLastError());
                if((memory.getInt(0)&0x7ff)!=0x7ff)throw rejected("opened identity attributes unavailable");
                long major=Integer.toUnsignedLong(memory.getInt(136)),minor=Integer.toUnsignedLong(memory.getInt(140));
                long dev=((major&0xfffff000L)<<32)|((major&0xfffL)<<8)|((minor&0xffffff00L)<<12)|(minor&0xffL);
                return new OpenedStat(dev,memory.getLong(32),memory.getInt(20),memory.getInt(24),Short.toUnsignedInt(memory.getShort(28)),memory.getLong(40),memory.getLong(96),memory.getInt(104),memory.getLong(112),memory.getInt(120));
            } catch(LinkageError failure){throw rejected("opened identity stat unsupported");}
        }
        void watch(int fd) {
            if(libc.inotify_add_watch(notification,"/proc/self/fd/"+fd,WATCH)<0)throw rejected("held object lifetime watch unsupported");
        }
        void unchanged() {
            for(int i=0;i<directoryStats.size();i++) {
                if(!directoryStats.get(i).equals(stat(descriptors.get(i),"")))throw rejected("held directory changed");
                if(i>0&&!directoryStats.get(i).equals(stat(descriptors.get(i-1),directoryNames.get(i))))throw rejected("directory resolution changed");
            }
            byte[] events=new byte[4096];long count=libc.read(notification,events,events.length);
            if(count>=0||com.sun.jna.Native.getLastError()!=11)throw rejected("held object lifetime changed or unavailable");
        }
        int directory(int parent,String name,DirectoryPin required) {
            if(!descriptors.isEmpty())unchanged();
            int fd=libc.openat(parent,name,DIRECTORY|NOFOLLOW|CLOEXEC);
            if(fd<0)throw rejected("directory open rejected");
            descriptors.add(fd);watch(fd);OpenedStat value=stat(fd,"");
            if((value.mode()&0170000)!=0040000)throw rejected("opened directory kind");
            if(required!=null&&(value.dev()!=required.dev()||value.inode()!=required.inode()||value.uid()!=required.uid()||(value.mode()&07777)!=required.mode()))throw rejected("opened directory pin");
            directoryStats.add(value);directoryNames.add(name);unchanged();return fd;
        }
        @Override public void close() {
            for(int i=descriptors.size()-1;i>=0;i--)libc.close(descriptors.get(i));
            libc.close(notification);
        }
    }
    static Optional<String> readPinnedConfiguration(Path root,DirectoryPin parentPin,DirectoryPin rootPin,int fileUid,java.util.function.Consumer<String> phase) throws Exception {
        if(!root.isAbsolute()||!root.normalize().equals(root))throw rejected("reader root shape");
        try(var held=new PinnedRead()) {
            int fd=held.directory(-100,"/",null);Path current=Path.of("/");
            for(Path component:root) {
                current=current.resolve(component);DirectoryPin required=current.equals(root)?rootPin:current.equals(root.getParent())?parentPin:null;
                fd=held.directory(fd,component.toString(),required);
            }
            phase.accept("DIRECTORIES_PINNED");held.unchanged();
            OpenedStat named;
            try {named=held.stat(fd,"private-mysql.properties");}
            catch(LookupFailure failure) {
                // Only ENOENT at the actual held, pinned root means absence.
                // Dangling links have a statx object; EACCES/ENOTDIR never do.
                if(failure.errno!=2)throw failure;
                held.unchanged();phase.accept("CONFIG_ABSENT");held.unchanged();return Optional.empty();
            }
            if((named.mode()&0170000)!=0100000||(named.mode()&07777)!=0600||named.uid()!=fileUid||named.size()>4096)throw rejected("configuration opened ownership/kind/mode/size");
            phase.accept("CONFIG_SNAPSHOT");
            int configFd=held.libc.openat(fd,"private-mysql.properties",PinnedRead.NOFOLLOW|PinnedRead.CLOEXEC|PinnedRead.NONBLOCK);
            if(configFd<0)throw rejected("configuration open rejected");
            held.descriptors.add(configFd);held.watch(configFd);
            if(!named.equals(held.stat(configFd,"")))throw rejected("configuration opened identity mismatch");
            phase.accept("CONFIG_OPENED");held.unchanged();
            byte[] bytes=new byte[4097];int used=0;
            while(used<bytes.length) {
                byte[] part=new byte[bytes.length-used];long count=held.libc.read(configFd,part,part.length);
                if(count<0)throw rejected("configuration read rejected");if(count==0)break;
                System.arraycopy(part,0,bytes,used,(int)count);used+=(int)count;
            }
            if(used>4096||used!=named.size())throw rejected("configuration read size changed");
            phase.accept("CONFIG_READ");
            if(!named.equals(held.stat(configFd,""))||!named.equals(held.stat(fd,"private-mysql.properties")))throw rejected("configuration opened object changed");
            held.unchanged();
            return Optional.of(StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes,0,used)).toString());
        }
    }
    /** Pure-file subprocess entry for the Node selector. No datasource, Fixture,
     * resolver, reducer or graph construction. Fixture mode is bounded to NEW
     * mounted regression paths and returns only status, never file contents.
     * load()/bootstrap and native routing still use ONLY the hard-coded pins.
     */
    public static final class ConfigReader {
        private static final Path RECOVERY_FIXTURE_ROOT=Path.of("/tmp/cyf-r4-exact-api-recovery-bounded-VHJKpm");
        private static final DirectoryPin RECOVERY_FIXTURE_PIN=new DirectoryPin(37,6608,1001,0700);
        private static final String TEMP_LOCATOR="(?:/mnt/Workspace/cyf-multi-region-tenancy-evidence-20261004-GfzaiT/cyf-r4-selector-read-[A-Za-z0-9]+|/mnt/Workspace/cyf-r4-private-mysql-repair-[A-Za-z0-9]+/java-tmp|/tmp/cyf-r4-exact-api-[A-Za-z0-9-]+/cyf-r4-selector-read-[A-Za-z0-9]+)";
        static void checkReaderEnvironment(Map<String,String> environment,List<String> arguments) {
            for(String key:environment.keySet())if(Set.of("NODE_OPTIONS","JAVA_TOOL_OPTIONS","JDK_JAVA_OPTIONS","_JAVA_OPTIONS","JAVA_OPTS","GRADLE_OPTS","LD_PRELOAD","LD_LIBRARY_PATH","LD_AUDIT").contains(key)||key.startsWith("FAKETIME")||key.startsWith("LIBFAKETIME")||key.startsWith("CYF_R4_PRIVATE_MYSQL")||key.startsWith("CYF_R4_FILE_DIAGNOSTIC"))throw rejected("pure reader environment override");
            if(arguments.size()!=1||!arguments.getFirst().matches("-Djava\\.io\\.tmpdir="+TEMP_LOCATOR))throw rejected("pure reader JVM override");
        }
        static DirectoryPin privateTempDirectory(Path directory,int uid) throws Exception {
            if(!directory.isAbsolute()||!directory.normalize().equals(directory)||!directory.toRealPath().equals(directory))throw rejected("pure reader private temp canonical path");
            var attributes=Files.readAttributes(directory,"unix:dev,ino,uid,mode",LinkOption.NOFOLLOW_LINKS);
            int mode=(Integer)attributes.get("mode");
            if((mode&0170000)!=0040000||(mode&07777)!=0700||!Integer.valueOf(uid).equals(attributes.get("uid")))throw rejected("pure reader private temp ownership/kind/mode");
            return new DirectoryPin((Long)attributes.get("dev"),(Long)attributes.get("ino"),uid,0700);
        }
        static PinnedRead holdReaderTemp(Path temp,int uid) throws Exception {
            if(!temp.toString().matches(TEMP_LOCATOR))throw rejected("pure reader private temp locator");
            // Validate BEFORE loading JNA into the temp directory. Then bind the
            // actual opened candidate/temp objects and keep their ancestor and
            // inotify lifetime protection until the pure locator read ends.
            DirectoryPin candidate=privateTempDirectory(temp.getParent(),uid),leaf=privateTempDirectory(temp,uid);
            if(temp.getParent().equals(RECOVERY_FIXTURE_ROOT)&&!candidate.equals(RECOVERY_FIXTURE_PIN))throw rejected("recovery fixture candidate identity");
            var held=new PinnedRead();
            try {
                int fd=held.directory(-100,"/",null);Path current=Path.of("/");
                for(Path component:temp) {
                    current=current.resolve(component);
                    fd=held.directory(fd,component.toString(),current.equals(temp)?leaf:current.equals(temp.getParent())?candidate:null);
                }
                return held;
            } catch(Exception|Error failure){held.close();throw failure;}
        }
        static void checkReaderTempLifetime(Path temp,int uid,Runnable duringHold) throws Exception {
            try(var held=holdReaderTemp(temp,uid)){duringHold.run();held.unchanged();}
        }
        private static PinnedRead holdRecoveryFixture(Path root,Path temp,DirectoryPin parent,DirectoryPin leaf) throws Exception {
            if(!temp.getParent().equals(RECOVERY_FIXTURE_ROOT))throw rejected("recovery fixture must share held temp candidate");
            if(!privateTempDirectory(RECOVERY_FIXTURE_ROOT,1001).equals(RECOVERY_FIXTURE_PIN))throw rejected("recovery fixture candidate identity");
            DirectoryPin cases=privateTempDirectory(RECOVERY_FIXTURE_ROOT.resolve("selector-cases"),1001);
            if(!privateTempDirectory(root.getParent(),1001).equals(parent)||!privateTempDirectory(root,1001).equals(leaf))throw rejected("recovery fixture directory identity");
            var held=new PinnedRead();
            try {
                int fd=held.directory(-100,"/",null);Path current=Path.of("/");
                for(Path component:root) {
                    current=current.resolve(component);
                    DirectoryPin required=current.equals(RECOVERY_FIXTURE_ROOT)?RECOVERY_FIXTURE_PIN:current.equals(RECOVERY_FIXTURE_ROOT.resolve("selector-cases"))?cases:current.equals(root.getParent())?parent:current.equals(root)?leaf:null;
                    fd=held.directory(fd,component.toString(),required);
                }
                return held;
            }catch(Exception|Error failure){held.close();throw failure;}
        }
        public static void main(String[] args) throws Exception {
            checkReaderEnvironment(System.getenv(),java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments());
            Path temp=Path.of(System.getProperty("java.io.tmpdir"));
            try(var heldTemp=holdReaderTemp(temp,1001)) {
            Path root;DirectoryPin parent,leaf;String pause="";boolean recoveryFixture=false;
            if(Arrays.equals(args,new String[]{"fixed"})) {
                root=ROOT;parent=new DirectoryPin(2068,341120,1001,0700);leaf=new DirectoryPin(2068,347914,1001,0700);
            } else if(args.length==5&&"fixture".equals(args[0])&&args[1].matches("/mnt/Workspace/cyf-r4-private-mysql-repair-[A-Za-z0-9]+/selector-cases/[A-Za-z0-9-]+/root")) {
                root=Path.of(args[1]);parent=new DirectoryPin(2068,Long.parseLong(args[2]),1001,0700);leaf=new DirectoryPin(2068,Long.parseLong(args[3]),1001,0700);pause=args[4];
            } else if(args.length==5&&"fixture".equals(args[0])&&args[1].matches(RECOVERY_FIXTURE_ROOT+"/selector-cases/[A-Za-z0-9-]+/root")) {
                root=Path.of(args[1]);parent=new DirectoryPin(37,Long.parseLong(args[2]),1001,0700);leaf=new DirectoryPin(37,Long.parseLong(args[3]),1001,0700);pause=args[4];recoveryFixture=true;
            } else throw rejected("pure reader entry point");
                if(!Set.of("","DIRECTORIES_PINNED","CONFIG_SNAPSHOT","CONFIG_OPENED","CONFIG_READ","CONFIG_ABSENT").contains(pause))throw rejected("fixture phase");
            try(var heldFixture=recoveryFixture?holdRecoveryFixture(root,temp,parent,leaf):null) {
            heldTemp.unchanged();
            final String stopped=pause;
            var bytes=readPinnedConfiguration(root,parent,leaf,1001,phase->{
                if(phase.equals(stopped))try {
                    System.out.println("PRIVATE_R4_READER_PHASE "+phase);System.out.flush();
                    if(!"CONTINUE".equals(new java.io.BufferedReader(new java.io.InputStreamReader(System.in,StandardCharsets.UTF_8)).readLine()))throw rejected("fixture continuation");
                } catch(java.io.IOException failure){throw rejected("fixture continuation");}
            });
            if(bytes.isPresent())parseConfiguration(bytes.get());
            if(heldFixture!=null)heldFixture.unchanged();
            heldTemp.unchanged();
            System.out.println(bytes.isPresent()?"PRIVATE_R4_LOCATOR_VALID":"PRIVATE_R4_LOCATOR_ABSENT");
            }
            }
        }
    }
    private static void checkOwned(Path path,boolean directory) throws Exception {
        if(Files.isSymbolicLink(path)||!path.toRealPath().equals(path)||!Files.getOwner(path,LinkOption.NOFOLLOW_LINKS).equals(Files.getOwner(ROOT.getParent(),LinkOption.NOFOLLOW_LINKS)))throw rejected("private path ownership");
        if(directory?!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS):!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw rejected("private path kind");
        if(!Files.getPosixFilePermissions(path,LinkOption.NOFOLLOW_LINKS).equals(PosixFilePermissions.fromString(directory?"rwx------":"rw-------")))throw rejected("private path mode");
        if(!Integer.valueOf(1001).equals(Files.getAttribute(path,"unix:uid",LinkOption.NOFOLLOW_LINKS)))throw rejected("private path user");
    }
    private static SecurityException rejected(String boundary){return new SecurityException("private native checkpoint rejected: "+boundary);}
    private DriverManagerDataSource dataSource(boolean admin) {
        // Neither URL, endpoint, account nor SQL is caller-selectable.
        return new DriverManagerDataSource(URL,admin?ADMIN_USER:RUNTIME_USER,config.get(admin?"adminPassword":"runtimePassword"));
    }
    private void checkServer(JdbcTemplate jdbc,boolean admin) {
        var row=jdbc.queryForMap("SELECT DATABASE() AS db,@@port AS port,@@version AS version,@@server_uuid AS server_uuid,CURRENT_USER() AS account");
        if(!SCHEMA.equals(row.get("db"))||!PORT.equals(String.valueOf(row.get("port")))||!"8.0.45".equals(row.get("version"))||!config.get("serverUuid").equals(row.get("server_uuid"))||!(admin?ADMIN_USER:RUNTIME_USER).concat("@127.0.0.1").equals(row.get("account")))throw rejected("connected server/account");
        String allowed=expectedPrivileges(admin);
        Set<String> privileges=new TreeSet<>();boolean schemaGrant=false;
        for(String grant:jdbc.queryForList("SHOW GRANTS",String.class)) {
            if(grant.startsWith("GRANT USAGE ON *.* TO ")&&!grant.contains("WITH GRANT OPTION"))continue;
            String prefix="GRANT ",delimiter=" ON `"+SCHEMA+"`.* TO ";int at=grant.indexOf(delimiter);
            if(!grant.startsWith(prefix)||at<0||schemaGrant||grant.contains("WITH GRANT OPTION"))throw rejected("account privilege boundary");
            schemaGrant=true;privileges.addAll(Arrays.asList(grant.substring(prefix.length(),at).split(", ")));
        }
        if(!schemaGrant||!String.join(", ",privileges).equals(allowed))throw rejected("account privilege set");
    }
    static String expectedPrivileges(boolean admin) {
        // The unchanged transport migration creates immutable audit triggers.
        // TRIGGER is schema-local and exclusive to the explicit bootstrap role;
        // the ordinary runtime account remains strictly DML-only.
        return admin?"ALTER, CREATE, DELETE, DROP, INDEX, INSERT, SELECT, TRIGGER, UPDATE":"DELETE, INSERT, SELECT, UPDATE";
    }
    AgentRuntimeV2RegistrationTransactionTest.TrackingDataSource runtimeDataSource() throws Exception {
        var ds=new AgentRuntimeV2RegistrationTransactionTest.TrackingDataSource(URL,RUNTIME_USER,config.get("runtimePassword"));
        var jdbc=new JdbcTemplate(ds);checkServer(jdbc,false);verifyCatalogOnly(jdbc);return ds;
    }
    /** Pure catalog comparison, separated from ALL business graph validators.
     * Calls the actual metadata-only persistence gate and actual private
     * registration catalog validators. Delta/dispatch/execution definitions are
     * compared exactly. Readiness/graph methods are deliberately NOT invoked,
     * and this result is NEVER advertised as full gate/business acceptance.
     */
    static void verifyCatalogOnly(JdbcTemplate jdbc) {
        var catalog=new AgentRuntimeV2EffectConfiguration.CatalogGate(jdbc,new AgentRuntimeV2RegistrationSchemaGate.JdbcCatalog(jdbc),()->verifyPreEffectCatalog(jdbc));
        catalog.afterPropertiesSet();
    }
    private static void verifyPreEffectCatalog(JdbcTemplate jdbc) {
        new AgentRuntimeV2PersistenceSchemaGate(jdbc).afterPropertiesSet();
        var catalog=new AgentRuntimeV2RegistrationSchemaGate.JdbcCatalog(jdbc);
        for(String table:AgentRuntimeV2RegistrationSchemaGate.TABLES) {
            checkTable(catalog,table);
            invokeRegistrationCatalog("validateColumns",List.class,table,catalog.columns(table));
            invokeRegistrationCatalog("validateIndexes",Map.class,table,catalog.indexes(table));
            invokeRegistrationCatalog("validateChecks",Map.class,table,catalog.checks(table));
        }
        for(String table:AgentRuntimeV2RegistrationDeltaSchemaGate.TABLES) {
            checkTable(catalog,table);
            var actual=catalog.columns(table);var expected=AgentRuntimeV2RegistrationDeltaSchemaGate.expectedColumns().get(table);
            if(actual.size()!=expected.size())throw rejected("delta native column count");
            for(int index=0;index<expected.size();index++) {
                var a=actual.get(index);var e=expected.get(index);
                if(!a.name().equals(e.name())||!a.dataType().equals(e.dataType())||!a.columnType().equals(e.columnType())||a.nullable()!=e.nullable()||!Objects.equals(a.defaultValue(),e.defaultValue())||!Objects.equals(a.collation(),e.collation())||!a.extra().equals(e.extra())||!AgentRuntimeV2PersistenceSchemaGate.canonicalCheck(a.generationExpression()).equals(AgentRuntimeV2PersistenceSchemaGate.canonicalCheck(e.generationExpression())))throw rejected("delta native column attributes");
            }
            if(!AgentRuntimeV2RegistrationDeltaSchemaGate.expectedIndexes().get(table).equals(catalog.indexes(table)))throw rejected("delta native indexes");
            checkClauses(catalog,table,AgentRuntimeV2RegistrationDeltaSchemaGate.expectedChecks().get(table));
        }
        String dispatch=AgentRuntimeV2DispatchSchemaGate.TABLE;checkTable(catalog,dispatch);
        if(!AgentRuntimeV2DispatchSchemaGate.expectedColumns().equals(catalog.columns(dispatch))||!AgentRuntimeV2DispatchSchemaGate.expectedIndexes().equals(catalog.indexes(dispatch)))throw rejected("dispatch native catalog");
        checkClauses(catalog,dispatch,AgentRuntimeV2DispatchSchemaGate.expectedChecks());
        // The persistence gate above already validates the full execution
        // extension and raw-request CHECK; retain the execution trigger check.
        Long triggers=jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE() AND event_object_table='agent_command_v2_execution_request'",Long.class);
        if(triggers==null||triggers!=0L)throw rejected("execution native triggers");
    }
    private static void checkTable(AgentRuntimeV2RegistrationSchemaGate.Catalog catalog,String table) {
        var actual=catalog.table(table);
        if(actual==null||!"InnoDB".equalsIgnoreCase(actual.engine())||!"utf8mb4_0900_bin".equals(actual.collation())||catalog.participatingForeignKeyCount(table)!=0)throw rejected("native engine/collation/FK");
    }
    private static void checkClauses(AgentRuntimeV2RegistrationSchemaGate.Catalog catalog,String table,Map<String,String> expected) {
        var actual=catalog.checks(table);if(!actual.keySet().equals(expected.keySet()))throw rejected("native CHECK set");
        expected.forEach((name,clause)->{var a=actual.get(name);if(a==null||!a.enforced()||!AgentRuntimeV2PersistenceSchemaGate.canonicalCheck(clause).equals(AgentRuntimeV2PersistenceSchemaGate.canonicalCheck(a.clause())))throw rejected("native CHECK clause");});
    }
    private static void invokeRegistrationCatalog(String name,Class<?> argument,String table,Object actual) {
        try {var method=AgentRuntimeV2RegistrationSchemaGate.class.getDeclaredMethod(name,String.class,argument);method.setAccessible(true);method.invoke(null,table,actual);}
        catch(java.lang.reflect.InvocationTargetException failure){if(failure.getCause() instanceof RuntimeException cause)throw cause;throw rejected("registration native catalog failure");}
        catch(ReflectiveOperationException failure){throw rejected("registration native catalog method");}
    }
    static void verifyCatalogAndGraphs(JdbcTemplate jdbc) {
        new AgentRuntimeV2PersistenceSchemaGate(jdbc).afterPropertiesSet();
        var registration=new AgentRuntimeV2RegistrationSchemaGate(jdbc);
        var delta=new AgentRuntimeV2RegistrationDeltaSchemaGate(jdbc,registration);
        var dispatch=new AgentRuntimeV2DispatchSchemaGate(jdbc,delta);
        var execution=new AgentRuntimeV2ExecutionSchemaGate(jdbc,dispatch);
        var catalog=new AgentRuntimeV2EffectConfiguration.CatalogGate(jdbc,new AgentRuntimeV2RegistrationSchemaGate.JdbcCatalog(jdbc),execution::afterPropertiesSet);
        catalog.afterPropertiesSet();
        var store=new AgentRuntimeV2ExecutionStoreImpl(new AgentRuntimeV2ExecutionMapper(jdbc));
        new AgentRuntimeV2EffectConfiguration.GraphGate(store,new AgentRuntimeV2ExecutionEvidenceValidator(store),new AgentRuntimeV2HistoricalSettlementEvidenceValidator(new AgentRuntimeV2EffectMapper(jdbc)),catalog).afterPropertiesSet();
    }
    static String resource(String name) throws Exception {
        SourcePin pin=SOURCE_PINS.get(name);if(pin==null)throw rejected("native SQL source identity");
        Path path=API_SOURCE.resolve(pin.relative());
        if(Files.isSymbolicLink(path)||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||!path.toRealPath().equals(path))throw rejected("native SQL source path");
        byte[] bytes=Files.readAllBytes(path);
        if(!java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)).equals(pin.sha256()))throw rejected("native SQL source pin");
        // Runtime gate resources must also be the exact current source, not a
        // stale classpath copy. Parent-only drain/schema SQL is deliberately
        // read from the fixed source pin because OAuth mapper is not required
        // in this isolated registration/R4 classpath.
        if(RUNTIME_RESOURCES.contains(name)||FILE_RESOURCES.contains(name)) {
            byte[] built=new ClassPathResource(name).getContentAsByteArray();
            if(!java.util.Arrays.equals(built,bytes))throw rejected("native SQL classpath/source mismatch");
        }
        return new String(bytes,StandardCharsets.UTF_8);
    }
    static String selectedTaskTable(String sql,String table) {
        if(!Set.of("agent_task_meta","agent_task_member","agent_task_work_item","agent_task_artifact").contains(table))throw rejected("TASK scaffold selection");
        var matcher=Pattern.compile("(?is)CREATE TABLE IF NOT EXISTS "+table+"\\s*\\(.*?\\) ENGINE=InnoDB[^;]*;").matcher(sql);
        if(!matcher.find())throw rejected("TASK native source absent");String result=matcher.group();if(matcher.find())throw rejected("TASK source ambiguous");return result;
    }
    private static void execute(Connection connection,String sql){new ResourceDatabasePopulator(new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8))).populate(connection);}

    /** Columns/types are fixed contract selections, NOT inferred from seed values.
     * Sources: OAuth tenant-authorization-v2 and tenant-module-admission manual
     * candidates; OAuth/User mapper test schema + account lifecycle contract;
     * agent mapper schema and RegistrationStore/current authority field reads.
     * Unrelated full-catalog FKs/legacy owner=tenant checks are not asserted by
     * these compact trusted TEST parents. R2/R4 tables retain EVERY native DDL
     * column/index/CHECK/generated expression and must pass their real gates.
     */
    static List<String> compactAuthorityScaffolds(){
        String suffix=") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin";
        return List.of(
            "CREATE TABLE core_tenant_control(tenant_id VARCHAR(50) NOT NULL,state VARCHAR(32) NOT NULL,control_revision BIGINT NOT NULL,tenant_session_epoch BIGINT NOT NULL,admission_epoch BIGINT NOT NULL,drain_epoch BIGINT NOT NULL,drain_started_at TIMESTAMP(6) NULL,drain_deadline TIMESTAMP(6) NULL,PRIMARY KEY(tenant_id)"+suffix,
            "CREATE TABLE core_tenant_module_control(tenant_id VARCHAR(50) NOT NULL,module_id VARCHAR(64) NOT NULL,mode_state VARCHAR(16) NOT NULL,mode_revision BIGINT NOT NULL,migration_epoch BIGINT NOT NULL,drain_sealed BOOLEAN NOT NULL,schema_version BIGINT NOT NULL,mapping_digest CHAR(64) NULL,drain_cutoff TIMESTAMP(6) NULL,drain_deadline TIMESTAMP(6) NULL,PRIMARY KEY(tenant_id,module_id)"+suffix,
            "CREATE TABLE oauth_client(client_id VARCHAR(100) NOT NULL,PRIMARY KEY(client_id)"+suffix,
            "CREATE TABLE oauth_client_tenant_grant(tenant_id VARCHAR(50) NOT NULL,client_id VARCHAR(100) NOT NULL,status VARCHAR(8) NOT NULL,grant_revision BIGINT NOT NULL,PRIMARY KEY(tenant_id,client_id)"+suffix,
            "CREATE TABLE oauth_client_tenant_domain_grant(tenant_id VARCHAR(50) NOT NULL,client_id VARCHAR(100) NOT NULL,data_domain_id VARCHAR(64) NOT NULL,status VARCHAR(8) NOT NULL,domain_revision BIGINT NOT NULL,PRIMARY KEY(tenant_id,client_id,data_domain_id)"+suffix,
            "CREATE TABLE user_info(id BIGINT NOT NULL,jiacn VARCHAR(32) NOT NULL,account_state VARCHAR(16) NOT NULL,auth_epoch BIGINT NOT NULL,PRIMARY KEY(jiacn)"+suffix,
            "CREATE TABLE oauth_api_key(id VARCHAR(100) NOT NULL,tenant_id VARCHAR(100) NOT NULL,client_id VARCHAR(100) NOT NULL,jiacn VARCHAR(32) NOT NULL,status INT NOT NULL,expire_time BIGINT NULL,api_key VARCHAR(255) NOT NULL,PRIMARY KEY(id)"+suffix,
            "CREATE TABLE agent_runtime(id BIGINT NOT NULL,tenant_id VARCHAR(50) NOT NULL,client_id VARCHAR(50) NOT NULL,owner_jiacn VARCHAR(50) NOT NULL,agent_id VARCHAR(100) NOT NULL,binding_id BIGINT NOT NULL,PRIMARY KEY(tenant_id,client_id,owner_jiacn,agent_id)"+suffix,
            "CREATE TABLE agent_identity_registry(id BIGINT NOT NULL,tenant_id VARCHAR(50) NOT NULL,client_id VARCHAR(50) NOT NULL,owner_jiacn VARCHAR(50) NOT NULL,canonical_agent_id VARCHAR(100) NOT NULL,lifecycle_status VARCHAR(20) NOT NULL,binding_id BIGINT NOT NULL,update_time BIGINT NULL,PRIMARY KEY(tenant_id,client_id,owner_jiacn,canonical_agent_id)"+suffix,
            "CREATE TABLE agent_persona_binding(id BIGINT NOT NULL,tenant_id VARCHAR(50) NOT NULL,client_id VARCHAR(50) NOT NULL,jiacn VARCHAR(50) NOT NULL,agent_id VARCHAR(100) NOT NULL,status INT NOT NULL,update_time BIGINT NULL,PRIMARY KEY(id)"+suffix,
            "CREATE TABLE core_deployment_registration(deployment_id VARCHAR(100) NOT NULL,status VARCHAR(12) NOT NULL,stage VARCHAR(12) NOT NULL,artifact_digest CHAR(64) NOT NULL,worker_generation BIGINT NOT NULL,registration_revision BIGINT NOT NULL,credential_digest CHAR(64) NOT NULL,PRIMARY KEY(deployment_id)"+suffix,
            "CREATE TABLE core_deployment_tenant_grant(deployment_id VARCHAR(100) NOT NULL,tenant_id VARCHAR(50) NOT NULL,data_domain_id VARCHAR(64) NOT NULL,status VARCHAR(12) NOT NULL,execution_pool_id VARCHAR(100) NOT NULL,grant_revision BIGINT NOT NULL,allow_read BOOLEAN NOT NULL,allow_write BOOLEAN NOT NULL,PRIMARY KEY(deployment_id,tenant_id,data_domain_id)"+suffix,
            "CREATE TABLE core_deployment_module_admission(deployment_id VARCHAR(100) NOT NULL,tenant_id VARCHAR(50) NOT NULL,data_domain_id VARCHAR(64) NOT NULL,module_id VARCHAR(64) NOT NULL,mode_state VARCHAR(16) NOT NULL,mode_revision BIGINT NOT NULL,migration_epoch BIGINT NOT NULL,admission_epoch BIGINT NOT NULL,schema_version BIGINT NOT NULL,registration_revision BIGINT NOT NULL,grant_revision BIGINT NOT NULL,allow_read BOOLEAN NOT NULL,allow_write BOOLEAN NOT NULL,status VARCHAR(12) NOT NULL,PRIMARY KEY(deployment_id,tenant_id,data_domain_id,module_id)"+suffix,
            "CREATE TABLE core_tenant_operation(tenant_id VARCHAR(50) NOT NULL,operator_id VARCHAR(128) NOT NULL,idempotency_key VARCHAR(128) NOT NULL,to_state VARCHAR(32) NOT NULL,result_revision BIGINT NOT NULL,result_snapshot VARCHAR(200) NOT NULL,PRIMARY KEY(tenant_id,operator_id,idempotency_key)"+suffix);
    }
    private void bootstrap() throws Exception {
        var admin=dataSource(true);var jdbc=new JdbcTemplate(admin);checkServer(jdbc,true);
        if(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",Long.class)!=0L)throw rejected("bootstrap requires empty private schema");
        // Resolve every resource before the first DDL. No partially sourced plan.
        Map<String,String> sources=new LinkedHashMap<>();
        for(String name:RUNTIME_RESOURCES)sources.put(name,resource(name));
        for(String name:FILE_RESOURCES)sources.put(name,resource(name));
        String drain=resource("db/runtime-v2-drain-authority-manual-20261001.sql");
        String task=resource("db/schema.sql");
        List<String> taskDdl=new ArrayList<>();for(String name:List.of("agent_task_meta","agent_task_member","agent_task_work_item","agent_task_artifact"))taskDdl.add(selectedTaskTable(task,name));
        try(var connection=admin.getConnection()) {
            for(String ddl:compactAuthorityScaffolds())execute(connection,ddl);
            for(String name:RUNTIME_RESOURCES) {
                execute(connection,sources.get(name));
                if(name.equals("db/agent-outbox-relay-v2-manual-20260925.sql"))new AgentOutboxRelayV2SchemaGate(jdbc).afterPropertiesSet();
                if(name.equals("db/agent-command-consumer-v2-manual-20260925.sql"))new AgentCommandConsumerV2SchemaGate(jdbc).afterPropertiesSet();
            }
            execute(connection,drain);
            for(String ddl:taskDdl)execute(connection,ddl);
            for(String name:FILE_RESOURCES)execute(connection,sources.get(name));
        }
        verifyCatalogOnly(jdbc);
        // Validate runtime's exact DML-only grant and the same server/schema.
        var runtime=new JdbcTemplate(dataSource(false));checkServer(runtime,false);verifyCatalogOnly(runtime);
    }
    public static void main(String[] args) throws Exception {
        if(!Arrays.equals(args,new String[]{"bootstrap"}))throw rejected("bootstrap entry point");
        load().bootstrap();
        System.out.println("PRIVATE_R4_NATIVE_BOOTSTRAP_OK (test-only; no business invocation)");
    }
}
