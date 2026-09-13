package cn.jia.wx.api;

import cn.jia.core.redis.RedisService;
import cn.jia.mat.entity.MatDailyVoteAnswerResult;
import cn.jia.mat.service.MatVoteService;
import cn.jia.point.entity.PointRecordEntity;
import cn.jia.point.service.PointService;
import cn.jia.wx.dao.WxDailyVoteReceiptDao;
import cn.jia.wx.dao.impl.WxDailyVoteReceiptDaoImpl;
import cn.jia.wx.dailyvote.WxDailyVoteAnswerCommand;
import cn.jia.wx.dailyvote.WxDailyVoteAnswerResult;
import cn.jia.wx.dailyvote.WxDailyVoteKeys;
import cn.jia.wx.dailyvote.WxDailyVoteReplayQuery;
import cn.jia.wx.entity.MpInfoEntity;
import cn.jia.wx.entity.MpUserEntity;
import cn.jia.wx.entity.WxDailyVoteMessageReceiptEntity;
import cn.jia.wx.entity.WxDailyVoteReceiptEntity;
import cn.jia.wx.mapper.WxDailyVoteReceiptMapper;
import cn.jia.wx.service.MpInfoService;
import cn.jia.wx.service.MpUserService;
import cn.jia.wx.service.WxDailyVoteService;
import cn.jia.wx.service.impl.WxDailyVoteServiceImpl;
import jakarta.servlet.http.HttpServletRequest;
import me.chanjar.weixin.mp.api.WxMpService;
import me.chanjar.weixin.mp.bean.message.WxMpXmlMessage;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Verifier-owned MySQL proof for callback replay after the proxied answer transaction rolls back.
 * This class is intentionally selected separately from the unit-only test set.
 */
@EnabledIfEnvironmentVariable(named = WxDailyVoteCallbackMySqlTransactionTest.MySqlFixture.URL_ENV,
        matches = ".+")
class WxDailyVoteCallbackMySqlTransactionTest {

    private static final String APPID = "wx-test-app";
    private static final String ORIGINAL = "gh_test";
    private static final String OPENID = "openid-secret-value";
    private static final String JIACN = "user-1";
    private static final long Q1 = 337L;
    private static final long Q2 = 338L;
    private static final String OTHER_MESSAGE_KEY = "c".repeat(64);
    private static final String REPLY_Q1 = "恭喜你，答案正确，增加2积分！";
    private static final String XML_A = xml("A");
    private static final String XML_B = xml("B");

    private final MySqlFixture fixture = new MySqlFixture();
    private MySqlFixture.Database database;
    private JdbcTemplate jdbc;
    private PlatformTransactionManager transactionManager;
    private WxDailyVoteReceiptDao realReceiptDao;
    private MatVoteService voteService;
    private PointService pointService;
    private ThreadPoolTaskExecutor taskExecutor;
    private String messageKey;
    private String userKey;
    private ExecutorService writerExecutor;

    @BeforeEach
    void setUp() throws Exception {
        fixture.start();
        database = fixture.newDatabase("callback");
        jdbc = database.jdbc();
        transactionManager = new DataSourceTransactionManager(database.dataSource());
        createTables();
        realReceiptDao = new WxDailyVoteReceiptDaoImpl(realMapper(database.dataSource()));
        voteService = mock(MatVoteService.class);
        pointService = mock(PointService.class);
        messageKey = WxDailyVoteKeys.messageKey(APPID, WxMpXmlMessage.fromXml(XML_A));
        userKey = WxDailyVoteKeys.userKey(APPID, JIACN);
        writerExecutor = Executors.newSingleThreadExecutor();
        arrangeBusinessMutationsIfUnexpectedlyReached();
    }

    @AfterEach
    void tearDown() throws Exception {
        boolean writerStopped = true;
        try {
            if (writerExecutor != null) {
                writerExecutor.shutdownNow();
                writerStopped = writerExecutor.awaitTermination(10, TimeUnit.SECONDS);
            }
        } finally {
            fixture.close();
        }
        assertTrue(writerStopped);
    }

