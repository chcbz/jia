package cn.jia.test;

import cn.jia.JiaTestApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.bean.override.mockito.MockitoResetTestExecutionListener;
import org.springframework.test.context.jdbc.SqlScriptsTestExecutionListener;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.test.context.support.DirtiesContextTestExecutionListener;
import org.springframework.test.context.transaction.TransactionalTestExecutionListener;
import org.springframework.transaction.annotation.Transactional;

/**
 * @author chc
 */
@ActiveProfiles("test")
@SpringBootTest(classes = {JiaTestApplication.class})
@TestExecutionListeners({
        DirtiesContextTestExecutionListener.class,
        TransactionalTestExecutionListener.class,
        SqlScriptsTestExecutionListener.class,
        MockitoResetTestExecutionListener.class,
        DependencyInjectionTestExecutionListener.class})
@Transactional(rollbackFor = Exception.class)
public class BaseDbUnitTest {
}