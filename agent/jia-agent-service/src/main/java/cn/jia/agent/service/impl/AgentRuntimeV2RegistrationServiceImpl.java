package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentRuntimeV2RegistrationStore;
import cn.jia.agent.entity.*;
import cn.jia.agent.security.AgentRuntimeV2RegistrationAuthorityResolver;
import cn.jia.agent.security.AgentRuntimeV2RegistrationAuthorityResolver.Authority;
import cn.jia.agent.security.AgentRuntimeV2RegistrationAuthorityResolver.Drain;
import cn.jia.agent.security.AgentRuntimeV2RegistrationKeyRing;
import cn.jia.agent.service.AgentRuntimeV2RegistrationService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionTemplate;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;
import java.util.function.LongConsumer;
import static cn.jia.agent.dao.AgentRuntimeV2RegistrationStore.*;
import static cn.jia.agent.entity.AgentRuntimeV2RegistrationFingerprints.*;
import static cn.jia.agent.security.AgentRuntimeV2RegistrationAuthorityResolver.*;

/** R2b2 persistence aggregate. No socket/network operations exist inside this class. */
public final class AgentRuntimeV2RegistrationServiceImpl implements AgentRuntimeV2RegistrationService {
    private final AgentRuntimeV2RegistrationStore db;
    private final AgentRuntimeV2RegistrationAuthorityResolver resolver;
    private final AgentRuntimeV2RegistrationKeyRing keys;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final SecureRandom random;
    private record History(List<Map<String,Object>> chain,Map<String,Map<String,Object>> facts,Map<String,Map<String,Object>> receipts) {
        Map<String,Object> latest(){return chain.isEmpty()?null:chain.getLast();}
        Map<String,Object> fact(Object id){return required(facts.get(id));}
    }
    public AgentRuntimeV2RegistrationServiceImpl(AgentRuntimeV2RegistrationStore db,
            AgentRuntimeV2RegistrationAuthorityResolver resolver,AgentRuntimeV2RegistrationKeyRing keys,Clock clock) {
        this(db,resolver,keys,clock,new SecureRandom());
    }
    /** Injectable CSPRNG for observation in transaction tests; dormant production wiring uses the default. */
    public AgentRuntimeV2RegistrationServiceImpl(AgentRuntimeV2RegistrationStore db,
            AgentRuntimeV2RegistrationAuthorityResolver resolver,AgentRuntimeV2RegistrationKeyRing keys,Clock clock,SecureRandom random) {
        this.db=db;this.resolver=resolver;this.keys=keys;this.clock=clock;
        this.random=Objects.requireNonNull(random);
        tx=new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(db.jdbc().getDataSource())));
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    @Override public Result create(AgentRuntimeV2RegistrationContext identity,String fence,String owner,long epoch,byte[] raw) {
        var request=AgentRuntimeV2RegistrationCodec.decodeCreate(raw);socketInputs(fence,owner,epoch);
        outside();if(!apiKey(identity))return reject("CREATE",request.registrationRequestId(),"NOT_AUTHORIZED");
        return operation("CREATE",request.registrationRequestId(),()->{
            Authority a=resolver.resolve(identity);
            Map<String,Object> binding=db.lock(BINDING,row("registration_subject_sha256",a.subjectHash(),"status","ACTIVE"));
            if(binding==null)binding=db.lock(BINDING,row("registration_subject_sha256",a.subjectHash(),"status","DISCONNECTED"));
            Map<String,Object> hint=db.hint(REQUEST,row("registration_request_id",request.registrationRequestId()));
            History history=lockHistory(binding,hint==null||hint.get("registration_receipt_id")==null?List.of():List.of(text(hint,"registration_receipt_id")));
            Map<String,Object> latest=history.latest();
            Map<String,Object> currentReceipt=binding==null?null:history.receipts().get(binding.get("current_registration_receipt_id"));
            Map<String,Object> mapping=db.lock(REQUEST,row("registration_request_id",request.registrationRequestId()));
            if(mapping!=null)return replay(a,mapping,raw,"CREATE",request.runtimeInstanceId(),sha256(request.processPublicKeySpki()),null,fence,owner,epoch,binding,latest,currentReceipt,history);
            if(!a.permitted()||a.frozenRequired())return reject("CREATE",request.registrationRequestId(),"NOT_AUTHORIZED");
            if(binding!=null)return reject("CREATE",request.registrationRequestId(),"RECONCILIATION_REQUIRED");
            String id=uuid(), factId=uuid(), receiptId=uuid();long now=now();long lease=Math.addExact(now,30000);
            checkIdentityTime(a,now);
            byte[] verifier=proof();var key=keys.active();
            Map<String,Object> b=receiver(a,null);b.putAll(row("runtime_binding_id",id,"credential_kind","API_KEY","credential_reference",a.subject().credentialReference(),
                "runtime_installation_id",null,"contract_version",2,"registration_subject_sha256",a.subjectHash(),"registration_authority_id",a.registration().get("registration_authority_id"),
                "registration_authority_revision",a.registration().get("authority_revision"),"process_public_key_spki",request.processPublicKeySpki(),"process_public_key_sha256",sha256(request.processPublicKeySpki()),
                "verifier_version",1L,"verifier_key_id",key.keyId(),"reconnect_verifier",reconnectVerifier(key,id,1,verifier),"runtime_instance_id",request.runtimeInstanceId(),
                "binding_generation",0L,"status","ACTIVE","connected_at",now,"disconnected_at",null,"revoked_at",null,"revoke_reason",null,
                "socket_owner_instance_id",owner,"socket_owner_epoch",epoch,"socket_owner_lease_until",lease,"current_session_fence",fence,"version",0L,
                "create_time",now,"update_time",now,"evidence_provenance","R2B1_ENFORCED"));
            Map<String,Object> receipt=makeReceipt(a,b,request.registrationRequestId(),"CREATE",null,factId,receiptId,verifier,now);
            Arrays.fill(verifier,(byte)0);
            b.put("current_registration_receipt_id",receiptId);b.put("current_registration_receipt_sha256",receipt.get("receipt_wire_sha256"));
            Map<String,Object> fact=connected(a,b,null,factId,receipt,null,now);
            db.append(BINDING,b);db.append(RECEIPT,receipt);db.append(FACT,fact);
            db.append(REQUEST,mapping(a,b,receipt,raw,createSemantic(a.subjectHash(),a.fingerprint(),request.runtimeInstanceId(),sha256(request.processPublicKeySpki())),"GRANTED",null,now));
            Result result=delivery(receipt,"ADVANCED",b,fence,owner,epoch,null);
            beforeCommitTime(time->{checkIdentityTime(a,time);check(time>=now&&time<lease);});
            return result;
        });
    }
    @Override public Result challenge(AgentRuntimeV2RegistrationContext identity,String fence,String owner,long epoch,byte[] raw) {
        var request=AgentRuntimeV2RegistrationCodec.decodeChallengeRequest(raw);socketInputs(fence,owner,epoch);
        outside();if(!apiKey(identity))return reject("CHALLENGE",request.challengeRequestId(),"NOT_AUTHORIZED");
        return operation("CHALLENGE",request.challengeRequestId(),()->{
            Authority a=resolver.resolve(identity); Drain drain=null;boolean permitted=a.permitted();
            try { if(permitted)drain=resolver.lockDrain(a,request.runtimeBindingId()); } catch(SecurityException denied){permitted=false;}
            Map<String,Object> b=db.lock(BINDING,row("runtime_binding_id",request.runtimeBindingId()));
            List<String> ids=new ArrayList<>();ids.add(request.priorRegistrationReceiptId());if(drain!=null)ids.add(text(drain.completion(),"frozen_registration_receipt_id"));
            History history=lockHistory(b,ids);Map<String,Object> fact=history.latest();
            Map<String,Map<String,Object>> receipts=history.receipts();
            Map<String,Object> prior=receipts.get(request.priorRegistrationReceiptId());
            Map<String,Object> c=db.lock(CHALLENGE,row("challenge_request_id",request.challengeRequestId()));
            Map<String,Object> d=db.lock(DECISION,row("challenge_request_id",request.challengeRequestId()));
            Map<String,Object> context=decisionContext(a,request,fence,owner,epoch,raw);
            if(d!=null) {
                check(same(d.get("registration_subject_sha256"),a.subjectHash()));
                if(!same(d.get("request_wire_sha256"),sha256(raw))||!same(d.get("request_context_sha256"),context.get("request_context_sha256")))return reject("CHALLENGE",request.challengeRequestId(),"REQUEST_CONFLICT");
                verifyDecision(d,c);
                if("DENIED".equals(d.get("decision")))return new Result((byte[])d.get("response_wire_bytes"),null,null);
                if(!permitted||!same(d.get("registration_authority_sha256"),a.fingerprint()))return reject("CHALLENGE",request.challengeRequestId(),"NOT_AUTHORIZED");
                validateBinding(a,b);validatePrior(b,prior,request.runtimeInstanceId(),request.priorRegistrationReceiptSha256());validateCurrent(b,fact,prior,history);
                if(drain!=null)validateFrozen(a,b,drain,history);else validateOrdinaryDelta(a,b);
                long now=now();checkAdmissionTime(a,drain,now);
                if(!"ISSUED".equals(c.get("status"))||now<number(c,"issued_at")||now>=number(c,"expires_at"))return reject("CHALLENGE",request.challengeRequestId(),"NOT_AUTHORIZED");
                return new Result((byte[])d.get("response_wire_bytes"),null,null);
            }
            if(c!=null)return reject("CHALLENGE",request.challengeRequestId(),"NOT_AUTHORIZED"); // orphan/historical: never invent a ledger
            long issuedAt;
            try {
                check(permitted);validateBinding(a,b);validatePrior(b,prior,request.runtimeInstanceId(),request.priorRegistrationReceiptSha256());validateCurrent(b,fact,prior,history);
                if(drain!=null)validateFrozen(a,b,drain,history);else validateOrdinaryDelta(a,b);
                issuedAt=now();checkAdmissionTime(a,drain,issuedAt);
            } catch(SecurityException denied) {
                byte[] response=reject("CHALLENGE",request.challengeRequestId(),"NOT_AUTHORIZED").responseBytes();
                context.putAll(row("decision","DENIED","challenge_id",null,"response_wire_bytes",response,"response_wire_sha256",sha256(response),"denial_code","NOT_AUTHORIZED","decided_at",now()));
                db.append(DECISION,context);beforeCommitTime(time->checkIdentityTime(a,time));return new Result(response,null,null);
            }
            long now=issuedAt, expiry=Math.addExact(now,60000);String challengeId=uuid();byte[] plain=proof();var digestKey=keys.active();
            var sealed=AgentRuntimeV2RegistrationCrypto.sealChallenge(plain,(byte[])b.get("process_public_key_spki"),(ephemeral,nonce)->AgentRuntimeV2RegistrationCodec.challengeAad(new AgentRuntimeV2RegistrationCodec.Challenge(request.challengeRequestId(),challengeId,request.runtimeBindingId(),number(b,"binding_generation"),request.runtimeInstanceId(),request.priorRegistrationReceiptId(),request.priorRegistrationReceiptSha256(),ephemeral,nonce,new byte[48],sha256(plain),expiry)));
            var response=new AgentRuntimeV2RegistrationCodec.Challenge(request.challengeRequestId(),challengeId,request.runtimeBindingId(),number(b,"binding_generation"),request.runtimeInstanceId(),request.priorRegistrationReceiptId(),request.priorRegistrationReceiptSha256(),sealed.serverEphemeralPublicKeySpki(),sealed.nonce(),sealed.sealed(),sha256(plain),expiry);
            byte[] wire=AgentRuntimeV2RegistrationCodec.encodeChallenge(response);
            Map<String,Object> challenge=record(response);challenge.remove("expires_at");
            challenge.putAll(row("request_wire_sha256",sha256(raw),"semantic_sha256",challengeSemantic(a.subjectHash(),a.fingerprint(),request.runtimeBindingId(),request.priorRegistrationReceiptId(),request.priorRegistrationReceiptSha256(),request.runtimeInstanceId(),fence,owner,epoch),
                "registration_subject_sha256",a.subjectHash(),"registration_authority_sha256",a.fingerprint(),"registration_authority_id",a.registration().get("registration_authority_id"),"registration_authority_revision",a.registration().get("authority_revision"),
                "process_public_key_sha256",b.get("process_public_key_sha256"),"challenge_digest",reconnectChallenge(digestKey,challengeId,request.runtimeBindingId(),number(b,"binding_generation"),request.priorRegistrationReceiptId(),request.runtimeInstanceId(),plain),
                "challenge_digest_key_id",digestKey.keyId(),"challenge_cipher_suite",AgentRuntimeV2RegistrationCodec.CIPHER_SUITE,"challenge_wire_bytes",wire,"challenge_wire_sha256",sha256(wire),
                "issuer_socket_owner_instance_id",owner,"issuer_socket_owner_epoch",epoch,"provisional_session_fence",fence,"issued_at",now,"expires_at",expiry,"status","ISSUED","consumed_reconnect_request_id",null,"consumed_at",null,"denial_code",null,"contract_version",2,
                "tenant_id",a.subject().tenantId(),"client_id",a.subject().runtimeCredentialClientId(),"create_time",now,"update_time",now));
            Arrays.fill(plain,(byte)0);
            context.putAll(row("decision","ISSUED","challenge_id",challengeId,"response_wire_bytes",wire,"response_wire_sha256",sha256(wire),"denial_code",null,"decided_at",now));
            db.append(CHALLENGE,challenge);db.append(DECISION,context);
            Drain finalDrain=drain;
            beforeCommitTime(time->{checkAdmissionTime(a,finalDrain,time);check(time>=issuedAt&&time<expiry);});
            return new Result(wire,null,null);
        });
    }
    @Override public Result reconnect(AgentRuntimeV2RegistrationContext identity,String fence,String owner,long epoch,byte[] raw) {
        var request=AgentRuntimeV2RegistrationCodec.decodeCommit(raw);socketInputs(fence,owner,epoch);
        outside();if(!apiKey(identity))return reject("RECONNECT",request.reconnectRequestId(),"NOT_AUTHORIZED");
        return operation("RECONNECT",request.reconnectRequestId(),()->{
            Authority a=resolver.resolve(identity);Drain drain=null;boolean permitted=a.permitted();
            try { if(permitted)drain=resolver.lockDrain(a,request.runtimeBindingId()); } catch(SecurityException denied){permitted=false;}
            Map<String,Object> b=db.lock(BINDING,row("runtime_binding_id",request.runtimeBindingId()));
            List<String> ids=new ArrayList<>();ids.add(request.priorRegistrationReceiptId());if(b!=null)ids.add(text(b,"current_registration_receipt_id"));if(drain!=null)ids.add(text(drain.completion(),"frozen_registration_receipt_id"));
            Map<String,Object> hint=db.hint(REQUEST,row("registration_request_id",request.reconnectRequestId()));if(hint!=null&&hint.get("registration_receipt_id")!=null)ids.add(text(hint,"registration_receipt_id"));
            History history=lockHistory(b,ids);Map<String,Object> fact=history.latest();
            Map<String,Map<String,Object>> receipts=history.receipts();Map<String,Object> prior=receipts.get(request.priorRegistrationReceiptId());
            Map<String,Object> c=db.lock(CHALLENGE,row("challenge_id",request.challengeId()));
            Map<String,Object> d=c==null?null:db.lock(DECISION,row("challenge_request_id",c.get("challenge_request_id")));
            Map<String,Object> m=db.lock(REQUEST,row("registration_request_id",request.reconnectRequestId()));
            if(m!=null)return replay(a,m,raw,"RECONNECT",request.runtimeInstanceId(),b==null?null:bytes(b,"process_public_key_sha256"),request,fence,owner,epoch,b,fact,receipts.get(b==null?null:b.get("current_registration_receipt_id")),history);
            check(permitted);validateBinding(a,b);validatePrior(b,prior,request.runtimeInstanceId(),request.priorRegistrationReceiptSha256());validateCurrent(b,fact,prior,history);
            if(drain!=null)validateFrozen(a,b,drain,history);else validateOrdinaryDelta(a,b);
            required(c);required(d);verifyDecision(d,c);
            check("ISSUED".equals(c.get("status"))&&same(c.get("registration_subject_sha256"),a.subjectHash())&&same(c.get("registration_authority_sha256"),a.fingerprint()));
            check(same(c.get("runtime_binding_id"),request.runtimeBindingId())&&same(c.get("binding_generation"),b.get("binding_generation"))&&same(c.get("runtime_instance_id"),request.runtimeInstanceId())&&same(c.get("prior_registration_receipt_id"),request.priorRegistrationReceiptId())&&same(c.get("prior_registration_receipt_sha256"),request.priorRegistrationReceiptSha256())&&same(c.get("process_public_key_sha256"),b.get("process_public_key_sha256")));
            check(same(c.get("provisional_session_fence"),fence)&&same(c.get("issuer_socket_owner_instance_id"),owner)&&same(c.get("issuer_socket_owner_epoch"),epoch));
            long now=now();checkAdmissionTime(a,drain,now);check(now>=number(c,"issued_at")&&now<number(c,"expires_at"));
            check(constantTimeEquals(bytes(b,"reconnect_verifier"),reconnectVerifier(keys.require(text(b,"verifier_key_id")),request.runtimeBindingId(),number(b,"verifier_version"),request.verifier())));
            check(constantTimeEquals(bytes(c,"challenge_digest"),reconnectChallenge(keys.require(text(c,"challenge_digest_key_id")),request.challengeId(),request.runtimeBindingId(),number(b,"binding_generation"),request.priorRegistrationReceiptId(),request.runtimeInstanceId(),request.challenge())));
            check(same(sha256(request.challenge()),c.get("challenge_sha256")));
            // Fresh time is sampled again after validation, immediately before CAS and append.
            now=now();checkAdmissionTime(a,drain,now);check(now>=number(c,"issued_at")&&now<number(c,"expires_at"));
            db.consume(c,request.reconnectRequestId(),now);
            AgentRuntimeV2SocketTuple old="ACTIVE".equals(b.get("status"))?tuple(b):null;
            if(old!=null)fact=appendDisconnected(b,fact,number(b,"socket_owner_lease_until")<=now?"ROUTING_LEASE_EXPIRED":"REPLACED_BY_AUTHENTICATED_RECONNECT",now);
            Map<String,Object> next=new LinkedHashMap<>(b);
            next.putAll(receiver(a,drain==null?null:history.fact(drain.completion().get("frozen_connected_fact_id"))));
            long generation=Math.addExact(number(b,"binding_generation"),1);long verifierVersion=Math.addExact(number(b,"verifier_version"),1);check(verifierVersion==Math.addExact(generation,1));
            long newEpoch=Math.addExact(Math.max(number(c,"issuer_socket_owner_epoch"),old==null?epoch:number(b,"socket_owner_epoch")),1);
            // Owner epoch is server-owned route state; provisional epoch is checked above and replaced atomically.
            next.putAll(row("binding_generation",generation,"verifier_version",verifierVersion,"status","ACTIVE","connected_at",now,"disconnected_at",null,"revoked_at",null,"revoke_reason",null,
                "registration_authority_revision",a.registration().get("authority_revision"),"socket_owner_instance_id",owner,"socket_owner_epoch",newEpoch,"socket_owner_lease_until",Math.addExact(now,30000),"current_session_fence",fence,"update_time",now));
            byte[] verifier=proof();var key=keys.active();next.put("verifier_key_id",key.keyId());next.put("reconnect_verifier",reconnectVerifier(key,request.runtimeBindingId(),verifierVersion,verifier));
            String factId=uuid(), receiptId=uuid();Map<String,Object> receipt=makeReceipt(a,next,request.reconnectRequestId(),"RECONNECT",prior,factId,receiptId,verifier,now);Arrays.fill(verifier,(byte)0);
            next.put("current_registration_receipt_id",receiptId);next.put("current_registration_receipt_sha256",receipt.get("receipt_wire_sha256"));
            Map<String,Object> connected=connected(a,next,fact,factId,receipt,drain,now);
            db.append(RECEIPT,receipt);db.append(FACT,connected);
            db.append(REQUEST,mapping(a,next,receipt,raw,reconnectSemantic(a.subjectHash(),a.fingerprint(),request.challengeId(),request.runtimeBindingId(),request.priorRegistrationReceiptId(),request.priorRegistrationReceiptSha256(),request.runtimeInstanceId(),request.verifier(),request.challenge()),"GRANTED",null,now));
            Map<String,Object> changes=new LinkedHashMap<>(next);for(String name:List.of("id","runtime_binding_id","live_registration_subject_sha256","live_business_subject","version","create_time"))changes.remove(name);
            db.bindingCas(b,changes);next.put("version",Math.addExact(number(b,"version"),1));
            Result result=new Result(deliveryBytes(receipt,"ADVANCED","CURRENT"),tuple(next),old);
            Drain finalDrain=drain;long advancedAt=now;
            beforeCommitTime(time->{checkAdmissionTime(a,finalDrain,time);check(time>=advancedAt&&time<number(next,"socket_owner_lease_until"));check(time>=number(c,"issued_at")&&time<number(c,"expires_at"));});
            // The receiver learns committed route epoch from this result, not by copying the provisional epoch.
            return result;
        });
    }
    private Result replay(Authority a,Map<String,Object> m,byte[] raw,String kind,String instance,byte[] processHash,AgentRuntimeV2RegistrationCodec.Commit request,
            String fence,String owner,long epoch,Map<String,Object> b,Map<String,Object> fact,Map<String,Object> currentReceipt,History history) {
        // Historical GRANTED delivery is authentication, not a new receiver admission or lease.
        check(same(m.get("registration_subject_sha256"),a.subjectHash()));validateBinding(a,b);
        if(!same(m.get("request_wire_sha256"),sha256(raw))||!same(m.get("request_kind"),kind))return reject(kind,text(m,"registration_request_id"),"REQUEST_CONFLICT");
        check(same(m.get("runtime_instance_id"),instance)&&same(m.get("process_public_key_sha256"),processHash));
        byte[] semantic=request==null?createSemantic(a.subjectHash(),bytes(m,"registration_authority_sha256"),instance,processHash):reconnectSemantic(a.subjectHash(),bytes(m,"registration_authority_sha256"),request.challengeId(),request.runtimeBindingId(),request.priorRegistrationReceiptId(),request.priorRegistrationReceiptSha256(),instance,request.verifier(),request.challenge());
        check(same(m.get("semantic_sha256"),semantic));
        if("DENIED".equals(m.get("decision")))return reject(kind,text(m,"registration_request_id"),text(m,"denial_code"));
        Map<String,Object> receipt=history.receipts().get(m.get("registration_receipt_id"));
        validateReceipt(required(receipt));check(same(receipt.get("receipt_wire_sha256"),m.get("registration_receipt_sha256")));
        for(String name:List.of("runtime_binding_id","binding_generation","runtime_instance_id","process_public_key_sha256","registration_authority_id","registration_authority_revision","data_domain_id","execution_pool_id","runtime_credential_client_id","owner_jiacn","agent_id","claimant_deployment_id","claimant_worker_generation","receiver_registration_revision","admission_epoch","module_id","migration_epoch"))check(same(m.get(name),receipt.get(name)));
        Map<String,Object> oldFact=history.fact(receipt.get("binding_generation_fact_id"));
        validateHolder(required(oldFact),receipt);check(same(oldFact.get("registration_subject_sha256"),a.subjectHash()));
        if(currentReceipt!=null)validateCurrent(b,fact,currentReceipt,history);
        checkIdentityTime(a,now());return delivery(receipt,"PRIOR",b,fence,owner,epoch,null);
    }
    @Override public boolean renew(AgentRuntimeV2RegistrationContext identity,AgentRuntimeV2SocketTuple socket) {
        outside();if(!apiKey(identity))return false;try{return retry(()->tx.execute(status->{Authority a=resolver.resolve(identity);check(a.permitted());Drain drain=resolver.lockDrain(a,socket.runtimeBindingId());Map<String,Object> b=db.lock(BINDING,row("runtime_binding_id",socket.runtimeBindingId()));if(!matches(b,socket)||number(b,"socket_owner_lease_until")<=now())return false;
            History history=lockHistory(b,drain==null?List.of():List.of(text(drain.completion(),"frozen_registration_receipt_id")));Map<String,Object> f=required(history.latest());Map<String,Object> r=required(history.receipts().get(b.get("current_registration_receipt_id")));validateBinding(a,b);validateCurrent(b,f,r,history);if(drain!=null)validateFrozen(a,b,drain,history);else validateOrdinaryDelta(a,b);
            long now=now();checkAdmissionTime(a,drain,now);if(number(b,"socket_owner_lease_until")<=now)return false;long lease=Math.addExact(now,30000);
            if(lease>number(b,"socket_owner_lease_until"))db.bindingCas(b,row("socket_owner_lease_until",lease,"update_time",now));
            beforeCommitTime(time->{checkAdmissionTime(a,drain,time);check(time>=now&&time<number(b,"socket_owner_lease_until")&&time<Math.max(lease,number(b,"socket_owner_lease_until")));});
            return true;}));}catch(SecurityException denied){return false;}
    }
    @Override public boolean disconnect(AgentRuntimeV2SocketTuple socket,String reason) {
        outside();if(!List.of("SOCKET_CLOSED","PROTOCOL_CLOSED","ROUTING_LEASE_EXPIRED").contains(reason))throw new IllegalArgumentException("disconnect reason");
        return retry(()->tx.execute(status->{Map<String,Object> b=db.lock(BINDING,row("runtime_binding_id",socket.runtimeBindingId()));if(!matches(b,socket))return false;
            long now=now();if("ROUTING_LEASE_EXPIRED".equals(reason)&&number(b,"socket_owner_lease_until")>now)return false;
            History history=lockHistory(b,List.of());Map<String,Object> f=required(history.latest());Map<String,Object> r=required(history.receipts().get(b.get("current_registration_receipt_id")));validateCurrent(b,f,r,history);
            appendDisconnected(b,f,reason,now);db.bindingCas(b,row("status","DISCONNECTED","disconnected_at",now,"current_session_fence",null,"socket_owner_instance_id",null,"socket_owner_epoch",null,"socket_owner_lease_until",null,"update_time",now));return true;}));
    }
    @Override public int reapExpired(int limit) { outside();int count=0;for(Map<String,Object> b:db.expiredHints(now(),limit))if(disconnect(tuple(b),"ROUTING_LEASE_EXPIRED"))count++;return count; }
    private Map<String,Object> appendDisconnected(Map<String,Object> b,Map<String,Object> latest,String reason,long now) {
        check(number(latest,"effective_at")<=now&&number(b,"connected_at")<=now);
        Map<String,Object> f=baseFact(b,latest,uuid(),now);f.putAll(row("event_type","DISCONNECTED","reason",reason));db.append(FACT,f);return f;
    }
    private Map<String,Object> connected(Authority a,Map<String,Object> b,Map<String,Object> latest,String id,Map<String,Object> receipt,Drain drain,long now) {
        Map<String,Object> f=baseFact(b,latest,id,now);
        f.putAll(row("event_type","CONNECTED","reason",null,"registration_subject_sha256",a.subjectHash(),"registration_authority_id",a.registration().get("registration_authority_id"),"registration_authority_revision",a.registration().get("authority_revision"),
            "runtime_credential_client_id",a.subject().runtimeCredentialClientId(),"process_public_key_sha256",b.get("process_public_key_sha256"),"verifier_version",b.get("verifier_version"),"registration_receipt_id",receipt.get("registration_receipt_id"),"registration_receipt_sha256",receipt.get("receipt_wire_sha256"),
            "connection_admission_kind",drain==null?"CURRENT_ADMISSION":"FROZEN_DRAIN","drain_control_operation_id",drain==null?null:drain.operation().get("drain_operation_id")));
        f.put("generation_snapshot_sha256",holder(f).sha256());return f;
    }
    private Map<String,Object> baseFact(Map<String,Object> b,Map<String,Object> latest,String id,long now) {
        Map<String,Object> f=new LinkedHashMap<>();for(String name:List.of("runtime_binding_id","binding_generation","runtime_instance_id","tenant_id","client_id","data_domain_id","execution_pool_id","owner_jiacn","agent_id","claimant_deployment_id","claimant_worker_generation","receiver_registration_revision","admission_epoch","module_id","migration_epoch","socket_owner_instance_id","socket_owner_epoch","socket_owner_lease_until"))f.put(name,b.get(name));
        f.putAll(row("binding_generation_fact_id",id,"fact_sequence",latest==null?0L:Math.addExact(number(latest,"fact_sequence"),1),"previous_fact_id",latest==null?null:latest.get("binding_generation_fact_id"),"session_fence",b.get("current_session_fence"),"effective_at",now,"create_time",now,"update_time",now,"evidence_provenance","R2B1_ENFORCED"));return f;
    }
    private Map<String,Object> receiver(Authority a,Map<String,Object> frozen) {
        Map<String,Object> r=row("tenant_id",a.subject().tenantId(),"client_id",a.subject().runtimeCredentialClientId(),"data_domain_id",a.registration().get("data_domain_id"),"execution_pool_id",a.registration().get("execution_pool_id"),"owner_jiacn",a.subject().ownerJiacn(),"agent_id",a.subject().agentId(),"claimant_deployment_id",a.registration().get("claimant_deployment_id"),"claimant_worker_generation",a.deployment().get("worker_generation"),"receiver_registration_revision",a.deployment().get("registration_revision"),"admission_epoch",a.tenant().get("admission_epoch"),"module_id",a.registration().get("module_id"),"migration_epoch",a.module().get("migration_epoch"));
        if(frozen!=null)for(String name:new ArrayList<>(r.keySet()))r.put(name,frozen.get(name));return r;
    }
    private Map<String,Object> makeReceipt(Authority a,Map<String,Object> b,String request,String kind,Map<String,Object> prior,String fact,String id,byte[] verifier,long now) {
        java.util.function.BiFunction<byte[],byte[],AgentRuntimeV2RegistrationCodec.Receipt> builder=(ephemeral,nonce)->new AgentRuntimeV2RegistrationCodec.Receipt(id,kind,request,prior==null?null:text(prior,"registration_receipt_id"),prior==null?null:bytes(prior,"receipt_wire_sha256"),text(b,"runtime_binding_id"),number(b,"binding_generation"),fact,text(b,"runtime_instance_id"),bytes(b,"process_public_key_sha256"),number(b,"verifier_version"),sha256(verifier),ephemeral,nonce,new byte[48],text(b,"tenant_id"),text(b,"data_domain_id"),text(b,"execution_pool_id"),text(b,"client_id"),text(b,"owner_jiacn"),text(b,"agent_id"),text(a.registration(),"registration_authority_id"),number(a.registration(),"authority_revision"),text(b,"claimant_deployment_id"),number(b,"claimant_worker_generation"),number(b,"receiver_registration_revision"),number(b,"admission_epoch"),text(b,"module_id"),number(b,"migration_epoch"),now);
        var sealed=AgentRuntimeV2RegistrationCrypto.sealVerifier(verifier,(byte[])b.get("process_public_key_spki"),(ephemeral,nonce)->AgentRuntimeV2RegistrationCodec.receiptAad(builder.apply(ephemeral,nonce)));
        Map<String,Object> r=record(builder.apply(sealed.serverEphemeralPublicKeySpki(),sealed.nonce()));r.put("sealed_verifier",sealed.sealed());
        var receipt=receipt(r);byte[] wire=AgentRuntimeV2RegistrationCodec.encodeReceipt(receipt);
        r.putAll(row("verifier_cipher_suite",AgentRuntimeV2RegistrationCodec.CIPHER_SUITE,"receipt_wire_bytes",wire,"receipt_wire_sha256",sha256(wire),"contract_version",2,"client_id",a.subject().runtimeCredentialClientId(),"create_time",now,"update_time",now));return r;
    }
    private Map<String,Object> mapping(Authority a,Map<String,Object> b,Map<String,Object> r,byte[] raw,byte[] semantic,String decision,String denial,long now) {
        Map<String,Object> m=receiver(a,b);m.put("runtime_credential_client_id",m.get("client_id"));
        m.putAll(row("registration_request_id",r.get("registration_request_id"),"request_kind",r.get("receipt_kind"),"request_wire_sha256",sha256(raw),"semantic_sha256",semantic,"registration_subject_sha256",a.subjectHash(),"registration_authority_sha256",a.fingerprint(),
            "runtime_binding_id",b.get("runtime_binding_id"),"binding_generation",b.get("binding_generation"),"runtime_instance_id",b.get("runtime_instance_id"),"process_public_key_sha256",b.get("process_public_key_sha256"),"registration_authority_id",a.registration().get("registration_authority_id"),"registration_authority_revision",a.registration().get("authority_revision"),
            "decision",decision,"registration_receipt_id",r.get("registration_receipt_id"),"registration_receipt_sha256",r.get("receipt_wire_sha256"),"denial_code",denial,"decided_at",now,"contract_version",2,"create_time",now,"update_time",now));return m;
    }
    private Map<String,Object> decisionContext(Authority a,AgentRuntimeV2RegistrationCodec.ChallengeRequest r,String fence,String owner,long epoch,byte[] raw) {
        Map<String,Object> d=row("challenge_request_id",r.challengeRequestId(),"contract_version",2,"credential_reference",a.subject().credentialReference(),"tenant_id",a.subject().tenantId(),"runtime_credential_client_id",a.subject().runtimeCredentialClientId(),"owner_jiacn",a.subject().ownerJiacn(),"agent_id",a.subject().agentId(),"registration_subject_sha256",a.subjectHash(),"registration_authority_id",a.registration().get("registration_authority_id"),"registration_authority_revision",a.registration().get("authority_revision"),"registration_authority_sha256",a.fingerprint(),
            "request_wire_bytes",raw.clone(),"request_wire_sha256",sha256(raw),"runtime_binding_id",r.runtimeBindingId(),"prior_registration_receipt_id",r.priorRegistrationReceiptId(),"prior_registration_receipt_sha256",r.priorRegistrationReceiptSha256(),"runtime_instance_id",r.runtimeInstanceId(),"provisional_session_fence",fence,"issuer_socket_owner_instance_id",owner,"issuer_socket_owner_epoch",epoch);
        d.put("request_context_sha256",AgentRuntimeV2RegistrationDeltaHashes.context(d));return d;
    }
    public static void verifyDecision(Map<String,Object> d,Map<String,Object> c) {
        check(same(sha256((byte[])d.get("request_wire_bytes")),d.get("request_wire_sha256"))&&same(AgentRuntimeV2RegistrationDeltaHashes.context(d),d.get("request_context_sha256"))&&same(sha256((byte[])d.get("response_wire_bytes")),d.get("response_wire_sha256")));
        var request=AgentRuntimeV2RegistrationCodec.decodeChallengeRequest((byte[])d.get("request_wire_bytes"));
        check(same(request.challengeRequestId(),d.get("challenge_request_id"))&&same(request.runtimeBindingId(),d.get("runtime_binding_id"))&&same(request.runtimeInstanceId(),d.get("runtime_instance_id"))&&same(request.priorRegistrationReceiptId(),d.get("prior_registration_receipt_id"))&&same(request.priorRegistrationReceiptSha256(),d.get("prior_registration_receipt_sha256")));
        check(number(d,"contract_version")==2&&number(d,"decided_at")>0);
        if("DENIED".equals(d.get("decision"))) { check(c==null&&d.get("challenge_id")==null&&"NOT_AUTHORIZED".equals(d.get("denial_code")));var r=AgentRuntimeV2RegistrationCodec.decodeRejected((byte[])d.get("response_wire_bytes"));check("CHALLENGE".equals(r.correlationType())&&r.correlationId().equals(request.challengeRequestId())&&r.runtimeBindingId()==null&&"NOT_AUTHORIZED".equals(r.reasonCode())); }
        else { required(c);check("ISSUED".equals(d.get("decision")));var challenge=AgentRuntimeV2RegistrationCodec.decodeChallenge((byte[])d.get("response_wire_bytes"));record(challenge).forEach((name,value)->check(same(value,c.get(name))));check(AgentRuntimeV2RegistrationCodec.CIPHER_SUITE.equals(c.get("challenge_cipher_suite")));for(String name:List.of("challenge_request_id","challenge_id","request_wire_sha256","registration_subject_sha256","registration_authority_id","registration_authority_revision","registration_authority_sha256","runtime_binding_id","prior_registration_receipt_id","prior_registration_receipt_sha256","runtime_instance_id","provisional_session_fence","issuer_socket_owner_instance_id","issuer_socket_owner_epoch"))check(same(d.get(name),c.get(name)));check(same(d.get("response_wire_bytes"),c.get("challenge_wire_bytes"))&&same(d.get("response_wire_sha256"),c.get("challenge_wire_sha256"))&&same(c.get("semantic_sha256"),challengeSemantic(bytes(d,"registration_subject_sha256"),bytes(d,"registration_authority_sha256"),text(d,"runtime_binding_id"),text(d,"prior_registration_receipt_id"),bytes(d,"prior_registration_receipt_sha256"),text(d,"runtime_instance_id"),text(d,"provisional_session_fence"),text(d,"issuer_socket_owner_instance_id"),number(d,"issuer_socket_owner_epoch")))); }
        if(c!=null) {
            check(d.get("denial_code")==null&&c.get("denial_code")==null&&number(c,"contract_version")==2&&number(c,"issued_at")==number(d,"decided_at")&&number(c,"expires_at")==Math.addExact(number(c,"issued_at"),60000));
            if("ISSUED".equals(c.get("status")))check(c.get("consumed_reconnect_request_id")==null&&c.get("consumed_at")==null);
            else {check("CONSUMED".equals(c.get("status"))&&text(c,"consumed_reconnect_request_id").matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"));check(number(c,"consumed_at")>=number(c,"issued_at")&&number(c,"consumed_at")<number(c,"expires_at"));}
        }
    }
    private void validateBinding(Authority a,Map<String,Object> b) { required(b);check(number(b,"contract_version")==2&&"R2B1_ENFORCED".equals(b.get("evidence_provenance"))&&!"REVOKED".equals(b.get("status"))&&List.of("ACTIVE","DISCONNECTED").contains(b.get("status"))&&same(b.get("registration_subject_sha256"),a.subjectHash())&&same(b.get("credential_reference"),a.subject().credentialReference())&&same(b.get("registration_authority_id"),a.registration().get("registration_authority_id")));for(String name:List.of("tenant_id","client_id","owner_jiacn","agent_id","data_domain_id","execution_pool_id","claimant_deployment_id","module_id"))check(same(b.get(name),a.registration().get(name))); }
    private void validatePrior(Map<String,Object> b,Map<String,Object> r,String instance,byte[] hash) { required(r);validateReceipt(r);check(same(r.get("runtime_binding_id"),b.get("runtime_binding_id"))&&same(r.get("runtime_instance_id"),instance)&&same(b.get("runtime_instance_id"),instance)&&same(r.get("receipt_wire_sha256"),hash)&&same(b.get("current_registration_receipt_id"),r.get("registration_receipt_id"))&&same(b.get("current_registration_receipt_sha256"),hash)&&same(r.get("binding_generation"),b.get("binding_generation"))&&same(r.get("process_public_key_sha256"),b.get("process_public_key_sha256"))); }
    public static void validateReceipt(Map<String,Object> r) { var decoded=AgentRuntimeV2RegistrationCodec.decodeReceipt((byte[])r.get("receipt_wire_bytes"));check(same(sha256((byte[])r.get("receipt_wire_bytes")),r.get("receipt_wire_sha256")));record(decoded).forEach((name,value)->check(same(value,r.get(name))));check(AgentRuntimeV2RegistrationCodec.CIPHER_SUITE.equals(r.get("verifier_cipher_suite"))); }
    public static void validateHolder(Map<String,Object> f,Map<String,Object> r) { check("CONNECTED".equals(f.get("event_type"))&&"R2B1_ENFORCED".equals(f.get("evidence_provenance"))&&same(holder(f).sha256(),f.get("generation_snapshot_sha256")));for(String name:List.of("runtime_binding_id","binding_generation","runtime_instance_id","tenant_id","data_domain_id","execution_pool_id","owner_jiacn","agent_id","claimant_deployment_id","claimant_worker_generation","receiver_registration_revision","admission_epoch","module_id","migration_epoch","registration_authority_id","registration_authority_revision","process_public_key_sha256","verifier_version"))check(same(f.get(name),r.get(name)));check(same(f.get("binding_generation_fact_id"),r.get("binding_generation_fact_id"))&&same(f.get("registration_receipt_id"),r.get("registration_receipt_id"))&&same(f.get("registration_receipt_sha256"),r.get("receipt_wire_sha256"))&&same(f.get("runtime_credential_client_id"),r.get("runtime_credential_client_id"))&&same(f.get("client_id"),r.get("runtime_credential_client_id"))&&same(f.get("effective_at"),r.get("connected_at"))); }
    private void validateCurrent(Map<String,Object> b,Map<String,Object> latest,Map<String,Object> r,History history) {
        validateHistory(b,history);
        required(latest);validateReceipt(r);Map<String,Object> f="CONNECTED".equals(latest.get("event_type"))?latest:history.fact(r.get("binding_generation_fact_id"));validateHolder(f,r);
        check(same(f.get("runtime_binding_id"),b.get("runtime_binding_id"))&&same(f.get("binding_generation"),b.get("binding_generation"))&&same(f.get("registration_receipt_id"),b.get("current_registration_receipt_id"))&&same(f.get("registration_receipt_sha256"),b.get("current_registration_receipt_sha256")));
        if("ACTIVE".equals(b.get("status")))check("CONNECTED".equals(latest.get("event_type"))&&same(f.get("session_fence"),b.get("current_session_fence"))&&same(f.get("socket_owner_instance_id"),b.get("socket_owner_instance_id"))&&same(f.get("socket_owner_epoch"),b.get("socket_owner_epoch")));
        else check("DISCONNECTED".equals(latest.get("event_type"))&&same(latest.get("previous_fact_id"),f.get("binding_generation_fact_id")));
        for(String name:List.of("runtime_instance_id","tenant_id","client_id","data_domain_id","execution_pool_id","owner_jiacn","agent_id","claimant_deployment_id","claimant_worker_generation","receiver_registration_revision","admission_epoch","module_id","migration_epoch","registration_subject_sha256","registration_authority_id","registration_authority_revision","process_public_key_sha256","verifier_version"))check(same(f.get(name),b.get(name)));
        // Routing renewal changes only projection lease/version, not immutable holder lease/hash.
    }
    private void validateHistory(Map<String,Object> binding,History history) {
        String previous=null;long sequence=0,generation=-1,effective=0;Map<String,Object> priorReceipt=null,connected=null;
        for(var f:history.chain()) {
            check("R2B1_ENFORCED".equals(f.get("evidence_provenance"))&&same(f.get("runtime_binding_id"),binding.get("runtime_binding_id"))&&number(f,"fact_sequence")==sequence++&&same(f.get("previous_fact_id"),previous)&&number(f,"effective_at")>=effective);effective=number(f,"effective_at");previous=text(f,"binding_generation_fact_id");
            if("CONNECTED".equals(f.get("event_type"))) {
                check(connected==null&&number(f,"binding_generation")==++generation);var receipt=required(history.receipts().get(f.get("registration_receipt_id")));validateReceipt(receipt);validateHolder(f,receipt);
                check(same(f.get("registration_subject_sha256"),binding.get("registration_subject_sha256"))&&same(f.get("registration_authority_id"),binding.get("registration_authority_id"))&&same(f.get("runtime_instance_id"),binding.get("runtime_instance_id"))&&same(f.get("process_public_key_sha256"),binding.get("process_public_key_sha256")));
                check(priorReceipt==null?receipt.get("previous_registration_receipt_id")==null&&receipt.get("previous_registration_receipt_sha256")==null:same(receipt.get("previous_registration_receipt_id"),priorReceipt.get("registration_receipt_id"))&&same(receipt.get("previous_registration_receipt_sha256"),priorReceipt.get("receipt_wire_sha256")));priorReceipt=receipt;connected=f;
            } else {
                check("DISCONNECTED".equals(f.get("event_type"))&&connected!=null&&number(f,"binding_generation")==generation&&(f.get("reason")==null||List.of("SOCKET_CLOSED","PROTOCOL_CLOSED","ROUTING_LEASE_EXPIRED","REPLACED_BY_AUTHENTICATED_RECONNECT").contains(f.get("reason"))));
                for(String name:List.of("runtime_instance_id","tenant_id","client_id","data_domain_id","execution_pool_id","owner_jiacn","agent_id","claimant_deployment_id","claimant_worker_generation","receiver_registration_revision","admission_epoch","module_id","migration_epoch","session_fence","socket_owner_instance_id","socket_owner_epoch"))check(same(f.get(name),connected.get(name)));connected=null;
            }
        }
        check(sequence>0&&generation==number(binding,"binding_generation"));
    }
    private void validateOrdinaryDelta(Authority a,Map<String,Object> b) {check(!a.frozenRequired()&&same(b.get("claimant_worker_generation"),a.deployment().get("worker_generation"))&&same(b.get("admission_epoch"),a.tenant().get("admission_epoch"))&&same(b.get("migration_epoch"),a.module().get("migration_epoch"))&&number(a.registration(),"authority_revision")>=number(b,"registration_authority_revision")&&number(a.deployment(),"registration_revision")>=number(b,"receiver_registration_revision")); }
    private void validateFrozen(Authority a,Map<String,Object> b,Drain drain,History history) {
        required(b);Map<String,Object> g=drain.completion(),f=history.fact(g.get("frozen_connected_fact_id"));Map<String,Object> r=required(history.receipts().get(g.get("frozen_registration_receipt_id")));validateReceipt(r);validateHolder(f,r);
        check(same(f.get("generation_snapshot_sha256"),g.get("frozen_connected_fact_sha256"))&&same(r.get("receipt_wire_sha256"),g.get("frozen_registration_receipt_sha256"))&&number(f,"effective_at")<number(drain.operation(),"cutoff_at"));
        for(String name:List.of("runtime_binding_id","runtime_instance_id","process_public_key_sha256","registration_subject_sha256","registration_authority_id","claimant_deployment_id","claimant_worker_generation"))check(same(g.get(name),b.get(name)));
        for(String name:List.of("runtime_binding_id","runtime_instance_id","process_public_key_sha256","registration_subject_sha256","registration_authority_id","claimant_deployment_id","claimant_worker_generation"))check(same(g.get(name),f.get(name)));
        List<Map<String,Object>> chain=history.chain();String previous=null;long sequence=0;boolean found=false;long generation=-1;Map<String,Object> previousReceipt=null;
        for(Map<String,Object> item:chain) {check(same(item.get("previous_fact_id"),previous)&&number(item,"fact_sequence")==sequence++);previous=text(item,"binding_generation_fact_id");check(!"REVOKED".equals(item.get("event_type")));
            if(same(item.get("binding_generation_fact_id"),f.get("binding_generation_fact_id")))found=true;
            if("CONNECTED".equals(item.get("event_type"))){Map<String,Object> itemReceipt=required(history.receipts().get(item.get("registration_receipt_id")));validateReceipt(itemReceipt);validateHolder(item,itemReceipt);check(same(item.get("registration_subject_sha256"),a.subjectHash()));if(previousReceipt==null)check(itemReceipt.get("previous_registration_receipt_id")==null&&itemReceipt.get("previous_registration_receipt_sha256")==null);else check(same(itemReceipt.get("previous_registration_receipt_id"),previousReceipt.get("registration_receipt_id"))&&same(itemReceipt.get("previous_registration_receipt_sha256"),previousReceipt.get("receipt_wire_sha256")));previousReceipt=itemReceipt;}
            if("CONNECTED".equals(item.get("event_type"))) {check(number(item,"binding_generation")==++generation&&number(item,"verifier_version")==Math.addExact(generation,1));if(found){for(String name:List.of("tenant_id","client_id","data_domain_id","execution_pool_id","owner_jiacn","agent_id","runtime_instance_id","process_public_key_sha256","claimant_deployment_id","claimant_worker_generation","receiver_registration_revision","admission_epoch","module_id","migration_epoch"))check(same(item.get(name),f.get(name)));if(!same(item.get("binding_generation_fact_id"),f.get("binding_generation_fact_id")))check("FROZEN_DRAIN".equals(item.get("connection_admission_kind"))&&same(item.get("drain_control_operation_id"),drain.operation().get("drain_operation_id")));}}
        }
        check(found&&same(previous,required(history.latest()).get("binding_generation_fact_id")));
    }
    public static AuthenticatedHolderGenerationSnapshot holder(Map<String,Object> f) {return new AuthenticatedHolderGenerationSnapshot(text(f,"binding_generation_fact_id"),number(f,"fact_sequence"),(String)f.get("previous_fact_id"),text(f,"runtime_binding_id"),number(f,"binding_generation"),text(f,"runtime_instance_id"),bytes(f,"registration_subject_sha256"),text(f,"registration_authority_id"),number(f,"registration_authority_revision"),text(f,"tenant_id"),text(f,"data_domain_id"),text(f,"execution_pool_id"),text(f,"runtime_credential_client_id"),text(f,"owner_jiacn"),text(f,"agent_id"),text(f,"claimant_deployment_id"),number(f,"claimant_worker_generation"),number(f,"receiver_registration_revision"),number(f,"admission_epoch"),text(f,"module_id"),number(f,"migration_epoch"),bytes(f,"process_public_key_sha256"),number(f,"verifier_version"),text(f,"registration_receipt_id"),bytes(f,"registration_receipt_sha256"),text(f,"connection_admission_kind"),(String)f.get("drain_control_operation_id"),text(f,"session_fence"),text(f,"socket_owner_instance_id"),number(f,"socket_owner_epoch"),number(f,"socket_owner_lease_until"),number(f,"effective_at"));}
    private static AgentRuntimeV2RegistrationCodec.Receipt receipt(Map<String,Object> r) { return new AgentRuntimeV2RegistrationCodec.Receipt(text(r,"registration_receipt_id"),text(r,"receipt_kind"),text(r,"registration_request_id"),(String)r.get("previous_registration_receipt_id"),(byte[])r.get("previous_registration_receipt_sha256"),text(r,"runtime_binding_id"),number(r,"binding_generation"),text(r,"binding_generation_fact_id"),text(r,"runtime_instance_id"),bytes(r,"process_public_key_sha256"),number(r,"verifier_version"),bytes(r,"verifier_sha256"),(byte[])r.get("server_ephemeral_public_key_spki"),(byte[])r.get("nonce"),(byte[])r.get("sealed_verifier"),text(r,"tenant_id"),text(r,"data_domain_id"),text(r,"execution_pool_id"),text(r,"runtime_credential_client_id"),text(r,"owner_jiacn"),text(r,"agent_id"),text(r,"registration_authority_id"),number(r,"registration_authority_revision"),text(r,"claimant_deployment_id"),number(r,"claimant_worker_generation"),number(r,"receiver_registration_revision"),number(r,"admission_epoch"),text(r,"module_id"),number(r,"migration_epoch"),number(r,"connected_at")); }
    private Map<String,Map<String,Object>> lockReceipts(List<String> ids) {Map<String,Map<String,Object>> map=new HashMap<>();ids.stream().filter(Objects::nonNull).distinct().sorted().forEach(id->map.put(id,db.lock(RECEIPT,row("registration_receipt_id",id))));return map;}
    private History lockHistory(Map<String,Object> binding,List<String> extraReceipts) {
        List<Map<String,Object>> chain=binding==null?List.of():db.factChain(text(binding,"runtime_binding_id"));Map<String,Map<String,Object>> facts=new HashMap<>();List<String> ids=new ArrayList<>(extraReceipts);
        for(Map<String,Object> f:chain){facts.put(text(f,"binding_generation_fact_id"),f);if(f.get("registration_receipt_id")!=null)ids.add(text(f,"registration_receipt_id"));}
        if(binding!=null&&binding.get("current_registration_receipt_id")!=null)ids.add(text(binding,"current_registration_receipt_id"));
        return new History(chain,facts,lockReceipts(ids));
    }
    private Result operation(String kind,String id,Supplier<Result> work) { outside();try{return retry(()->tx.execute(status->work.get()));}catch(SecurityException denied){return reject(kind,id,"NOT_AUTHORIZED");} }
    // Initial attempt + at most two retries. TransactionTemplate has rolled back and
    // cleaned its resources/synchronizations before either eligible exception escapes.
    private <T>T retry(Supplier<T> work) {for(int attempt=0;;attempt++){outside();try{return work.get();}catch(DuplicateKeyException|PessimisticLockingFailureException race){outside();if(attempt>=2)throw race;}}}
    private void beforeCommitTime(LongConsumer validation) {
        // Registered after the final write, with
        // default LOWEST_PRECEDENCE. No DB/crypto/network work occurs in this callback:
        // this is the fresh application precommit boundary, not physical commit time.
        // A validation exception propagates through TransactionTemplate and rolls back
        // all consume/fact/verifier/receipt/mapping/projection changes, before delivery.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            @Override public void beforeCommit(boolean readOnly){validation.accept(now());}
        });
    }
    public static void outside() {if(TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isSynchronizationActive())throw new IllegalStateException("registration/network call inside ambient transaction");}
    private long now(){long n=clock.millis();if(n<=0)throw new IllegalStateException("invalid server time");return n;}
    private static boolean apiKey(AgentRuntimeV2RegistrationContext identity){return identity!=null&&"API_KEY".equals(identity.credentialKind());}
    private static void checkIdentityTime(Authority authority,long now){check(authority.credentialExpiresAt()==null||now<authority.credentialExpiresAt());}
    private static void checkAdmissionTime(Authority authority,Drain drain,long now){checkIdentityTime(authority,now);if(drain!=null)check(now>=number(drain.operation(),"issued_at")&&now<number(drain.operation(),"deadline_at"));}
    private byte[] proof(){byte[] bytes=new byte[32];random.nextBytes(bytes);return bytes;}
    private static String uuid(){return UUID.randomUUID().toString();}
    private static void socketInputs(String fence,String owner,long epoch){new AgentRuntimeV2SocketTuple(uuid(),0,uuid(),fence,owner,epoch);}
    private static Result reject(String kind,String id,String reason){return new Result(AgentRuntimeV2RegistrationCodec.encodeRejected(new AgentRuntimeV2RegistrationCodec.Rejected(kind,id,null,reason)),null,null);}
    private static byte[] deliveryBytes(Map<String,Object> r,String result,String disposition){return AgentRuntimeV2RegistrationCodec.encodeReceiptDelivery(new AgentRuntimeV2RegistrationCodec.ReceiptDelivery(result,text(r,"receipt_kind"),text(r,"registration_request_id"),disposition,(byte[])r.get("receipt_wire_bytes"),bytes(r,"receipt_wire_sha256")));}
    private Result delivery(Map<String,Object> r,String result,Map<String,Object> b,String fence,String owner,long epoch,AgentRuntimeV2SocketTuple replaced){boolean current="ACTIVE".equals(b.get("status"))&&same(r.get("binding_generation"),b.get("binding_generation"))&&same(r.get("registration_receipt_id"),b.get("current_registration_receipt_id"))&&same(fence,b.get("current_session_fence"))&&same(owner,b.get("socket_owner_instance_id"))&&same(epoch,b.get("socket_owner_epoch"))&&number(b,"socket_owner_lease_until")>now();return new Result(deliveryBytes(r,result,current?"CURRENT":"RECONNECT_REQUIRED"),current?tuple(b):null,replaced);}
    private static boolean matches(Map<String,Object> b,AgentRuntimeV2SocketTuple s){return b!=null&&"ACTIVE".equals(b.get("status"))&&number(b,"contract_version")==2&&"R2B1_ENFORCED".equals(b.get("evidence_provenance"))&&same(b.get("runtime_binding_id"),s.runtimeBindingId())&&same(b.get("binding_generation"),s.bindingGeneration())&&same(b.get("runtime_instance_id"),s.runtimeInstanceId())&&same(b.get("current_session_fence"),s.sessionFence())&&same(b.get("socket_owner_instance_id"),s.socketOwnerInstanceId())&&same(b.get("socket_owner_epoch"),s.socketOwnerEpoch());}
    private static AgentRuntimeV2SocketTuple tuple(Map<String,Object> b){return new AgentRuntimeV2SocketTuple(text(b,"runtime_binding_id"),number(b,"binding_generation"),text(b,"runtime_instance_id"),text(b,"current_session_fence"),text(b,"socket_owner_instance_id"),number(b,"socket_owner_epoch"));}
    // Fixed complete projections, not cached receipt or validation results.
    // Call the actual typed accessors, including their defensive byte clones.
    private static Map<String,Object> record(Record record){
        Map<String,Object> map=new LinkedHashMap<>();
        if(record instanceof AgentRuntimeV2RegistrationCodec.Challenge v){
            map.put("challenge_request_id",v.challengeRequestId());
            map.put("challenge_id",v.challengeId());
            map.put("runtime_binding_id",v.runtimeBindingId());
            map.put("binding_generation",v.bindingGeneration());
            map.put("runtime_instance_id",v.runtimeInstanceId());
            map.put("prior_registration_receipt_id",v.priorRegistrationReceiptId());
            map.put("prior_registration_receipt_sha256",v.priorRegistrationReceiptSha256());
            map.put("server_ephemeral_public_key_spki",v.serverEphemeralPublicKeySpki());
            map.put("nonce",v.nonce());
            map.put("sealed_challenge",v.sealedChallenge());
            map.put("challenge_sha256",v.challengeSha256());
            map.put("expires_at",v.expiresAt());
        }else if(record instanceof AgentRuntimeV2RegistrationCodec.Receipt v){
            map.put("registration_receipt_id",v.registrationReceiptId());
            map.put("receipt_kind",v.receiptKind());
            map.put("registration_request_id",v.registrationRequestId());
            map.put("previous_registration_receipt_id",v.previousRegistrationReceiptId());
            map.put("previous_registration_receipt_sha256",v.previousRegistrationReceiptSha256());
            map.put("runtime_binding_id",v.runtimeBindingId());
            map.put("binding_generation",v.bindingGeneration());
            map.put("binding_generation_fact_id",v.bindingGenerationFactId());
            map.put("runtime_instance_id",v.runtimeInstanceId());
            map.put("process_public_key_sha256",v.processPublicKeySha256());
            map.put("verifier_version",v.verifierVersion());
            map.put("verifier_sha256",v.verifierSha256());
            map.put("server_ephemeral_public_key_spki",v.serverEphemeralPublicKeySpki());
            map.put("nonce",v.nonce());
            map.put("sealed_verifier",v.sealedVerifier());
            map.put("tenant_id",v.tenantId());
            map.put("data_domain_id",v.dataDomainId());
            map.put("execution_pool_id",v.executionPoolId());
            map.put("runtime_credential_client_id",v.runtimeCredentialClientId());
            map.put("owner_jiacn",v.ownerJiacn());
            map.put("agent_id",v.agentId());
            map.put("registration_authority_id",v.registrationAuthorityId());
            map.put("registration_authority_revision",v.registrationAuthorityRevision());
            map.put("claimant_deployment_id",v.claimantDeploymentId());
            map.put("claimant_worker_generation",v.claimantWorkerGeneration());
            map.put("receiver_registration_revision",v.receiverRegistrationRevision());
            map.put("admission_epoch",v.admissionEpoch());
            map.put("module_id",v.moduleId());
            map.put("migration_epoch",v.migrationEpoch());
            map.put("connected_at",v.connectedAt());
        }else throw new IllegalArgumentException("unsupported registration projection type");
        return map;
    }
}