    @Test
    void lateDifferentCanonicalAliasRollsBackTemporaryQ2ThenCurrentCallbackReplaysQ1() throws Exception {
        seedBusinessState(false);
        long q1Receipt = insertCompleted(OTHER_MESSAGE_KEY, Q1, "A", REPLY_Q1);
        insertAlias(q1Receipt, OTHER_MESSAGE_KEY, Q1, "A");
        BusinessSnapshot before = businessSnapshot();

        CommitAfterMessageMissDao racingDao = new CommitAfterMessageMissDao(
                realReceiptDao, jdbc, messageKey, writerExecutor,
                () -> inTransaction(() -> insertAlias(q1Receipt, messageKey, Q1, "A")));
        ObservingVoteService observing = observingService(racingDao);
        PointerRedisService redis = new PointerRedisService(String.valueOf(Q2));
        WxMpController controller = controller(observing, redis);

        String response = (String) controller.receiveMsg(XML_A, request());

        assertTrue(response.contains(REPLY_Q1));
        assertTrue(racingDao.transactionObserved.get());
        assertTrue(racingDao.insertedTemporaryClaim.get(), "the M2/Q2 receipt must exist before rollback");
        assertTrue(observing.rollbackVisibleBeforeReplay.get());
        assertEquals(2, observing.replayQueries);
        assertEquals(String.valueOf(Q2), redis.pointer());
        assertEquals(List.of(String.valueOf(Q1)), redis.compareDeleteValues);
        assertEquals(0, receiptCount(messageKey, Q2));
        assertEquals(before, businessSnapshot());
        verifyNoInteractions(voteService, pointService);
        verify(taskExecutor, never()).execute(any(Runnable.class));
    }

    @Test
    void legitimateM1Q1AndM2Q2DualReceiptsStillReturnQ1InCurrentCallback() throws Exception {
        seedBusinessState(true);
        long q2Receipt = insertCompleted(OTHER_MESSAGE_KEY, Q2, "A", "persisted-q2");
        insertAlias(q2Receipt, OTHER_MESSAGE_KEY, Q2, "A");
        BusinessSnapshot before = businessSnapshot();

        CommitAfterMessageMissDao racingDao = new CommitAfterMessageMissDao(
                realReceiptDao, jdbc, messageKey, writerExecutor, () -> inTransaction(() -> {
                    long q1Receipt = insertCompleted(messageKey, Q1, "A", REPLY_Q1);
                    insertAlias(q1Receipt, messageKey, Q1, "A");
                }));
        ObservingVoteService observing = observingService(racingDao);
        PointerRedisService redis = new PointerRedisService(String.valueOf(Q2));
        WxMpController controller = controller(observing, redis);

        String response = (String) controller.receiveMsg(XML_A, request());

        assertTrue(response.contains(REPLY_Q1));
        assertTrue(racingDao.transactionObserved.get());
        assertFalse(racingDao.insertedTemporaryClaim.get(),
                "the late M1 receipt must win the message key before the speculative claim");
        assertTrue(observing.rollbackVisibleBeforeReplay.get());
        assertEquals(2, observing.replayQueries);
        assertEquals(1, receiptCount(messageKey, Q1));
        assertEquals(1, receiptCount(OTHER_MESSAGE_KEY, Q2));
        assertEquals(String.valueOf(Q2), redis.pointer());
        assertEquals(List.of(String.valueOf(Q1)), redis.compareDeleteValues);
        assertEquals(before, businessSnapshot());
        verifyNoInteractions(voteService, pointService);
        verify(taskExecutor, never()).execute(any(Runnable.class));
    }

