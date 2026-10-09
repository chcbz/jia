package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.dao.AgentTaskProviderCostConsentDao;
import cn.jia.agent.dao.ControlledImageExecutionSourceV3Dao;
import cn.jia.agent.dao.ControlledImageIntentOperationGrantDao;
import cn.jia.agent.dao.PersonalWorkspaceExecutionDao;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.service.ControlledImageFollowupAuthorityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Proves operation-grant, consent, and late Chat writes share the root-first transaction. */
class ControlledImageIntentOperationGrantSpringTransactionTest {
    private JdbcTemplate evidence;
    private AgentTaskMutationTransaction transactions;

    @BeforeEach void setUp() {
        var dataSource=new DriverManagerDataSource(
                "jdbc:h2:mem:followup_authority_atomicity;MODE=MYSQL;DB_CLOSE_DELAY=-1","sa","");
        evidence=new JdbcTemplate(dataSource);
        evidence.execute("DROP TABLE IF EXISTS followup_atomic_evidence");
        evidence.execute("CREATE TABLE followup_atomic_evidence(label VARCHAR(40) PRIMARY KEY)");
        AgentTaskMetaDao roots=mock(AgentTaskMetaDao.class);
        var root=new AgentTaskMetaEntity().setTaskId("task").setTaskVersion(0L)
                .setCurrentEventVersion(0L).setOwnerJiacn("owner");
        root.setTenantId("0");root.setClientId("client");
        when(roots.findByTaskIdForUpdateInOwnerScope("0","client","owner","task")).thenReturn(root);
        transactions=new AgentTaskMutationTransactionImpl(roots,new DataSourceTransactionManager(dataSource));
    }

    @Test void lateChatFailureRollsBackBothAuthorityRows() {
        AgentTaskExecutionGrantService grants=mock(AgentTaskExecutionGrantService.class);
        AgentTaskProviderCostConsentDao consents=mock(AgentTaskProviderCostConsentDao.class);
        ControlledImageIntentOperationGrantDao operationGrants=mock(ControlledImageIntentOperationGrantDao.class);
        PersonalWorkspaceExecutionDao executions=mock(PersonalWorkspaceExecutionDao.class);
        ControlledImageExecutionSourceV3Dao sources=mock(ControlledImageExecutionSourceV3Dao.class);
        ControlledImageProviderOperatorPolicy policies=mock(ControlledImageProviderOperatorPolicy.class);
        @SuppressWarnings("unchecked") ObjectProvider<ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup> lookups=mock(ObjectProvider.class);
        @SuppressWarnings("unchecked") ObjectProvider<ControlledImageFollowupAuthorityService.RuntimeSourceAccessLookup> access=mock(ObjectProvider.class);
        var declaration=mock(ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup.class);
        when(lookups.getIfUnique()).thenReturn(declaration);
        when(declaration.current(any())).thenReturn(new ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup.Declaration(
                ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup.State.READY,"runtime",
                List.of("GENERATE_IMAGE","EDIT_IMAGE"),"CONTROLLED_IMAGE_HTTP_V1","binding",7L,
                "model",16,1,1));
        when(grants.admitFollowupBaseline(any(),eq("task"),eq("baseline"),eq(1L),eq(0L),eq(0L),eq(1L),eq("agent")))
                .thenReturn(new AgentTaskExecutionGrantService.Admission("baseline",1,0,"agent",null,
                        false,List.of(),null,null,0L,1L,"1".repeat(64),"assign-key","5".repeat(64)));
        var policy=new ControlledImageProviderOperatorPolicy.Policy("CONTROLLED_IMAGE_HTTP_V1","binding",7,
                "model","OPERATOR_TEMPLATE","operator","policy-r1","UNPRICED_EXTERNAL_ACCOUNT",1,
                Long.MAX_VALUE);
        when(policies.requireCurrent(any(),eq("agent"),eq("binding"),eq(7L),anyLong())).thenReturn(policy);
        doAnswer(invocation->{evidence.update("INSERT INTO followup_atomic_evidence VALUES ('consent')");return null;})
                .when(consents).insert(any());
        doAnswer(invocation->{evidence.update("INSERT INTO followup_atomic_evidence VALUES ('operation_grant')");return null;})
                .when(operationGrants).insert(any());
        var service=new ControlledImageFollowupAuthorityServiceImpl(transactions,grants,consents,
                operationGrants,executions,sources,policies,lookups,access,new ObjectMapper());
        var command=preview();
        var request=new ControlledImageFollowupAuthorityService.IssueCommand(command,"issue-key",
                "6".repeat(64),new ControlledImageFollowupAuthorityService.ProviderExpectation(
                "binding",7,"model","OPERATOR_TEMPLATE","policy-r1"),
                new ControlledImageFollowupAuthorityService.ExpectedPreview(command.ownerPayloadSha256(),
                        command.instructionSha256(),command.sourceSnapshotSha256(),"model",
                        "OPERATOR_TEMPLATE","policy-r1"),
                "UNPRICED_EXTERNAL_ACCOUNT_ONE_IMAGE_REQUEST_ATTEMPT");

        var failure=assertThrows(ControlledImageFollowupAuthorityService.Failure.class,()->service.issue(
                new ControlledImageFollowupAuthorityService.Scope("0","client","owner"),request,()->{
                    evidence.update("INSERT INTO followup_atomic_evidence VALUES ('chat_late_write')");
                    throw new IllegalStateException("late chat failure");
                }));

        assertEquals(ControlledImageFollowupAuthorityService.Reason.UNAVAILABLE,failure.reason());
        assertEquals(0,evidence.queryForObject("SELECT COUNT(*) FROM followup_atomic_evidence",Integer.class));
        verifyNoInteractions(executions,sources);
    }

