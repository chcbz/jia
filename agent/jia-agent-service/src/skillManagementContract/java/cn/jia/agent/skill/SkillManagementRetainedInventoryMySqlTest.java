package cn.jia.agent.skill;

import cn.jia.agent.dao.AgentRuntimeV1InstallationDao;
import cn.jia.agent.entity.AgentRuntimeV1InstallationEntity;
import cn.jia.agent.service.SkillCapabilityLookup;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static cn.jia.agent.skill.SkillManagementCodec.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Inherits the owned MySQL/real installation fixture. Select retained* methods; historical
 * superclass tests have separate evidence and are not implicitly counted as executed here. */
class SkillManagementRetainedInventoryMySqlTest extends SkillManagementTransactionTest {
    private static final String NEXT_RUNTIME="rti_"+"d".repeat(32);
    private Map<String,Object> original;
    private String installation;
    private Map<String,Object> originalAuthorization;
    private SkillRelationshipAuthorizations authorizations;

    private void retainedFixture(String owner) throws Exception {
        retainedFixture(owner, install());
    }
    private void retainedFixture(String owner, Map<String,Object> first) throws Exception {
        original=first;report(original,"PREPARED",null);report(current(original),"APPLIED",null);original=current(original);catalog(original);
        installation=text(object(original.get("target")),"skillInstallationId");
        originalAuthorization=jdbc.queryForMap("SELECT * FROM agent_skill_installation_authorization WHERE installation_id=?",installation);
        // Use the real component DDL, not a permissive surrogate authorization table.
        String schema=null;
        for(var resource:new PathMatchingResourcePatternResolver().getResources("classpath*:db/schema.sql")) {
            String candidate=resource.getContentAsString(StandardCharsets.UTF_8);
            if(candidate.contains("CREATE TABLE IF NOT EXISTS agent_runtime (")){schema=candidate;break;}
        }
        assertNotNull(schema);
        for(String table:List.of("agent_runtime","agent_persona_binding")) {
            int start=schema.indexOf("CREATE TABLE IF NOT EXISTS "+table+" (");jdbc.execute(schema.substring(start,schema.indexOf(";\n",start)+1));
        }
        new ResourceDatabasePopulator(new ClassPathResource("db/agent-runtime-session-fence-v1.sql"),
                new ClassPathResource("db/economy-v0-hosting-rent.sql")).execute(Objects.requireNonNull(jdbc.getDataSource()));
        jdbc.update("INSERT INTO agent_persona_binding(id,jiacn,persona_code,agent_id,bound_at,status,tenant_id,client_id) VALUES(8,?,'linchong',?,1,1,'0',?)",owner,AGENT,ACTOR.clientId());
        jdbc.update("INSERT INTO agent_runtime(agent_id,name,owner_jiacn,binding_id,tenant_id,client_id,runtime_installation_id,runtime_host_id) VALUES(?,'retained',?,8,'0',?,?,'host-1')",AGENT,owner,ACTOR.clientId(),NEXT_RUNTIME);
        var ref=new LinkedHashMap<>(object(original.get("target")));ref.remove("byteLength");
        String snapshot=json(List.of(map("skillKey",original.get("skillKey"),"source","MANAGED","revision","a".repeat(64),"enabled",true,"installation",ref)));
        jdbc.update("""
            INSERT INTO economy_hosting_rent_quote(quote_id,quote_purpose,plan_id,plan_version,amount_wen,recurring_amount_wen,
              initial_skill_count,inventory_sha256,inventory_host_id,inventory_snapshot_json,period_seconds,principal_type,principal_id,
              persona_code,agent_id,idempotency_key,request_hash,expires_at,tenant_id,client_id,create_time)
            VALUES('next-quote','INITIAL','plan',1,550,500,1,?,'host-1',?,2592000,'USER','buyer','linchong',?,'quote-key',UNHEX(REPEAT('00',32)),9999999999999,'0',?,1)
            ""","a".repeat(64),snapshot,AGENT,ACTOR.clientId());
        jdbc.update("""
            INSERT INTO economy_hosting_lease(lease_id,principal_type,principal_id,persona_code,agent_id,binding_id,plan_id,plan_version,
              amount_wen,period_seconds,status,latest_intent_id,version,tenant_id,client_id,create_time,update_time)
            VALUES('next-lease','USER','buyer','linchong',?,'8','plan',1,500,2592000,'ACTIVE','next-intent',1,'0',?,1,1)
            """,AGENT,ACTOR.clientId());
        jdbc.update("""
            INSERT INTO economy_hosting_provisioning_intent(intent_id,lease_id,quote_id,quote_purpose,principal_type,principal_id,persona_code,
              agent_id,amount_wen,period_seconds,status,reserve_idempotency_key,reserve_request_hash,reserve_transaction_id,reserved_at,
              escrow_version,capture_transaction_id,captured_at,version,tenant_id,client_id,create_time,update_time)
            VALUES('next-intent','next-lease','next-quote','INITIAL','USER','buyer','linchong',?,550,2592000,'ACTIVE','reserve-key',
              UNHEX(REPEAT('00',32)),'reserve-next',1,1,'capture-next',2,3,'0',?,1,2)
            """,AGENT,ACTOR.clientId());
        authorizations=new SkillRelationshipAuthorizations(db,true);service.setRelationshipAuthorizations(authorizations);
        runtime.setOwnerJiacn(owner).setBindingId(8L).setRuntimeInstallationId(NEXT_RUNTIME);
        var installations=(AgentRuntimeV1InstallationDao)ReflectionTestUtils.getField(service,"installations");
        var current=new AgentRuntimeV1InstallationEntity().setInstallationId(NEXT_RUNTIME).setCanonicalAgentId(AGENT).setStatus("ACTIVE");
        current.setTenantId("0");current.setClientId(ACTOR.clientId());when(installations.lock(NEXT_RUNTIME)).thenReturn(current);
    }
    private SkillCapabilityLookup.Scope scope(String owner){return new SkillCapabilityLookup.Scope("0",ACTOR.clientId(),owner,AGENT);}
    @Override protected cn.jia.agent.security.AgentRuntimeAuthenticationService.Proof proof() {
        return new cn.jia.agent.security.AgentRuntimeAuthenticationService.Proof(
                new cn.jia.agent.security.AgentRuntimeAuthentication.Scope("0",ACTOR.clientId(),runtime.getOwnerJiacn(),AGENT,"runtime-1"),
                runtime.getRuntimeInstallationId(),"host-1",1L,"b".repeat(64),1L,1L);
    }
    private void assertHistoryUnchanged(){assertEquals(originalAuthorization,jdbc.queryForMap("SELECT * FROM agent_skill_installation_authorization WHERE installation_id=?",installation));}