    @Test
    void lateAliasWithTamperedPayloadRethrowsOriginalAfterRollbackAndPreservesQ2() throws Exception {
        seedBusinessState(false);
        long q1Receipt = insertCompleted(OTHER_MESSAGE_KEY, Q1, "A", REPLY_Q1);
        insertAlias(q1Receipt, OTHER_MESSAGE_KEY, Q1, "A");
        BusinessSnapshot before = businessSnapshot();

        CommitAfterMessageMissDao racingDao = new CommitAfterMessageMissDao(
                realReceiptDao, jdbc, messageKey, writerExecutor,
                () -> inTransaction(() -> insertAlias(q1Receipt, messageKey, Q1, "A")));
        ObservingVoteService observing = observingService(racingDao);
        PointerRedisService redis = new PointerRedisService(String.valueOf(Q2));
        WxMpController controller = controller(observing, redis);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> controller.receiveMsg(XML_B, request()));

        assertSame(observing.answerFailure.get(), thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertTrue(thrown.getSuppressed()[0].getMessage().contains("payload mismatch"));
        assertTrue(racingDao.transactionObserved.get());
        assertTrue(racingDao.insertedTemporaryClaim.get());
        assertTrue(observing.rollbackVisibleBeforeReplay.get());
        assertEquals(2, observing.replayQueries);
        assertEquals(String.valueOf(Q2), redis.pointer());
        assertTrue(redis.compareDeleteValues.isEmpty());
        assertEquals(0, receiptCount(messageKey, Q2));
        assertEquals(before, businessSnapshot());
        verifyNoInteractions(voteService, pointService);
        verify(taskExecutor, never()).execute(any(Runnable.class));
    }

