package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentIdentityRegistryDao;
import cn.jia.agent.dao.AgentRuntimeV1InstallationDao;
import cn.jia.agent.entity.*;
import cn.jia.agent.security.AgentRuntimeAuthentication;
import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import cn.jia.agent.service.AgentCommandAckService;
import cn.jia.agent.service.AgentIdentityService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.format.DateTimeFormatterBuilder;
import java.util.HexFormat;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** r2 cross-end fixture contract + real HTTP service/D06, mocked persistence/transport only.
 * Projection here is a test oracle, not a second production dispatcher or authority source. */
class AgentRuntimeCanonicalDispatchProjectionTest {
    private final ObjectMapper json = new ObjectMapper();
    private byte[] rawBytes() throws Exception {
        try (var in = getClass().getResourceAsStream("/ur02/command-dispatch-e05.canonical.redacted.json")) {
            assertNotNull(in); return in.readAllBytes();
        }
    }
    private JsonNode contract() throws Exception {
        try (var in = getClass().getResourceAsStream("/ur02/unified-runtime-wire-v2.redacted.json")) {
            assertNotNull(in); return json.readTree(in).path("canonicalDispatchProjectionR2");
        }
    }
    private AgentCommandDraft draft(JsonNode raw) {
        ObjectNode business = ((ObjectNode) raw).deepCopy();
        business.remove("messageType"); business.remove("messageId"); business.remove("attempt");
        return AgentCommandCanonicalCodec.decodeBusinessBytes(json.writeValueAsBytes(business));
    }
    private AgentRuntimeV1AckRequest project(JsonNode raw, JsonNode manifest, JsonNode transport) {
        if (!raw.path("tenantId").equals(manifest.path("tenantId"))
                || !raw.path("clientId").equals(manifest.path("clientId"))
                || !raw.path("targetAgentId").equals(manifest.path("canonicalAgentId")))
            throw new IllegalArgumentException("SUBJECT_MISMATCH");
        JsonNode expiry = raw.path("expiresAt");
        if (!expiry.isIntegralNumber() || !expiry.canConvertToLong() || expiry.asLong() < -8_640_000_000_000_000L
                || expiry.asLong() > 8_640_000_000_000_000L)
            throw new IllegalArgumentException("UNSAFE_EXPIRY");
        return new AgentRuntimeV1AckRequest(raw.path("messageId").asText(), raw.path("correlationId").asText(),
                raw.path("commandId").asText(), raw.path("taskId").asText(),
                raw.path("workItemId").isNull() ? null : raw.path("workItemId").asText(),
                manifest.path("tenantId").asText(), manifest.path("clientId").asText(),
                manifest.path("canonicalAgentId").asText(), null,
                new DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(Instant.ofEpochMilli(expiry.asLong())),
                "RECEIVED", transport.path("installationId").asText(), transport.path("hostId").asText(),
                transport.path("runtimeInstanceId").asText(), transport.path("sessionGeneration").asLong(), null);
    }
    @Test void realCanonicalBytesMatchR2AndProjectWithoutInventingReferences() throws Exception {
        byte[] bytes = rawBytes(); var raw = json.readTree(bytes); var c = contract();
        assertEquals(c.path("raw"), raw);
        assertEquals(c.path("rawSha256").asText(), HexFormat.of().formatHex(AgentCommandCanonicalCodec.sha256(bytes)));
        assertArrayEquals(bytes, AgentCommandCanonicalCodec.wireBytes(draft(raw),raw.path("messageId").asText(),1));
        var request = project(raw,c.path("trustedManifest"),c.path("currentTransport"));
        assertEquals(c.path("projectedFirstAck"),json.valueToTree(request));
        assertNull(request.payloadReference()); assertNull(request.deliveryVersion());
        assertEquals("1970-01-01T01:00:01.000Z",request.expiresAt());
        assertEquals("e05-reassignment-v1",raw.at("/payload/context/bindingVersion").asText());
        assertArrayEquals(bytes,rawBytes());
    }
    @Test void wrongFullSubjectAndUnsafeExpiryCannotProject() throws Exception {
        var c = contract();
        for (var field : new String[]{"tenantId","clientId","targetAgentId"}) {
            ObjectNode raw = (ObjectNode)json.readTree(rawBytes()); raw.put(field,"foreign");
            assertThrows(IllegalArgumentException.class,()->project(raw,c.path("trustedManifest"),c.path("currentTransport")));
        }
        for (var value : new String[]{"null","1.5","9007199254740992","-9007199254740992","8640000000000001","-8640000000000001","\"3601000\""}) {
            ObjectNode raw = (ObjectNode)json.readTree(rawBytes()); raw.set("expiresAt",json.readTree(value));
            assertThrows(IllegalArgumentException.class,()->project(raw,c.path("trustedManifest"),c.path("currentTransport")));
        }
    }
    @Test void legalNonPositiveAndDateBoundaryEpochsProjectWithoutGrantingAdmission() throws Exception {
        var c=contract();
        for(long epoch:new long[]{0,-1,-8_640_000_000_000_000L,8_640_000_000_000_000L}) {
            ObjectNode raw=(ObjectNode)json.readTree(rawBytes());raw.put("expiresAt",epoch);
            var ack=project(raw,c.path("trustedManifest"),c.path("currentTransport"));
            assertEquals(Instant.ofEpochMilli(epoch),Instant.parse(ack.expiresAt()));
            assertEquals(epoch,raw.path("expiresAt").asLong());
        }
    }
    @Test void productInstallationAndReconnectDoNotRewriteBusinessFingerprint() throws Exception {
        var c=contract(); ObjectNode raw=(ObjectNode)json.readTree(rawBytes());
        // Projection must not confuse any product installationId with transport authority.
        raw.put("installationId","product-installation");
        var ack=project(raw,c.path("trustedManifest"),c.path("currentTransport"));
        assertNotEquals(raw.path("installationId").asText(),ack.installationId());
        byte[] immutable=json.writeValueAsBytes(raw); var fingerprint=AgentCommandCanonicalCodec.sha256(immutable);
        ObjectNode rotated=((ObjectNode)c.path("currentTransport")).deepCopy();
        rotated.put("runtimeInstanceId","next-boot"); rotated.put("sessionGeneration",8);
        assertEquals(8,project(raw,c.path("trustedManifest"),rotated).sessionGeneration());
        assertArrayEquals(fingerprint,AgentCommandCanonicalCodec.sha256(json.writeValueAsBytes(raw)));
        ((ObjectNode)raw.path("payload")).put("instruction","different payload, same message");
        assertFalse(java.util.Arrays.equals(fingerprint,AgentCommandCanonicalCodec.sha256(json.writeValueAsBytes(raw))));
        raw.putNull("workItemId"); assertNull(project(raw,c.path("trustedManifest"),rotated).workItemId());
    }
    @SuppressWarnings("unchecked")
    private AgentRuntimeV1ServiceImpl boundary(AgentCommandAckServiceImplTest.Fixture f,AgentRuntimeV1AckRequest ack) {
        var auth=mock(AgentRuntimeAuthenticationService.class);
        var proof=new AgentRuntimeAuthenticationService.Proof(new AgentRuntimeAuthentication.Scope(
                ack.tenantId(),ack.clientId(),f.delivery.getOwnerJiacn(),ack.canonicalAgentId(),ack.runtimeInstanceId()),
                ack.installationId(),ack.hostId(),ack.sessionGeneration(),"redacted-verifier",1,1);
        when(auth.verify(any())).thenReturn(proof);
        doAnswer(call->((Supplier<?>)call.getArgument(2)).get()).when(auth).withFence(eq(proof),eq(false),any());
        ObjectProvider<AgentCommandAckService> provider=mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(f.service);
        return new AgentRuntimeV1ServiceImpl(mock(AgentRuntimeV1InstallationDao.class),mock(AgentIdentityService.class),
                mock(AgentIdentityRegistryDao.class),provider,auth);
    }
    @Test void actualE05FirstHttpAckCommitsD06WithOriginalKeysAtFrozenClock() throws Exception {
        var c=contract(); var raw=json.readTree(rawBytes());
        var ack=project(raw,c.path("trustedManifest"),c.path("currentTransport"));
        var f=new AgentCommandAckServiceImplTest.Fixture("SENT",draft(raw),ack.messageId());
        assertArrayEquals(rawBytes(),f.outbox.getWirePayload());
        var result=boundary(f,ack).acknowledge("rts1_redacted",ack.messageId(),ack,c.path("clockEpochMillis").asLong());
        assertEquals(AgentCommandAckResult.Kind.ADVANCED,result.kind()); assertEquals("RECEIVED",result.status());
        assertEquals(8,result.deliveryVersion()); verify(f.manager).commit(any());
        assertEquals(raw.path("commandId").asText(),f.delivery.getCommandId());
        assertEquals("work-1",f.delivery.getWorkItemId()); verify(f.dao).advanceAck(eq(f.delivery),eq("RECEIVED"),isNull(),eq(1_001_000L));
    }
    @Test void informationalZeroAndNegativeIsoDoNotOverridePersistentAdmissionOrStartedEvidence() throws Exception {
        var c=contract();var original=json.readTree(rawBytes());
        for(long epoch:new long[]{0,-1}) {
            ObjectNode projected=((ObjectNode)original).deepCopy();projected.put("expiresAt",epoch);
            var first=project(projected,c.path("trustedManifest"),c.path("currentTransport"));
            var terminal=new AgentRuntimeV1AckRequest(first.messageId(),first.correlationId(),first.commandId(),
                    first.taskId(),first.workItemId(),first.tenantId(),first.clientId(),first.canonicalAgentId(),null,
                    first.expiresAt(),"SUCCEEDED",first.installationId(),first.hostId(),first.runtimeInstanceId(),
                    first.sessionGeneration(),null);
            // The real source codec retains its positive issuedAt/fixed TTL policy. ACK time is
            // informational; legitimate persisted STARTED evidence alone allows expired terminal reporting.
            var started=new AgentCommandAckServiceImplTest.Fixture("STARTED",draft(original),first.messageId());
            var result=boundary(started,terminal).acknowledge("rts1_redacted",first.messageId(),terminal,3_601_000L);
            assertEquals(AgentCommandAckResult.Kind.ADVANCED,result.kind());assertEquals("SUCCEEDED",result.status());
            verify(started.manager).commit(any());
            var fresh=new AgentCommandAckServiceImplTest.Fixture("SENT",draft(original),first.messageId());
            assertThrows(AgentCommandAckRejectedException.class,()->boundary(fresh,first).acknowledge(
                    "rts1_redacted",first.messageId(),first,3_601_000L));
            verify(fresh.dao,never()).advanceAck(any(),anyString(),any(),anyLong());verify(fresh.manager).rollback(any());
        }
    }
    @Test void nullReferenceNeverBypassesSourceContextOrLeaseEvidence() throws Exception {
        var c=contract(); var raw=json.readTree(rawBytes());
        var ack=project(raw,c.path("trustedManifest"),c.path("currentTransport"));
        for(int variant=0;variant<3;variant++) {
            var f=new AgentCommandAckServiceImplTest.Fixture("SENT",draft(raw),ack.messageId());
            if(variant==0) {
                ObjectNode changed=((ObjectNode)raw).deepCopy();
                ((ObjectNode)changed.at("/payload/context")).put("contextVersion","6");
                byte[] changedWire=AgentCommandCanonicalCodec.wireBytes(draft(changed),ack.messageId(),1);
                f.outbox.setWirePayload(changedWire).setWirePayloadHash(AgentCommandCanonicalCodec.sha256(changedWire));
                f.inbox.setWirePayload(changedWire).setWirePayloadHash(AgentCommandCanonicalCodec.sha256(changedWire));
            }
            if(variant==1) f.outbox.setPublisherConfirmStatus("NACK");
            if(variant==2) {
                f.delivery.setStatus("RECEIVED");
                f.inbox.setStatus("PROCESSING").setResultStatus(null).setProcessedAt(null)
                        .setLeaseOwner("worker").setLeaseUntil(1_001_000L);
            }
            assertThrows(AgentCommandAckRejectedException.class,()->boundary(f,ack).acknowledge(
                    "rts1_redacted",ack.messageId(),ack,1_001_000L));
            verify(f.dao,never()).advanceAck(any(),anyString(),any(),anyLong()); verify(f.manager).rollback(any());
        }
    }
}
