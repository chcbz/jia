package cn.jia.agent.dao;

import cn.jia.agent.dao.impl.*;
import cn.jia.agent.entity.*;
import cn.jia.agent.mapper.*;
import org.apache.ibatis.annotations.Insert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentRuntimeV2RegistrationDaoTest {
    private static final String UUID = "66666666-6666-4666-8666-666666666666";
    private static final String REQUEST = "11111111-1111-4111-8111-111111111111";
    private static final String RECEIPT = "33333333-3333-4333-8333-333333333333";
    private static final String BINDING = "44444444-4444-4444-8444-444444444444";
    private static final String FACT = "55555555-5555-4555-8555-555555555555";
    private static final String INSTANCE = "22222222-2222-4222-8222-222222222222";
    private static final String CHALLENGE_REQUEST = "77777777-7777-4777-8777-777777777777";
    private static final String CHALLENGE = "88888888-8888-4888-8888-888888888888";

    @AfterEach void clear() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    }

    @Test void transactionRequiredBeforeAnyMapperCall() {
        var am=mock(AgentRuntimeV2RegistrationAuthorityMapper.class); var rm=mock(AgentRuntimeV2RegistrationReceiptMapper.class);
        var qm=mock(AgentRuntimeV2RegistrationRequestMapper.class); var cm=mock(AgentRuntimeV2ReconnectChallengeMapper.class);
        assertThrows(IllegalStateException.class,()->new AgentRuntimeV2RegistrationAuthorityDaoImpl(am).append(authority()));
        assertThrows(IllegalStateException.class,()->new AgentRuntimeV2RegistrationAuthorityDaoImpl(am).lockByStableId(UUID));
        assertThrows(IllegalStateException.class,()->new AgentRuntimeV2RegistrationReceiptDaoImpl(rm).append(receipt()));
        assertThrows(IllegalStateException.class,()->new AgentRuntimeV2RegistrationRequestDaoImpl(qm).append(grantedRequest()));
        assertThrows(IllegalStateException.class,()->new AgentRuntimeV2ReconnectChallengeDaoImpl(cm).lockByChallengeStableId(CHALLENGE));
        verifyNoInteractions(am,rm,qm,cm);
    }

    @Test void appendNeedsWritableTransactionButLocksAcceptReadTransaction() {
        var mapper=mock(AgentRuntimeV2RegistrationAuthorityMapper.class); var dao=new AgentRuntimeV2RegistrationAuthorityDaoImpl(mapper);
        begin(true); assertThrows(IllegalStateException.class,()->dao.append(authority())); verifyNoInteractions(mapper);
        assertNull(dao.lockByStableId(UUID)); verify(mapper).lockById(UUID);
    }

    @Test void everyCanonicalAppendReachesExactlyOneMapperInsert() {
        begin(false);
        var am=mock(AgentRuntimeV2RegistrationAuthorityMapper.class); when(am.insert(any())).thenReturn(1);
        var rm=mock(AgentRuntimeV2RegistrationReceiptMapper.class); when(rm.insert(any())).thenReturn(1);
        var qm=mock(AgentRuntimeV2RegistrationRequestMapper.class); when(qm.insert(any())).thenReturn(1);
        var cm=mock(AgentRuntimeV2ReconnectChallengeMapper.class); when(cm.insert(any())).thenReturn(1);
        assertEquals(1,new AgentRuntimeV2RegistrationAuthorityDaoImpl(am).append(authority()));
        assertEquals(1,new AgentRuntimeV2RegistrationReceiptDaoImpl(rm).append(receipt()));
        assertEquals(1,new AgentRuntimeV2RegistrationRequestDaoImpl(qm).append(grantedRequest()));
        assertEquals(1,new AgentRuntimeV2RegistrationRequestDaoImpl(qm).append(deniedRequest()));
        assertEquals(1,new AgentRuntimeV2ReconnectChallengeDaoImpl(cm).append(challenge()));
        verify(am).insert(any()); verify(rm).insert(any()); verify(qm,times(2)).insert(any()); verify(cm).insert(any());
    }

    @Test void legalReceiptAndChallengeWireWithOneWrongProjectionHaveZeroMapperCalls() {
        begin(false);
        var rm=mock(AgentRuntimeV2RegistrationReceiptMapper.class); var receipt=receipt().setOwnerJiacn("other-owner");
        assertThrows(IllegalArgumentException.class,()->new AgentRuntimeV2RegistrationReceiptDaoImpl(rm).append(receipt));
        verifyNoInteractions(rm);
        var cm=mock(AgentRuntimeV2ReconnectChallengeMapper.class); var challenge=challenge().setExpiresAt(2001L);
        assertThrows(IllegalArgumentException.class,()->new AgentRuntimeV2ReconnectChallengeDaoImpl(cm).append(challenge));
        verifyNoInteractions(cm);
    }

    @Test void requestUuidHashAndGrantedDeniedShapesFailBeforeMapper() {
        begin(false); var mapper=mock(AgentRuntimeV2RegistrationRequestMapper.class); var dao=new AgentRuntimeV2RegistrationRequestDaoImpl(mapper);
        var badId=grantedRequest().setRegistrationRequestId("not-a-uuid"); assertThrows(IllegalArgumentException.class,()->dao.append(badId));
        var badHash=grantedRequest().setRegistrationReceiptSha256(new byte[31]); assertThrows(IllegalArgumentException.class,()->dao.append(badHash));
        var partialGrant=grantedRequest().setRegistrationReceiptId(null); assertThrows(IllegalArgumentException.class,()->dao.append(partialGrant));
        var deniedWithReceipt=deniedRequest().setRegistrationReceiptId(RECEIPT).setRegistrationReceiptSha256(new byte[32]); assertThrows(IllegalArgumentException.class,()->dao.append(deniedWithReceipt));
        var deniedWithoutCode=deniedRequest().setDenialCode(null); assertThrows(IllegalArgumentException.class,()->dao.append(deniedWithoutCode));
        var partialBinding=deniedRequest().setRuntimeBindingId(BINDING).setBindingGeneration(null); assertThrows(IllegalArgumentException.class,()->dao.append(partialBinding));
        verifyNoInteractions(mapper);
    }

    @Test void bindingInsertLeavesGeneratedBusinessColumnsToTheDatabase() {
        assertFalse(AgentRuntimeV2BindingMapper.COLUMNS.contains("live_registration_subject_sha256"));
        assertFalse(AgentRuntimeV2BindingMapper.COLUMNS.contains("live_business_subject"));
        assertFalse(AgentRuntimeV2BindingMapper.VALUES.contains("liveRegistrationSubjectSha256"));
        assertFalse(AgentRuntimeV2BindingMapper.VALUES.contains("liveBusinessSubject"));

        var bindingMapper = mock(AgentRuntimeV2BindingMapper.class);
        assertThrows(IllegalArgumentException.class, () ->
                new AgentRuntimeV2BindingDaoImpl(bindingMapper).insertTrusted(null,
                        new AgentRuntimeV2BindingEntity().setLiveBusinessSubject("forged")));
        verifyNoInteractions(bindingMapper);

        begin(false);
        var factMapper = mock(AgentRuntimeV2BindingGenerationFactMapper.class);
        var bindings = mock(AgentRuntimeV2BindingDao.class);
        var session = new AuthenticatedRuntimeSession(BINDING, 0, INSTANCE,
                REQUEST, "socket-a", 0);
        var draft = new AgentRuntimeV2BindingEntity()
                .setLiveRegistrationSubjectSha256("forged").setSocketOwnerLeaseUntil(2L);
        assertThrows(IllegalArgumentException.class, () ->
                new AgentRuntimeV2BindingGenerationFactDaoImpl(bindingMapper, bindings, factMapper)
                        .createVersion2(null, draft, session, new byte[32], 1L));
        verifyNoInteractions(bindingMapper, bindings, factMapper);
    }

    @Test void generatedAuthorityColumnsAreRejectedAndAbsentFromInsert() throws Exception {
        begin(false); var mapper=mock(AgentRuntimeV2RegistrationAuthorityMapper.class); var dao=new AgentRuntimeV2RegistrationAuthorityDaoImpl(mapper);
        assertThrows(IllegalArgumentException.class,()->dao.append(authority().setActiveCredentialReference("forged"))); verifyNoInteractions(mapper);
        Method insert=AgentRuntimeV2RegistrationAuthorityMapper.class.getMethod("insert",AgentRuntimeV2RegistrationAuthorityEntity.class);
        String sql=String.join(" ",insert.getAnnotation(Insert.class).value());
        assertFalse(sql.contains("active_credential_reference")); assertFalse(sql.contains("active_business_subject"));
    }

    @Test void canonicalTenantUnicodeAndLengthsFailBeforeInsert() {
        begin(false); var mapper=mock(AgentRuntimeV2RegistrationAuthorityMapper.class); var dao=new AgentRuntimeV2RegistrationAuthorityDaoImpl(mapper);
        for(String tenant:new String[]{"00","+1"," 1","x"}){var e=authority();e.setTenantId(tenant);assertThrows(IllegalArgumentException.class,()->dao.append(e));}
        for(String ref:new String[]{"bad\ud800","bad\udc00"}){var e=authority().setCredentialReference(ref);assertThrows(IllegalArgumentException.class,()->dao.append(e));}
        verifyNoInteractions(mapper);
    }

    private static AgentRuntimeV2RegistrationReceiptEntity receipt(){
        byte[] process=bytes(32,1), verifier=bytes(32,2), server=spki(3), nonce=bytes(12,4), sealed=bytes(48,5);
        var wire=new AgentRuntimeV2RegistrationCodec.Receipt(RECEIPT,"CREATE",REQUEST,null,null,BINDING,0,FACT,INSTANCE,
                process,1,verifier,server,nonce,sealed,"1","cyf","pool-a","runtime-client","owner-a","agent-a",
                UUID,1,"api-a",1,1,1,"agent-command",1,1000);
        byte[] encoded=AgentRuntimeV2RegistrationCodec.encodeReceipt(wire);
        var e=new AgentRuntimeV2RegistrationReceiptEntity().setRegistrationReceiptId(RECEIPT).setReceiptKind("CREATE")
                .setRegistrationRequestId(REQUEST).setRuntimeBindingId(BINDING).setBindingGeneration(0L)
                .setBindingGenerationFactId(FACT).setRuntimeInstanceId(INSTANCE).setProcessPublicKeySha256(process)
                .setVerifierVersion(1L).setVerifierSha256(verifier).setVerifierCipherSuite(AgentRuntimeV2RegistrationCodec.CIPHER_SUITE)
                .setServerEphemeralPublicKeySpki(server).setNonce(nonce).setSealedVerifier(sealed).setDataDomainId("cyf")
                .setExecutionPoolId("pool-a").setRuntimeCredentialClientId("runtime-client").setOwnerJiacn("owner-a")
                .setAgentId("agent-a").setRegistrationAuthorityId(UUID).setRegistrationAuthorityRevision(1L)
                .setClaimantDeploymentId("api-a").setClaimantWorkerGeneration(1L).setReceiverRegistrationRevision(1L)
                .setAdmissionEpoch(1L).setModuleId("agent-command").setMigrationEpoch(1L).setConnectedAt(1000L)
                .setReceiptWireBytes(encoded).setReceiptWireSha256(AgentRuntimeV2RegistrationFingerprints.sha256(encoded)).setContractVersion(2);
        e.setTenantId("1");e.setClientId("runtime-client");return e;
    }
    private static AgentRuntimeV2ReconnectChallengeEntity challenge(){
        byte[] prior=bytes(32,6), server=spki(7), nonce=bytes(12,8), sealed=bytes(48,9), plain=bytes(32,10);
        var wire=new AgentRuntimeV2RegistrationCodec.Challenge(CHALLENGE_REQUEST,CHALLENGE,BINDING,0,INSTANCE,RECEIPT,prior,
                server,nonce,sealed,plain,2000); byte[] encoded=AgentRuntimeV2RegistrationCodec.encodeChallenge(wire);
        var e=new AgentRuntimeV2ReconnectChallengeEntity().setChallengeRequestId(CHALLENGE_REQUEST).setChallengeId(CHALLENGE)
                .setRequestWireSha256(bytes(32,11)).setSemanticSha256(bytes(32,12)).setRegistrationSubjectSha256(bytes(32,13))
                .setRegistrationAuthoritySha256(bytes(32,14)).setRegistrationAuthorityId(UUID).setRegistrationAuthorityRevision(1L)
                .setRuntimeBindingId(BINDING).setBindingGeneration(0L).setPriorRegistrationReceiptId(RECEIPT)
                .setPriorRegistrationReceiptSha256(prior).setRuntimeInstanceId(INSTANCE).setProcessPublicKeySha256(bytes(32,15))
                .setChallengeDigest(bytes(32,16)).setChallengeDigestKeyId("hmac-1").setChallengeCipherSuite(AgentRuntimeV2RegistrationCodec.CIPHER_SUITE)
                .setServerEphemeralPublicKeySpki(server).setNonce(nonce).setSealedChallenge(sealed).setChallengeSha256(plain)
                .setChallengeWireBytes(encoded).setChallengeWireSha256(AgentRuntimeV2RegistrationFingerprints.sha256(encoded))
                .setIssuerSocketOwnerInstanceId("socket-a").setIssuerSocketOwnerEpoch(0L)
                .setProvisionalSessionFence("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa").setIssuedAt(1000L).setExpiresAt(2000L)
                .setStatus("ISSUED").setContractVersion(2); e.setTenantId("1");e.setClientId("runtime-client");return e;
    }
    private static AgentRuntimeV2RegistrationAuthorityEntity authority(){var e=new AgentRuntimeV2RegistrationAuthorityEntity().setRegistrationAuthorityId(UUID).setCredentialKind("API_KEY").setCredentialReference("api-key-1").setCredentialDigest(new byte[32]).setCredentialKeyId("key-1").setOwnerJiacn("owner-a").setAgentId("agent-a").setDataDomainId("cyf").setExecutionPoolId("pool-a").setModuleId("agent-command").setClaimantDeploymentId("api-a").setAuthorityRevision(1L).setStatus("ACTIVE").setVersion(0L);e.setTenantId("1");e.setClientId("runtime-client");return e;}
    private static AgentRuntimeV2RegistrationRequestEntity grantedRequest(){return request().setDecision("GRANTED").setRegistrationReceiptId(RECEIPT).setRegistrationReceiptSha256(new byte[32]);}
    private static AgentRuntimeV2RegistrationRequestEntity deniedRequest(){return request().setDecision("DENIED").setRuntimeBindingId(null).setBindingGeneration(null).setDenialCode("NOT_AUTHORIZED");}
    private static AgentRuntimeV2RegistrationRequestEntity request(){var e=new AgentRuntimeV2RegistrationRequestEntity().setRegistrationRequestId(REQUEST).setRequestKind("CREATE").setRequestWireSha256(new byte[32]).setSemanticSha256(new byte[32]).setRegistrationSubjectSha256(new byte[32]).setRegistrationAuthoritySha256(new byte[32]).setRuntimeBindingId(BINDING).setBindingGeneration(0L).setRuntimeInstanceId(INSTANCE).setProcessPublicKeySha256(new byte[32]).setRegistrationAuthorityId(UUID).setRegistrationAuthorityRevision(1L).setDataDomainId("cyf").setExecutionPoolId("pool-a").setRuntimeCredentialClientId("runtime-client").setOwnerJiacn("owner-a").setAgentId("agent-a").setClaimantDeploymentId("api-a").setClaimantWorkerGeneration(1L).setReceiverRegistrationRevision(1L).setAdmissionEpoch(1L).setModuleId("agent-command").setMigrationEpoch(1L).setDecidedAt(1L).setContractVersion(2);e.setTenantId("1");e.setClientId("runtime-client");return e;}
    private static byte[] bytes(int size,int seed){byte[] value=new byte[size];Arrays.fill(value,(byte)seed);return value;}
    private static byte[] spki(int seed){byte[] value=bytes(44,seed);byte[] prefix=HexFormat.of().parseHex("302a300506032b656e032100");System.arraycopy(prefix,0,value,0,prefix.length);return value;}
    private static void begin(boolean readOnly){TransactionSynchronizationManager.initSynchronization();TransactionSynchronizationManager.setActualTransactionActive(true);TransactionSynchronizationManager.setCurrentTransactionReadOnly(readOnly);}
}
