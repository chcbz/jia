package cn.jia.chat.handler;

import cn.jia.agent.entity.*;
import cn.jia.agent.service.AgentRuntimeV2RegistrationService;
import cn.jia.agent.config.AgentRuntimeV2DispatchConfiguration;
import cn.jia.agent.config.AgentRuntimeV2DispatchSchemaGate;
import cn.jia.agent.dao.AgentRuntimeV2RegistrationStore;
import cn.jia.agent.service.AgentRuntimeV2CapabilityReadiness;
import cn.jia.agent.service.AgentRuntimeV2SocketGateway;
import cn.jia.chat.config.AgentRuntimeV2WebSocketConfiguration;
import cn.jia.chat.config.ApiKeyHandshakeInterceptor;
import cn.jia.agent.security.AgentRuntimeV2RegistrationAuthorityResolver;
import cn.jia.agent.security.AgentRuntimeV2RegistrationKeyRing;
import cn.jia.agent.service.AgentService;
import cn.jia.oauth.service.ApiKeyService;
import cn.jia.oauth.entity.OauthApiKeyEntity;
import cn.jia.core.context.EsContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.web.socket.*;
import java.security.KeyPairGenerator;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentRuntimeV2WebSocketAdapterTest {
    static <T> ObjectProvider<T> provider(T value){var provider=mock(ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(value);return provider;}
    static Map<AgentRuntimeV2Readiness.Dependency,BooleanSupplier> ready(){Map<AgentRuntimeV2Readiness.Dependency,BooleanSupplier> evidence=new EnumMap<>(AgentRuntimeV2Readiness.Dependency.class);for(var dependency:AgentRuntimeV2Readiness.Dependency.values())evidence.put(dependency,()->true);return evidence;}
    static WebSocketSession session(String id){var session=mock(WebSocketSession.class);when(session.getId()).thenReturn(id);Map<String,Object> attributes=new HashMap<>();attributes.put(AgentRuntimeV2WebSocketAdapter.IDENTITY_ATTRIBUTE,new AgentRuntimeV2RegistrationContext("API_KEY","key","agent","test-key",new byte[32]));when(session.getAttributes()).thenReturn(attributes);when(session.isOpen()).thenReturn(true);return session;}
    static byte[] createWire() throws Exception{return AgentRuntimeV2RegistrationCodec.encodeCreate(new AgentRuntimeV2RegistrationCodec.Create(UUID.randomUUID().toString(),UUID.randomUUID().toString(),KeyPairGenerator.getInstance("X25519").generateKeyPair().getPublic().getEncoded()));}
    @Test void everyReadinessDependencyMissingAdvertisesOnlyOneAndGenericUnavailableNoServiceWrites() throws Exception {
        for(var missing:AgentRuntimeV2Readiness.Dependency.values()){var map=ready();map.remove(missing);var readiness=new AgentRuntimeV2Readiness(map);var service=mock(AgentRuntimeV2RegistrationService.class);var adapter=new AgentRuntimeV2WebSocketAdapter(provider(service),provider(readiness));var session=session("socket");adapter.established(session);assertArrayEquals(new int[]{1},adapter.supportedProtocolVersions());var responses=new ArrayList<TextMessage>();assertTrue(adapter.intercept(session,createWire(),responses::add));assertEquals("UNAVAILABLE",AgentRuntimeV2RegistrationCodec.decodeRejected(responses.getFirst().asBytes()).reasonCode());verifyNoInteractions(service);}
    }
    @Test void productionActivationFlagAloneCannotSupplyMissingNodeOrFutureRuntimeEvidence() {
        new ApplicationContextRunner().withUserConfiguration(AgentRuntimeV2WebSocketConfiguration.class).withPropertyValues("agent.runtime-v2-registration.activation-enabled=true").run(context->{assertNull(context.getStartupFailure());assertFalse(context.getBean(AgentRuntimeV2Readiness.class).ready());assertArrayEquals(new int[]{1},context.getBean(AgentRuntimeV2Readiness.class).supportedProtocolVersions());});
    }
    @Test void productionDispatchEvidenceReachesBothAggregatorsButAloneCannotReadyPollOrSend() throws Exception {
        var gate=mock(AgentRuntimeV2DispatchSchemaGate.class);when(gate.verified()).thenReturn(true);var gateway=mock(AgentRuntimeV2SocketGateway.class);var store=mock(AgentRuntimeV2RegistrationStore.class);var keys=mock(AgentRuntimeV2RegistrationKeyRing.class);var tx=mock(PlatformTransactionManager.class);
        try(var context=new AnnotationConfigApplicationContext()){
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("isolated",Map.of("agent.runtime-v2-dispatch.enabled","true","agent.runtime-v2-dispatch.scheduler-enabled","true","agent.runtime-v2-registration.activation-enabled","true")));
            context.registerBean(JdbcTemplate.class,()->mock(JdbcTemplate.class));context.registerBean(PlatformTransactionManager.class,()->tx);context.registerBean(AgentRuntimeV2RegistrationStore.class,()->store);context.registerBean(AgentRuntimeV2RegistrationKeyRing.class,()->keys);context.registerBean("agentRuntimeV2SocketGateway",AgentRuntimeV2SocketGateway.class,()->gateway);
            context.registerBean(AgentRuntimeV2WebSocketAdapter.class,()->new AgentRuntimeV2WebSocketAdapter(context.getBeanProvider(AgentRuntimeV2RegistrationService.class),context.getBeanProvider(AgentRuntimeV2Readiness.class)));
            context.register(AgentRuntimeV2DispatchConfiguration.class,AgentRuntimeV2WebSocketConfiguration.class);
            // Only isolate external catalog/process evidence; all production dispatch and aggregate factories run.
            context.addBeanFactoryPostProcessor(factory->{((AbstractBeanDefinition)factory.getBeanDefinition("agentRuntimeV2DispatchSchemaGate")).setInstanceSupplier(()->gate);((AbstractBeanDefinition)factory.getBeanDefinition("agentRuntimeV2CompositeWorkerProof")).setInstanceSupplier(()->mock(AgentRuntimeV2CompositeWorkerProof.class));});
            context.refresh();
            var capability=context.getBean("agentRuntimeV2DispatchCapability",AgentRuntimeV2CapabilityReadiness.class);assertEquals(List.of(capability),context.getBeanProvider(AgentRuntimeV2CapabilityReadiness.class).orderedStream().toList());assertEquals(Set.of(AgentRuntimeV2CapabilityReadiness.Capability.RECEIVER_DISPATCH),capability.verifiedCapabilities());
            var scheduler=context.getBean(AgentRuntimeV2DispatchConfiguration.AgentRuntimeV2DispatchScheduler.class);var providers=(ObjectProvider<?>)Objects.requireNonNull(ReflectionTestUtils.getField(scheduler,"capabilities"));assertEquals(List.of(capability),providers.orderedStream().toList());
            var readiness=context.getBean(AgentRuntimeV2Readiness.class);
            @SuppressWarnings("unchecked") var evidence=(Map<AgentRuntimeV2Readiness.Dependency,BooleanSupplier>)Objects.requireNonNull(ReflectionTestUtils.getField(readiness,"evidence"));
            assertTrue(evidence.get(AgentRuntimeV2Readiness.Dependency.RECEIVER_DISPATCH).getAsBoolean());
            for(var missing:List.of(AgentRuntimeV2Readiness.Dependency.EXECUTION_HOLDER_TRANSFER,AgentRuntimeV2Readiness.Dependency.NODE_INTEROPERABILITY,AgentRuntimeV2Readiness.Dependency.CONSUMER_TOPOLOGY))assertFalse(evidence.get(missing).getAsBoolean());
            assertFalse(readiness.ready());assertArrayEquals(new int[]{1},readiness.supportedProtocolVersions());scheduler.poll();
            var adapter=context.getBean(AgentRuntimeV2WebSocketAdapter.class);assertArrayEquals(new int[]{1},adapter.supportedProtocolVersions());var socket=session("dispatch-only");adapter.established(socket);var responses=new ArrayList<TextMessage>();assertTrue(adapter.intercept(socket,createWire(),responses::add));assertEquals("UNAVAILABLE",AgentRuntimeV2RegistrationCodec.decodeRejected(responses.getFirst().asBytes()).reasonCode());verifyNoInteractions(gateway,store,keys,tx);
            when(gate.verified()).thenReturn(false);assertFalse(evidence.get(AgentRuntimeV2Readiness.Dependency.RECEIVER_DISPATCH).getAsBoolean());scheduler.poll();verifyNoInteractions(gateway,store,keys,tx);
        }
    }
    @Test void originalEnteredHandshakeSecretCreatesTypedProofAndNoPlaintextAttribute() throws Exception {
        String entered="test-only-entered-key",current="test-only-new-database-key";var keys=new AgentRuntimeV2RegistrationKeyRing("test-key",Map.of("test-key",new SecretKeySpec(new byte[32],"HmacSHA256")),Set.of());var resolver=new AgentRuntimeV2RegistrationAuthorityResolver(null,keys,java.time.Clock.systemUTC());
        var apiKeys=mock(ApiKeyService.class);var agents=mock(AgentService.class);var entity=new OauthApiKeyEntity();entity.setId("managed-1");entity.setClientId("client");entity.setJiacn("owner");entity.setTenantId("1");entity.setStatus(1);entity.setApiKey(current);when(apiKeys.findByApiKey(entered)).thenReturn(entity);
        var interceptor=new ApiKeyHandshakeInterceptor(provider(apiKeys),provider(agents));interceptor.setRegistrationProofs(resolver);var request=mock(ServerHttpRequest.class);var response=mock(ServerHttpResponse.class);var headers=new HttpHeaders();headers.set("X-API-Key",entered);headers.set("X-Agent-Id","agent");when(request.getHeaders()).thenReturn(headers);when(request.getURI()).thenReturn(java.net.URI.create("ws://test.invalid/agent"));Map<String,Object> attributes=new HashMap<>();
        try {assertTrue(interceptor.beforeHandshake(request,response,mock(WebSocketHandler.class),attributes));var proof=(AgentRuntimeV2RegistrationContext)attributes.get(AgentRuntimeV2WebSocketAdapter.IDENTITY_ATTRIBUTE);assertEquals("API_KEY",proof.credentialKind());assertArrayEquals(AgentRuntimeV2RegistrationFingerprints.apiKeyCredential(keys.active(),"managed-1",entered),proof.credentialProof());assertFalse(Arrays.equals(AgentRuntimeV2RegistrationFingerprints.apiKeyCredential(keys.active(),"managed-1",current),proof.credentialProof()));assertFalse(attributes.values().contains(entered));assertFalse(attributes.values().contains(current));assertFalse(attributes.containsKey("apiKey"));verify(agents).requireApiKeyOwnedAgent("client","owner","agent");}finally{EsContextHolder.clearContext();}
    }
    @Test void failingReadinessSupplierAndProviderAdvertiseOnlyOneAndStrictUnavailable() throws Exception {
        for(var dependency:AgentRuntimeV2Readiness.Dependency.values()){var evidence=ready();evidence.put(dependency,()->{throw new IllegalStateException("test unavailable dependency");});var service=mock(AgentRuntimeV2RegistrationService.class);var adapter=new AgentRuntimeV2WebSocketAdapter(provider(service),provider(new AgentRuntimeV2Readiness(evidence)));var session=session("failing-check");adapter.established(session);assertArrayEquals(new int[]{1},adapter.supportedProtocolVersions());adapter.intercept(session,createWire(),message->assertEquals("UNAVAILABLE",AgentRuntimeV2RegistrationCodec.decodeRejected(message.asBytes()).reasonCode()));adapter.maintainRouting();verifyNoInteractions(service);}
        var service=mock(AgentRuntimeV2RegistrationService.class);ObjectProvider<AgentRuntimeV2Readiness> unavailable=mock(ObjectProvider.class);when(unavailable.getIfAvailable()).thenThrow(new IllegalStateException("test failed provider"));var adapter=new AgentRuntimeV2WebSocketAdapter(provider(service),unavailable);assertArrayEquals(new int[]{1},adapter.supportedProtocolVersions());adapter.intercept(session("provider"),createWire(),message->assertEquals("UNAVAILABLE",AgentRuntimeV2RegistrationCodec.decodeRejected(message.asBytes()).reasonCode()));verifyNoInteractions(service);
    }
    @Test void invalidRawDuplicateReorderBomAndUtf8AreNeverNormalizedOrPassedToService() throws Exception {
        var service=mock(AgentRuntimeV2RegistrationService.class);var adapter=new AgentRuntimeV2WebSocketAdapter(provider(service),provider(new AgentRuntimeV2Readiness(ready())));var session=session("socket");adapter.established(session);byte[] valid=createWire();String text=new String(valid,java.nio.charset.StandardCharsets.UTF_8);List<byte[]> invalid=new ArrayList<>();invalid.add((" "+text).getBytes(java.nio.charset.StandardCharsets.UTF_8));invalid.add(text.replace("\"schemaVersion\":2,","\"schemaVersion\":2,\"schemaVersion\":2,").getBytes(java.nio.charset.StandardCharsets.UTF_8));invalid.add(("\ufeff"+text).getBytes(java.nio.charset.StandardCharsets.UTF_8));invalid.add(text.replace("\"schemaVersion\":2","\"schemaVersion\":\"2\"").getBytes(java.nio.charset.StandardCharsets.UTF_8));byte[] bad=valid.clone();bad[bad.length-2]=(byte)0xff;invalid.add(bad);
        for(byte[] wire:invalid)assertTrue(adapter.intercept(session,wire,response->fail("must close malformed protocol")));verifyNoInteractions(service);verify(session,times(invalid.size())).close(CloseStatus.PROTOCOL_ERROR);assertFalse(adapter.intercept(session,"{\"type\":\"ping\"}".getBytes(),response->fail()));
    }
    @Test void responseLossStoresCommittedEpochBeforeSendAndCompleteTupleCallbackIsOutsideTx() throws Exception {
        var service=mock(AgentRuntimeV2RegistrationService.class);var adapter=new AgentRuntimeV2WebSocketAdapter(provider(service),provider(new AgentRuntimeV2Readiness(ready())));var session=session("socket");adapter.established(session);byte[] raw=createWire();var request=AgentRuntimeV2RegistrationCodec.decodeCreate(raw);String binding=UUID.randomUUID().toString();var committed=new AgentRuntimeV2SocketTuple(binding,1,request.runtimeInstanceId(),UUID.randomUUID().toString(),UUID.randomUUID().toString(),8);
        when(service.create(any(),anyString(),anyString(),anyLong(),any())).thenAnswer(call->{assertOutside();return new AgentRuntimeV2RegistrationService.Result(AgentRuntimeV2RegistrationCodec.encodeRejected(new AgentRuntimeV2RegistrationCodec.Rejected("CREATE",request.registrationRequestId(),null,"NOT_AUTHORIZED")),committed,null);});
        assertThrows(IllegalStateException.class,()->adapter.intercept(session,raw,message->{assertOutside();throw new IllegalStateException("test lost response");}));adapter.intercept(session,raw,message->assertOutside());verify(service).create(any(),anyString(),anyString(),eq(8L),any());adapter.closed(session);verify(service).disconnect(committed,"SOCKET_CLOSED");adapter.closed(session);verify(service,times(1)).disconnect(any(),anyString());
    }
    @Test void ambientTransactionOrSynchronizationPreventsEveryRegistrationSendAndClose() throws Exception {
        var service=mock(AgentRuntimeV2RegistrationService.class);var adapter=new AgentRuntimeV2WebSocketAdapter(provider(service),provider(new AgentRuntimeV2Readiness(ready())));var session=session("socket");adapter.established(session);byte[] raw=createWire();
        TransactionSynchronizationManager.setActualTransactionActive(true);try{assertThrows(IllegalStateException.class,()->adapter.intercept(session,raw,message->fail()));}finally{TransactionSynchronizationManager.setActualTransactionActive(false);}
        TransactionSynchronizationManager.initSynchronization();try{assertThrows(IllegalStateException.class,()->adapter.intercept(session,raw,message->fail()));}finally{TransactionSynchronizationManager.clearSynchronization();}verifyNoInteractions(service);verify(session,never()).close(any());
    }
    @Test void actualSendReplacementCloseRenewFailureAndBoundedReaperSeeNoTransaction() throws Exception {
        var service=mock(AgentRuntimeV2RegistrationService.class);var adapter=new AgentRuntimeV2WebSocketAdapter(provider(service),provider(new AgentRuntimeV2Readiness(ready())));var old=session("old");var next=session("next");adapter.established(old);adapter.established(next);byte[] raw=createWire();var request=AgentRuntimeV2RegistrationCodec.decodeCreate(raw);String binding=UUID.randomUUID().toString(),instance=request.runtimeInstanceId();var first=new AgentRuntimeV2SocketTuple(binding,0,instance,UUID.randomUUID().toString(),UUID.randomUUID().toString(),0);var second=new AgentRuntimeV2SocketTuple(binding,1,instance,UUID.randomUUID().toString(),first.socketOwnerInstanceId(),1);byte[] rejected=AgentRuntimeV2RegistrationCodec.encodeRejected(new AgentRuntimeV2RegistrationCodec.Rejected("CREATE",request.registrationRequestId(),null,"NOT_AUTHORIZED"));
        var jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:network_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa",""));jdbc.execute("CREATE TABLE network_transaction_observation(id INTEGER PRIMARY KEY)");var tx=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));var committed=new AtomicInteger();
        Runnable observeNetwork=()->{assertOutside();assertEquals(committed.get(),jdbc.queryForObject("SELECT COUNT(*) FROM network_transaction_observation",Integer.class));};
        java.util.function.Function<AgentRuntimeV2RegistrationService.Result,AgentRuntimeV2RegistrationService.Result> commit=result->tx.execute(status->{assertTrue(TransactionSynchronizationManager.isActualTransactionActive());assertTrue(TransactionSynchronizationManager.isSynchronizationActive());jdbc.update("INSERT INTO network_transaction_observation VALUES(?)",committed.get()+1);TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCompletion(int status){assertEquals(STATUS_COMMITTED,status);committed.incrementAndGet();}});return result;});
        doAnswer(call->{assertOutside();return commit.apply(new AgentRuntimeV2RegistrationService.Result(rejected,first,null));}).when(service).create(any(),anyString(),anyString(),anyLong(),any());doAnswer(call->{observeNetwork.run();return null;}).when(old).close(any());doAnswer(call->{observeNetwork.run();return null;}).when(next).close(any());doAnswer(call->{observeNetwork.run();return null;}).when(next).sendMessage(any());
        adapter.intercept(old,raw,message->{observeNetwork.run();try{old.sendMessage(message);}catch(java.io.IOException failure){throw new IllegalStateException(failure);}});doAnswer(call->{assertOutside();return commit.apply(new AgentRuntimeV2RegistrationService.Result(rejected,second,first));}).when(service).create(any(),anyString(),anyString(),anyLong(),any());adapter.intercept(next,raw,message->{observeNetwork.run();try{next.sendMessage(message);}catch(java.io.IOException failure){throw new IllegalStateException(failure);}});assertEquals(2,committed.get());verify(old).close(CloseStatus.PROTOCOL_ERROR);verify(next).sendMessage(any());
        when(service.renew(any(),any())).thenAnswer(call->{observeNetwork.run();return false;});when(service.reapExpired(100)).thenAnswer(call->{observeNetwork.run();return 1;});adapter.maintainRouting();verify(next).close(CloseStatus.PROTOCOL_ERROR);verify(service).renew(any(),eq(second));verify(service).reapExpired(100);adapter.closed(next);verify(service).disconnect(second,"SOCKET_CLOSED");
    }
    private static void assertOutside(){assertFalse(TransactionSynchronizationManager.isActualTransactionActive());assertFalse(TransactionSynchronizationManager.isSynchronizationActive());}
}