    @Test void retainedNewOwnerGetsDescriptorsWithoutReinstallingOrRewritingOldAuthorization() throws Exception {
        retainedFixture("new-owner");
        assertNull(authorizations.read(scope(ACTOR.ownerJiacn())));
        var relation=authorizations.read(scope("new-owner"));assertNotNull(relation);assertEquals(8,relation.bindingId());
        var descriptors=service.descriptors(scope("new-owner"));assertEquals(1,descriptors.size());
        assertEquals(NEXT_RUNTIME,descriptors.getFirst().runtimeInstallationId());assertEquals(installation,descriptors.getFirst().installationId());
        assertEquals(1,count("agent_skill_installation"));assertEquals(1,count("economy_skill_order"));assertHistoryUnchanged();
    }
    @Test void retainedGrantRequiresCapturedInitialAndExactCurrentRelationship() throws Exception {
        retainedFixture("new-owner");
        jdbc.update("UPDATE economy_hosting_provisioning_intent SET status='FUNDS_RESERVED',capture_transaction_id=NULL WHERE intent_id='next-intent'");
        assertTrue(service.descriptors(scope("new-owner")).isEmpty());
        jdbc.update("UPDATE economy_hosting_provisioning_intent SET status='ACTIVE',capture_transaction_id='capture-next' WHERE intent_id='next-intent'");
        assertEquals(1,service.descriptors(scope("new-owner")).size());
        jdbc.update("UPDATE economy_hosting_lease SET status='LOCKED' WHERE lease_id='next-lease'");assertNotNull(authorizations.read(scope("new-owner")));
        jdbc.update("UPDATE agent_persona_binding SET status=0 WHERE id=8");assertNull(authorizations.read(scope("new-owner")));
        assertTrue(service.descriptors(scope("new-owner")).isEmpty());assertHistoryUnchanged();
    }
    @Test void retainedNativeAdmissionAndPolicyUseNewRuntimeWhileDisabledSkillStaysClosed() throws Exception {
        retainedFixture(ACTOR.ownerJiacn());catalog(current(original));
        assertEquals("admitted",service.admitNative(scope(ACTOR.ownerJiacn()),text(original,"skillKey"),installation,()->"admitted"));
        var disabled=policyChange("DISABLE",current(original));
        assertEquals("SUCCEEDED",disabled.get("status"));
        assertEquals(NEXT_RUNTIME,db.findChange("0",ACTOR.clientId(),text(disabled,"skillChangeId")).getRuntimeInstallationId());
        assertThrows(SkillMarketplaceException.class,()->service.admitNative(scope(ACTOR.ownerJiacn()),text(original,"skillKey"),installation,()->"must-not-admit"));
        assertHistoryUnchanged();
    }
    @Test void retainedDifferentBuyerCanAdmitAndDisableButPredecessorCannot() throws Exception {
        retainedFixture("new-owner");
        var next=nextBuyer();
        catalog(original);
        assertThrows(SkillMarketplaceException.class,()->current(original));
        assertEquals("admitted",service.admitNative(scope(next.ownerJiacn()),text(original,"skillKey"),installation,()->"admitted"));
        assertThrows(SkillMarketplaceException.class,()->service.admitNative(scope(ACTOR.ownerJiacn()),text(original,"skillKey"),installation,()->"wrong-owner"));
        var installed=original;long before=wallet();
        var disabled=service.create(next,AGENT,"new-owner-disable","request",map("action","DISABLE","skillKey",installed.get("skillKey"),
                "sourceSkillInstallationId",installation,"expectedAgentVersion",Long.toString(versions.readOwned(next,AGENT)),
                "expectedSkillRevision",installed.get("skillRevision")),false);
        var change=db.findChange("0",ACTOR.clientId(),text(disabled,"skillChangeId"));
        assertEquals(next.actorId(),change.getActorId());assertEquals(next.ownerJiacn(),change.getOwnerJiacn());
        assertEquals(NEXT_RUNTIME,change.getRuntimeInstallationId());assertEquals(before,wallet());
        assertThrows(SkillMarketplaceException.class,()->service.admitNative(scope(next.ownerJiacn()),text(original,"skillKey"),installation,()->"disabled"));
        assertHistoryUnchanged();
    }