    @Test void ordinaryAdmissionRequiresCallerTransactionAndRollsBackIssueReservationAndLateChatTogether() {
        for (int failAt : List.of(2,3,0)) {
            setUp();
            var manager=new DataSourceTransactionManager(evidence.getDataSource());
            var grants=mock(AgentTaskExecutionGrantService.class);
            var consents=mock(AgentTaskProviderCostConsentDao.class);
            var operations=mock(ControlledImageIntentOperationGrantDao.class);
            var executions=mock(PersonalWorkspaceExecutionDao.class);
            var sources=mock(ControlledImageExecutionSourceV3Dao.class);
            var policies=mock(ControlledImageProviderOperatorPolicy.class);
            @SuppressWarnings("unchecked") ObjectProvider<ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup> lookups=mock(ObjectProvider.class);
            @SuppressWarnings("unchecked") ObjectProvider<ControlledImageFollowupAuthorityService.RuntimeSourceAccessLookup> access=mock(ObjectProvider.class);
            var declaration=mock(ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup.class);
            when(lookups.getIfUnique()).thenReturn(declaration);
            when(declaration.current(any())).thenReturn(new ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup.Declaration(
                    ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup.State.READY,"runtime",
                    List.of("GENERATE_IMAGE","EDIT_IMAGE"),"CONTROLLED_IMAGE_HTTP_V1","binding",7L,"model",16,1,1));
            when(grants.admitFollowupBaseline(any(),eq("task"),eq("baseline"),eq(1L),eq(0L),eq(0L),eq(1L),eq("agent")))
                    .thenReturn(new AgentTaskExecutionGrantService.Admission("baseline",1,0,"agent",null,
                            false,List.of(),null,null,0L,1L,"1".repeat(64),"assign-key","5".repeat(64)));
            when(policies.requireCurrent(any(),eq("agent"),eq("binding"),eq(7L),anyLong())).thenReturn(
                    new ControlledImageProviderOperatorPolicy.Policy("CONTROLLED_IMAGE_HTTP_V1","binding",7,
                            "model","OPERATOR_TEMPLATE","operator","policy-r1","UNPRICED_EXTERNAL_ACCOUNT",1,Long.MAX_VALUE));
            var consent=new java.util.concurrent.atomic.AtomicReference<cn.jia.agent.entity.AgentTaskProviderCostConsentEntity>();
            var operation=new java.util.concurrent.atomic.AtomicReference<cn.jia.agent.entity.ControlledImageIntentOperationGrantEntity>();
            doAnswer(inv->{consent.set(inv.getArgument(0));evidence.update("INSERT INTO followup_atomic_evidence VALUES ('consent')");return null;}).when(consents).insert(any());
            doAnswer(inv->{operation.set(inv.getArgument(0));evidence.update("INSERT INTO followup_atomic_evidence VALUES ('operation')");return null;}).when(operations).insert(any());
            doAnswer(inv->{evidence.update("INSERT INTO followup_atomic_evidence VALUES ('execution')");return null;}).when(executions).insert(any());
            doAnswer(inv->consent.get()).when(consents).findFollowupByConsent(anyString(),anyString(),anyString(),anyString(),anyString());
            doAnswer(inv->consent.get()).when(consents).findFollowupByConsentForUpdate(anyString(),anyString(),anyString(),anyString(),anyString());
            doAnswer(inv->operation.get()).when(operations).lockById(anyString(),anyString(),anyString(),anyString(),anyString());
            when(operations.reserve(any(),eq(1L))).thenReturn(true);
            when(consents.bindFollowup(any(),eq(1L))).thenReturn(true);
            when(consents.reserveFollowup(any(),eq(2L))).thenReturn(true);
            var target=new ControlledImageFollowupAuthorityServiceImpl(transactions,grants,consents,operations,
                    executions,sources,policies,lookups,access,new ObjectMapper());
            var factory=new org.springframework.aop.framework.ProxyFactory(target);
            factory.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(manager,
                    new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
            var service=(ControlledImageFollowupAuthorityService)factory.getProxy();
            var command=new ControlledImageFollowupAuthorityService.OrdinaryActionCommand(preview(),"act_"+"a".repeat(40),
                    "parent-outcome","sha256:"+"b".repeat(64),901L,"c".repeat(64),"execution","run","6".repeat(64));
            var scope=new ControlledImageFollowupAuthorityService.Scope("0","client","owner");
            assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,
                    ()->service.admitOrdinaryAction(scope,command,()->{}));
            var callbacks=new java.util.concurrent.atomic.AtomicInteger();
            Runnable work=()->new org.springframework.transaction.support.TransactionTemplate(manager).execute(status ->
                    service.admitOrdinaryAction(scope,command,()->{
                        int call=callbacks.incrementAndGet();
                        if(call==2)assertEquals(2,evidence.queryForObject("SELECT COUNT(*) FROM followup_atomic_evidence",Integer.class));
                        if(call==3){
                            assertEquals(3,evidence.queryForObject("SELECT COUNT(*) FROM followup_atomic_evidence",Integer.class));
                            evidence.update("INSERT INTO followup_atomic_evidence VALUES ('chat_child')");
                        }
                        if(call==failAt)throw new IllegalStateException("injected-ordinary-late-"+failAt);
                    }));
            if(failAt>0){
                var failure=assertThrows(ControlledImageFollowupAuthorityService.Failure.class,work::run);
                assertNotNull(failure.getCause());assertEquals("injected-ordinary-late-"+failAt,failure.getCause().getMessage());
                assertEquals(failAt,callbacks.get());
                assertEquals(0,evidence.queryForObject("SELECT COUNT(*) FROM followup_atomic_evidence",Integer.class));
            }else{
                work.run();assertEquals(3,callbacks.get());
                assertEquals(4,evidence.queryForObject("SELECT COUNT(*) FROM followup_atomic_evidence",Integer.class));
                assertEquals("ORDINARY_ACTION",consent.get().getConsentPurpose());
            }
        }
    }

    private static ControlledImageFollowupAuthorityService.PreviewCommand preview() {
        return new ControlledImageFollowupAuthorityService.PreviewCommand("task","conversation",1,
                "interaction-key","request","step","intent",
                new ControlledImageFollowupAuthorityService.Baseline("baseline",1,0,0,1,
                        "1".repeat(64),"agent"),"GENERATE_IMAGE","draw","2".repeat(64),
                "3".repeat(64),"4".repeat(64),List.of());
    }
}