    private ObservingVoteService observingService(WxDailyVoteReceiptDao receiptDao) {
        WxDailyVoteServiceImpl target = new WxDailyVoteServiceImpl(receiptDao, voteService, pointService);
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(transactionManager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(interceptor);
        WxDailyVoteService proxied = (WxDailyVoteService) factory.getProxy();
        assertTrue(AopUtils.isAopProxy(proxied));
        return new ObservingVoteService(proxied);
    }

    private WxMpController controller(WxDailyVoteService service, RedisService redis) {
        WxMpController controller = new WxMpController();
        MpInfoService mpInfoService = mock(MpInfoService.class);
        MpUserService mpUserService = mock(MpUserService.class);
        taskExecutor = mock(ThreadPoolTaskExecutor.class);
        WxMpService wxMpService = mock(WxMpService.class, RETURNS_DEEP_STUBS);

        ReflectionTestUtils.setField(controller, "mpInfoService", mpInfoService);
        ReflectionTestUtils.setField(controller, "mpUserService", mpUserService);
        ReflectionTestUtils.setField(controller, "redisService", redis);
        ReflectionTestUtils.setField(controller, "dailyVoteService", service);
        ReflectionTestUtils.setField(controller, "taskExecutor", taskExecutor);

        when(mpInfoService.findWxMpService(any(HttpServletRequest.class))).thenReturn(wxMpService);
        when(wxMpService.getWxMpConfigStorage().getAppId()).thenReturn(APPID);
        when(wxMpService.checkSignature("1710000000", "nonce", "valid-signature")).thenReturn(true);
        when(mpInfoService.findByKey(APPID)).thenReturn(new MpInfoEntity()
                .setAppid(APPID).setOriginal(ORIGINAL).setClientId("client-1").setName("公众号"));
        when(mpUserService.findByAppIdAndOpenId(APPID, OPENID)).thenReturn(new MpUserEntity()
                .setAppid(APPID).setOpenId(OPENID).setJiacn(JIACN));
        return controller;
    }

    private HttpServletRequest request() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getParameter("signature")).thenReturn("valid-signature");
        when(request.getParameter("timestamp")).thenReturn("1710000000");
        when(request.getParameter("nonce")).thenReturn("nonce");
        return request;
    }

    private void arrangeBusinessMutationsIfUnexpectedlyReached() {
        when(voteService.answerDaily(anyLong(), eq(JIACN), anyString())).thenAnswer(invocation -> {
            long questionId = invocation.getArgument(0);
            String answer = invocation.getArgument(2);
            jdbc.update("INSERT INTO mat_vote_tick(jiacn,vote_id,question_id,opt,tick) VALUES(?,?,?,?,1)",
                    JIACN, 1L, questionId, answer);
            jdbc.update("UPDATE mat_vote SET num=num+1 WHERE id=1");
            jdbc.update("UPDATE mat_vote_item SET num=num+1 WHERE question_id=? AND opt=?",
                    questionId, answer);
            return new MatDailyVoteAnswerResult(questionId, true, 2, "A");
        });
        when(pointService.add(eq(JIACN), anyInt(), anyInt())).thenAnswer(invocation -> {
            int point = invocation.getArgument(1);
            jdbc.update("UPDATE user_info SET point=point+? WHERE jiacn=?", point, JIACN);
            return new PointRecordEntity();
        });
    }

    private WxDailyVoteReceiptMapper realMapper(DataSource dataSource) throws Exception {
        org.apache.ibatis.session.Configuration configuration =
                new org.apache.ibatis.session.Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setConfiguration(configuration);
        factoryBean.setMapperLocations(
                new ClassPathResource("cn/jia/wx/mapper/WxDailyVoteReceiptMapper.xml"));
        SqlSessionFactory factory = factoryBean.getObject();
        if (factory == null) throw new IllegalStateException("missing SqlSessionFactory");
        return new SqlSessionTemplate(factory).getMapper(WxDailyVoteReceiptMapper.class);
    }

    private void createTables() {
        jdbc.execute("""
                CREATE TABLE wx_daily_vote_receipt (
                    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    appid VARCHAR(50) NOT NULL,
                    message_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                    user_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                    request_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                    question_id BIGINT NOT NULL,
                    processor_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                    correct TINYINT DEFAULT NULL,
                    point_awarded INT DEFAULT NULL,
                    reply_content VARCHAR(512) DEFAULT NULL,
                    create_time BIGINT NOT NULL,
                    update_time BIGINT NOT NULL,
                    UNIQUE KEY uk_wx_daily_vote_message (appid,message_key),
                    UNIQUE KEY uk_wx_daily_vote_user_question (appid,user_key,question_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
                """);
        jdbc.execute("""
                CREATE TABLE wx_daily_vote_message_receipt (
                    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    receipt_id BIGINT NOT NULL,
                    appid VARCHAR(50) NOT NULL,
                    message_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                    user_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                    request_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                    question_id BIGINT NOT NULL,
                    create_time BIGINT NOT NULL,
                    update_time BIGINT NOT NULL,
                    UNIQUE KEY uk_wx_daily_vote_message_alias (appid,message_key),
                    CONSTRAINT fk_wx_daily_vote_message_receipt FOREIGN KEY(receipt_id)
                        REFERENCES wx_daily_vote_receipt(id) ON DELETE CASCADE
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
                """);
        jdbc.execute("CREATE TABLE mat_vote(id BIGINT PRIMARY KEY,num INT NOT NULL) ENGINE=InnoDB");
        jdbc.execute("""
                CREATE TABLE mat_vote_question(
                    id BIGINT PRIMARY KEY,vote_id BIGINT NOT NULL,point INT NOT NULL,opt VARCHAR(6) NOT NULL
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE mat_vote_item(
                    id BIGINT PRIMARY KEY,question_id BIGINT NOT NULL,opt VARCHAR(6) NOT NULL,num INT NOT NULL
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE mat_vote_tick(
                    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,jiacn VARCHAR(32) NOT NULL,
                    vote_id BIGINT NOT NULL,question_id BIGINT NOT NULL,opt VARCHAR(6) NOT NULL,tick INT NOT NULL
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE user_info(
                    id BIGINT PRIMARY KEY,jiacn VARCHAR(32) NOT NULL,point INT NOT NULL
                ) ENGINE=InnoDB
                """);
    }

    private void seedBusinessState(boolean includeQ2) {
        int answered = includeQ2 ? 2 : 1;
        jdbc.update("INSERT INTO mat_vote(id,num) VALUES(1,?)", answered);
        jdbc.update("INSERT INTO mat_vote_question(id,vote_id,point,opt) VALUES(337,1,2,'A'),(338,1,2,'A')");
        jdbc.update("INSERT INTO mat_vote_item(id,question_id,opt,num) VALUES(1,337,'A',1),(2,338,'A',?)",
                includeQ2 ? 1 : 0);
        jdbc.update("INSERT INTO mat_vote_tick(jiacn,vote_id,question_id,opt,tick) VALUES(?,1,337,'A',1)",
                JIACN);
        if (includeQ2) {
            jdbc.update("INSERT INTO mat_vote_tick(jiacn,vote_id,question_id,opt,tick) VALUES(?,1,338,'A',1)",
                    JIACN);
        }
        jdbc.update("INSERT INTO user_info(id,jiacn,point) VALUES(1,?,?)", JIACN, answered * 2);
    }

    private long insertCompleted(String canonicalMessage, long questionId, String answer, String reply) {
        String fingerprint = WxDailyVoteKeys.requestFingerprint(APPID, userKey, questionId, answer);
        jdbc.update("""
                INSERT INTO wx_daily_vote_receipt
                (appid,message_key,user_key,request_fingerprint,question_id,processor_token,status,
                 correct,point_awarded,reply_content,create_time,update_time)
                VALUES(?,?,?,?,?,'11111111-1111-1111-1111-111111111111','COMPLETED',1,2,?,1,2)
                """, APPID, canonicalMessage, userKey, fingerprint, questionId, reply);
        return jdbc.queryForObject("SELECT id FROM wx_daily_vote_receipt WHERE appid=? AND message_key=?",
                Long.class, APPID, canonicalMessage);
    }

    private void insertAlias(long receiptId, String aliasMessage, long questionId, String answer) {
        String fingerprint = WxDailyVoteKeys.requestFingerprint(APPID, userKey, questionId, answer);
        jdbc.update("""
                INSERT INTO wx_daily_vote_message_receipt
                (receipt_id,appid,message_key,user_key,request_fingerprint,question_id,create_time,update_time)
                VALUES(?,?,?,?,?,?,1,2)
                """, receiptId, APPID, aliasMessage, userKey, fingerprint, questionId);
    }

    private void inTransaction(Runnable work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> work.run());
    }

    private int receiptCount(String canonicalMessage, long questionId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM wx_daily_vote_receipt
                WHERE appid=? AND message_key=? AND question_id=?
                """, Integer.class, APPID, canonicalMessage, questionId);
    }

    private BusinessSnapshot businessSnapshot() {
        return new BusinessSnapshot(
                jdbc.queryForObject("SELECT num FROM mat_vote WHERE id=1", Integer.class),
                jdbc.queryForObject("SELECT num FROM mat_vote_item WHERE question_id=337", Integer.class),
                jdbc.queryForObject("SELECT num FROM mat_vote_item WHERE question_id=338", Integer.class),
                jdbc.queryForObject("SELECT COUNT(*) FROM mat_vote_tick WHERE jiacn=?", Integer.class, JIACN),
                jdbc.queryForObject("SELECT point FROM user_info WHERE jiacn=?", Integer.class, JIACN));
    }

    private static String xml(String answer) {
        return "<xml>"
                + "<ToUserName><![CDATA[" + ORIGINAL + "]]></ToUserName>"
                + "<FromUserName><![CDATA[" + OPENID + "]]></FromUserName>"
                + "<CreateTime>1710000000</CreateTime>"
                + "<MsgType><![CDATA[text]]></MsgType>"
                + "<Content><![CDATA[" + answer + "]]></Content>"
                + "<MsgId>123456789</MsgId>"
                + "</xml>";
    }

    private record BusinessSnapshot(int voteCount, int q1ItemCount, int q2ItemCount,
                                    int tickCount, int point) {
    }

    private final class ObservingVoteService implements WxDailyVoteService {
        private final WxDailyVoteService delegate;
        private final AtomicReference<RuntimeException> answerFailure = new AtomicReference<>();
        private final AtomicBoolean rollbackVisibleBeforeReplay = new AtomicBoolean();
        private int replayQueries;

        private ObservingVoteService(WxDailyVoteService delegate) {
            this.delegate = delegate;
        }

        @Override
        public Optional<WxDailyVoteAnswerResult> findReplay(WxDailyVoteReplayQuery query) {
            replayQueries++;
            if (answerFailure.get() != null) {
                boolean outsideTransaction = !TransactionSynchronizationManager.isActualTransactionActive();
                boolean temporaryReceiptGone = receiptCount(messageKey, Q2) == 0;
                rollbackVisibleBeforeReplay.set(outsideTransaction && temporaryReceiptGone);
            }
            return delegate.findReplay(query);
        }

        @Override
        public WxDailyVoteAnswerResult answer(WxDailyVoteAnswerCommand command) {
            try {
                return delegate.answer(command);
            } catch (RuntimeException failure) {
                answerFailure.set(failure);
                throw failure;
            }
        }
    }

    private static final class PointerRedisService extends RedisService {
        private final AtomicReference<String> pointer;
        private final List<String> compareDeleteValues = new ArrayList<>();

        private PointerRedisService(String value) {
            pointer = new AtomicReference<>(value);
        }

        @Override
        public String get(String key) {
            return pointer.get();
        }

        @Override
        public void set(String key, String value, Duration duration) {
            // Active-user marker is unrelated to this fixture.
        }

        @Override
        public boolean deleteIfValueEquals(String key, String expectedValue) {
            compareDeleteValues.add(expectedValue);
            return pointer.compareAndSet(expectedValue, null);
        }

        private String pointer() {
            return pointer.get();
        }
    }

    private static final class CommitAfterMessageMissDao implements WxDailyVoteReceiptDao {
        private final WxDailyVoteReceiptDao delegate;
        private final JdbcTemplate transactionJdbc;
        private final String watchedMessage;
        private final ExecutorService executor;
        private final Runnable commit;
        private final AtomicBoolean committed = new AtomicBoolean();
        private final AtomicBoolean transactionObserved = new AtomicBoolean();
        private final AtomicBoolean insertedTemporaryClaim = new AtomicBoolean();

        private CommitAfterMessageMissDao(WxDailyVoteReceiptDao delegate, JdbcTemplate transactionJdbc,
                                          String watchedMessage, ExecutorService executor, Runnable commit) {
            this.delegate = delegate;
            this.transactionJdbc = transactionJdbc;
            this.watchedMessage = watchedMessage;
            this.executor = executor;
            this.commit = commit;
        }

        @Override
        public int claim(WxDailyVoteReceiptEntity receipt) {
            transactionObserved.set(TransactionSynchronizationManager.isActualTransactionActive()
                    && TransactionSynchronizationManager.hasResource(transactionJdbc.getDataSource()));
            int rows = delegate.claim(receipt);
            if (watchedMessage.equals(receipt.getMessageKey())
                    && Long.valueOf(Q2).equals(receipt.getQuestionId())) {
                int ownedClaim = transactionJdbc.queryForObject("""
                        SELECT COUNT(*) FROM wx_daily_vote_receipt
                        WHERE appid=? AND message_key=? AND question_id=? AND processor_token=?
                        """, Integer.class, receipt.getAppid(), watchedMessage, Q2,
                        receipt.getProcessorToken());
                insertedTemporaryClaim.set(ownedClaim == 1);
            }
            return rows;
        }

        @Override
        public List<WxDailyVoteReceiptEntity> lockConflicts(
                String appid, String messageKey, String userKey, long questionId) {
            return delegate.lockConflicts(appid, messageKey, userKey, questionId);
        }

        @Override
        public int claimMessage(WxDailyVoteMessageReceiptEntity messageReceipt) {
            return delegate.claimMessage(messageReceipt);
        }

        @Override
        public WxDailyVoteMessageReceiptEntity selectMessage(String appid, String messageKey) {
            WxDailyVoteMessageReceiptEntity result = delegate.selectMessage(appid, messageKey);
            if (result == null && watchedMessage.equals(messageKey) && committed.compareAndSet(false, true)) {
                try {
                    Future<?> future = executor.submit(commit);
                    future.get(10, TimeUnit.SECONDS);
                } catch (Exception failure) {
                    throw new IllegalStateException("late receipt commit fixture failed", failure);
                }
            }
            return result;
        }

        @Override
        public WxDailyVoteMessageReceiptEntity lockMessage(String appid, String messageKey) {
            return delegate.lockMessage(appid, messageKey);
        }

        @Override
        public WxDailyVoteReceiptEntity selectCompletedByMessage(String appid, String messageKey) {
            return delegate.selectCompletedByMessage(appid, messageKey);
        }

        @Override
        public WxDailyVoteReceiptEntity lockById(long id) {
            return delegate.lockById(id);
        }

        @Override
        public int complete(long id, String processorToken, String requestFingerprint, boolean correct,
                            int pointAwarded, String replyContent, long updateTime) {
            return delegate.complete(id, processorToken, requestFingerprint, correct,
                    pointAwarded, replyContent, updateTime);
        }
    }

    static final class MySqlFixture implements AutoCloseable {
        static final String URL_ENV = "WX_DAILY_VOTE_MYSQL_URL";
        private static final Pattern PREFIX = Pattern.compile("[a-z][a-z0-9_]{0,23}");
        private static final Pattern SUFFIX = Pattern.compile("[a-z][a-z0-9_]{0,15}");

        private JdbcTemplate admin;
        private String baseUrl;
        private String username;
        private String password;
        private String databasePrefix;
        private final List<String> databases = new ArrayList<>();

        void start() {
            baseUrl = requiredNonBlank(URL_ENV);
            if (!baseUrl.startsWith("jdbc:mysql://")) {
                throw new IllegalStateException(URL_ENV + " must be a jdbc:mysql:// URL");
            }
            username = requiredNonBlank("WX_DAILY_VOTE_MYSQL_USER");
            password = requiredPresent("WX_DAILY_VOTE_MYSQL_PASSWORD");
            if (!"true".equals(requiredNonBlank("WX_DAILY_VOTE_MYSQL_ISOLATED_FIXTURE"))) {
                throw new IllegalStateException("WX_DAILY_VOTE_MYSQL_ISOLATED_FIXTURE must equal true");
            }
            databasePrefix = requiredNonBlank("WX_DAILY_VOTE_MYSQL_DATABASE_PREFIX");
            if (!PREFIX.matcher(databasePrefix).matches()) {
                throw new IllegalStateException("WX_DAILY_VOTE_MYSQL_DATABASE_PREFIX is unsafe");
            }
            admin = new JdbcTemplate(dataSource(baseUrl));
            String version = admin.queryForObject("SELECT VERSION()", String.class);
            assertTrue(version != null && version.startsWith("8.0.21"), version);
            int expectedPort = Integer.parseInt(requiredNonBlank("WX_DAILY_VOTE_MYSQL_EXPECTED_PORT"));
            if (expectedPort == 3306 || expectedPort == 33060) {
                throw new IllegalStateException("refusing standard MySQL port " + expectedPort);
            }
            assertEquals(expectedPort,
                    admin.queryForObject("SELECT @@port", Integer.class).intValue());
            String expectedDatadir = normalized(requiredNonBlank("WX_DAILY_VOTE_MYSQL_EXPECTED_DATADIR"));
            String actualDatadir = normalized(admin.queryForObject("SELECT @@datadir", String.class));
            assertEquals(expectedDatadir, actualDatadir);
        }

        Database newDatabase(String suffix) {
            if (admin == null) throw new IllegalStateException("MySQL fixture is not started");
            if (suffix == null || !SUFFIX.matcher(suffix).matches()) {
                throw new IllegalArgumentException("unsafe MySQL fixture database suffix");
            }
            String database = databasePrefix + "_" + suffix + "_"
                    + Long.toUnsignedString(System.nanoTime(), 36);
            if (database.length() > 64 || !database.startsWith(databasePrefix + "_")) {
                throw new IllegalStateException("unsafe MySQL fixture database name");
            }
            databases.add(database);
            admin.execute("CREATE DATABASE `" + database
                    + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
            DriverManagerDataSource source = dataSource(databaseUrl(baseUrl, database));
            return new Database(database, source, new JdbcTemplate(source));
        }

        @Override
        public void close() {
            if (admin == null) return;
            while (!databases.isEmpty()) {
                int index = databases.size() - 1;
                String database = databases.get(index);
                if (!database.startsWith(databasePrefix + "_")) {
                    throw new IllegalStateException("refusing to drop an unowned MySQL database");
                }
                admin.execute("DROP DATABASE IF EXISTS `" + database + "`");
                databases.remove(index);
            }
            admin = null;
        }

        private DriverManagerDataSource dataSource(String url) {
            DriverManagerDataSource source = new DriverManagerDataSource();
            source.setDriverClassName("com.mysql.cj.jdbc.Driver");
            source.setUrl(boundedJdbcUrl(url));
            source.setUsername(username);
            source.setPassword(password);
            return source;
        }

        private static String boundedJdbcUrl(String url) {
            int query = url.indexOf('?');
            String prefix = query < 0 ? url : url.substring(0, query);
            List<String> options = new ArrayList<>();
            if (query >= 0) {
                for (String option : url.substring(query + 1).split("&")) {
                    String key = URLDecoder.decode(option.split("=", 2)[0], StandardCharsets.UTF_8);
                    if (!option.isBlank() && !key.equalsIgnoreCase("connectTimeout")
                            && !key.equalsIgnoreCase("socketTimeout")
                            && !key.equalsIgnoreCase("autoReconnect")
                            && !key.equalsIgnoreCase("autoReconnectForPools")) {
                        options.add(option);
                    }
                }
            }
            options.add("connectTimeout=10000");
            options.add("socketTimeout=120000");
            options.add("autoReconnect=false");
            options.add("autoReconnectForPools=false");
            return prefix + "?" + String.join("&", options);
        }

        private static String databaseUrl(String url, String database) {
            int query = url.indexOf('?');
            String suffix = query < 0 ? "" : url.substring(query);
            String prefix = query < 0 ? url : url.substring(0, query);
            int slash = prefix.indexOf('/', "jdbc:mysql://".length());
            return slash < 0
                    ? prefix + "/" + database + suffix
                    : prefix.substring(0, slash + 1) + database + suffix;
        }

        private static String normalized(String path) {
            return Path.of(path).toAbsolutePath().normalize().toString();
        }

        private static String requiredNonBlank(String name) {
            String value = System.getenv(name);
            if (value == null || value.isBlank()) {
                throw new IllegalStateException(name + " is required");
            }
            return value;
        }

        private static String requiredPresent(String name) {
            String value = System.getenv(name);
            if (value == null) {
                throw new IllegalStateException(name + " must be explicitly supplied");
            }
            return value;
        }

        record Database(String name, DriverManagerDataSource dataSource, JdbcTemplate jdbc) {
        }
    }
}