    private cn.jia.agent.hosting.HostingRentHttp.Actor nextBuyer() {
        var next=new cn.jia.agent.hosting.HostingRentHttp.Actor("new-buyer","0",ACTOR.clientId(),"new-owner");
        for(String table:List.of("economy_hosting_rent_quote","economy_hosting_lease","economy_hosting_provisioning_intent"))
            jdbc.update("UPDATE "+table+" SET principal_id='new-buyer'");
        // Only the external authentication/Agent ownership adapters are synthetic; installation,
        // paid inventory lookup, policy state and immutable provenance remain real MySQL.
        var owners=(cn.jia.agent.hosting.HostingRentOwnerResolver)ReflectionTestUtils.getField(versions,"owners");
        doReturn(next.ownerJiacn()).when(owners).requireOwner(next);
        @SuppressWarnings("unchecked") var agents=(org.springframework.beans.factory.ObjectProvider<cn.jia.agent.service.AgentService>)ReflectionTestUtils.getField(versions,"agents");
        var dto=new cn.jia.agent.entity.AgentRuntimeDTO();dto.setAgentId(AGENT);dto.setStatus("online");dto.setSystemAgent(false);
        when(agents.getObject().requireApiKeyOwnedAgentForUpdate(next.clientId(),next.ownerJiacn(),AGENT)).thenReturn(dto);
        when(agents.getObject().requireOwnedAgentCapabilities(next.tenantId(),next.clientId(),next.ownerJiacn(),AGENT)).thenReturn(dto);
        return next;
    }

    @Test void retainedDifferentBuyerUpgradeChargesNewWalletOnceAndKeepsOriginalProvenance() throws Exception {
        publishInstruction("1.0.0",1);
        retainedFixture("new-owner",packageChange("INSTALL",null,"0","spv_pdf_1"));
        var next=nextBuyer();catalog(original);
        sql.getMapper(cn.jia.economy.mapper.EconomyLedgerMapper.class).insertAccountIfAbsent(
                new cn.jia.economy.entity.EconomyAccountEntity().setAccountId("next-wallet").setOwnerType("USER")
                        .setOwnerId(next.actorId()).setPurpose("AVAILABLE").setCurrency("WEN").setBalanceWen(100L)
                        .setAllowNegative(0).setStatus("ACTIVE").setVersion(0L).setTenantId("0")
                        .setClientId(ACTOR.clientId()).setCreateTime(1L).setUpdateTime(1L));
        long previousOwnerBalance=wallet();
        publishInstruction("2.0.0",2);
        jdbc.update("INSERT INTO agent_skill_upgrade_policy VALUES (?,?,?,?,?)","0",ACTOR.clientId(),"spv_pdf_1","spv_pdf_2","PAID");
        var input=map("action","UPGRADE","agentId",AGENT,"skillKey","pdf-export","sourceSkillInstallationId",installation,
                "targetProductVersionId","spv_pdf_2","expectedAgentVersion",Long.toString(versions.readOwned(next,AGENT)),
                "expectedSkillRevision",original.get("skillRevision"),"approvedPermissions",List.of());
        var quote=service.quote(next,"next-upgrade-quote",input,false);
        assertEquals("20",object(quote.get("price")).get("amountMinor"));
        var request=creation(quote,input);
        var change=service.create(next,AGENT,"next-upgrade-create","request",request,false);
        assertEquals(text(change,"skillChangeId"),text(service.create(next,AGENT,"next-upgrade-create","request",request,false),"skillChangeId"));
        assertEquals(80L,jdbc.queryForObject("SELECT balance_wen FROM economy_account WHERE account_id='next-wallet'",Long.class));
        assertEquals(previousOwnerBalance,wallet());assertEquals("HELD",change.get("fundingStatus"));
        assertEquals(NEXT_RUNTIME,db.findChange("0",ACTOR.clientId(),text(change,"skillChangeId")).getRuntimeInstallationId());
        report(change,"PREPARED",null);
        change=service.change(next,AGENT,text(change,"skillChangeId"));
        var applied=result(change,evidence(change,"APPLIED",null));
        service.result(proof(),applied);service.result(proof(),applied);
        change=service.change(next,AGENT,text(change,"skillChangeId"));catalog(change);
        change=service.change(next,AGENT,text(change,"skillChangeId"));
        assertEquals("SUCCEEDED",change.get("status"));assertEquals("CAPTURED",change.get("fundingStatus"));
        assertEquals(80L,jdbc.queryForObject("SELECT balance_wen FROM economy_account WHERE account_id='next-wallet'",Long.class));
        assertEquals(previousOwnerBalance,wallet());assertEquals(2,count("economy_skill_order"));
        String nextInstallation=text(object(change.get("target")),"skillInstallationId");assertNotEquals(installation,nextInstallation);
        assertEquals("admitted",service.admitNative(scope(next.ownerJiacn()),"pdf-export",nextInstallation,()->"admitted"));
        assertThrows(SkillMarketplaceException.class,()->service.admitNative(scope(ACTOR.ownerJiacn()),"pdf-export",nextInstallation,()->"old-owner"));
        assertHistoryUnchanged();
    }

}
