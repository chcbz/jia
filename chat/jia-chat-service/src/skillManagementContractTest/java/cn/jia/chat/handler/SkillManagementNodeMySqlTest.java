package cn.jia.chat.handler;

import cn.jia.agent.entity.AgentRawCommandDispatchResult;
import cn.jia.agent.service.SkillCapabilityLookup;
import cn.jia.agent.service.impl.AgentCommandCanonicalCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static cn.jia.agent.skill.SkillManagementCodec.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real HTTP adapter -> MySQL/outbox -> Node manager/registry -> actual Java WS consumer.
 * Servlet/socket/authenticated identity remain explicit adapters. No browser, model or paid IO.
 * Both tests use ordinary instruction packages; no executable runner readiness is fabricated. */
public class SkillManagementNodeMySqlTest extends SkillManagementWireMySqlTest {
    @TempDir Path nodeScratch;

    private boolean pdfFirstInstall;
    @Override protected Map<String,Object> quoteData() {
        if(!pdfFirstInstall)return super.quoteData();
        return map("action","INSTALL","agentId",AGENT,"skillKey","pdf-export","sourceSkillInstallationId",null,"targetProductVersionId","spv_pdf_1",
            "expectedAgentVersion",Long.toString(versions.readOwned(ACTOR,AGENT)),"expectedSkillRevision","0","approvedPermissions",List.of());
    }
    private final class Peer implements AutoCloseable {
        final Process process;
        final BufferedWriter input;
        final BufferedReader output;
        final Path diagnostics;
        int sequence;
        Peer() throws Exception {
            String binary=System.getenv("CYF_SM_NODE_BIN"),entry=System.getenv("CYF_SM_NODE_PEER");
            assertNotNull(binary,"Required absolute CYF_SM_NODE_BIN; no skipped Node evidence");
            assertNotNull(entry,"Required absolute CYF_SM_NODE_PEER at pinned Runtime source");
            Path executable=Path.of(binary),script=Path.of(entry);
            assertTrue(executable.isAbsolute()&&Files.isExecutable(executable));assertTrue(script.isAbsolute()&&Files.isRegularFile(script));
            String entrySha=HexFormat.of().formatHex(AgentCommandCanonicalCodec.sha256(Files.readAllBytes(script)));
            assertEquals("0e41d4029785ba5cd014d48b2df2a7dfb7c1b7b2a8b845e964d2ddbc94d8d7c3",entrySha,"Pinned native Runtime peer input changed");
            System.out.println("CYF_SM_NODE_PEER runtimeCommit=c3f973c2e5ca2990dd3e775b42f6bf02f7e0a060 entrySha256="+entrySha);
            diagnostics=nodeScratch.resolve("node-stderr.log");
            process=new ProcessBuilder(executable.toString(),script.toString()).redirectError(diagnostics.toFile()).start();
            input=new BufferedWriter(new OutputStreamWriter(process.getOutputStream(),StandardCharsets.UTF_8));
            output=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8));
        }
        List<Map<String,Object>> call(Map<String,Object> operation) throws Exception {
            var request=new LinkedHashMap<>(operation);String id="java-control-"+(++sequence);request.put("id",id);
            input.write(json(request));input.newLine();input.flush();var rows=new ArrayList<Map<String,Object>>();
            while(true) {
                String line=output.readLine();assertNotNull(line,"Node peer exited; diagnostics="+diagnostics);
                var row=read(line.getBytes(StandardCharsets.UTF_8),null);rows.add(row);
                assertNotEquals("error",row.get("kind"),()->"Node rejected control: "+row);
                if("done".equals(row.get("kind"))){assertEquals(id,row.get("id"));return rows;}
            }
        }
        List<Map<String,Object>> receive(byte[] wire) throws Exception {
            return call(map("op","receive","wireBase64",Base64.getEncoder().encodeToString(wire)));
        }
        @Override public void close() throws Exception {
            try {input.close();} finally {
                // Only our own child process is controlled. EOF normally terminates the bridge.
                if(process.isAlive())process.destroy();process.waitFor();output.close();
            }
        }
    }
    private final class NodeSession implements AutoCloseable {
        final Peer peer;
        final Wire handler;
        final WebSocketSession socket;
        final List<String> frames=new ArrayList<>();
        final Set<String> dispatched=new LinkedHashSet<>();
        int results,receipts;
        NodeSession(boolean executableProbes) throws Exception {
            peer=new Peer();handler=wire();var registry=new SkillExecutionSessionRegistry();handler.setSkillExecutionSessions(registry);
            when(capabilities.lookup(any(),any())).thenAnswer(call->registry.lookup(call.getArgument(0),call.getArgument(1)));
            socket=socket(frames);
            try {
            register(handler,socket,null,frames);
            var initialized=peer.call(map("op","init","root",nodeScratch.resolve("runtime").toAbsolutePath().toString(),
                "apiOrigin","http://127.0.0.1","profileId","java-owned-mysql-runtime",
                "scope",map("tenantId","0","clientId",ACTOR.clientId(),"ownerJiacn",ACTOR.ownerJiacn(),"agentId",AGENT),
                "fence",map("installationId",runtime.getRuntimeInstallationId(),"hostId","host-1","runtimeInstanceId","runtime-1","sessionGeneration","1"),
                "packages",List.of(),"testExecutableReadiness",executableProbes));
            assertEquals(executableProbes?"TEST_PROBES_NO_EXECUTION":"NO_EXECUTABLE_RUNNERS",initialized.getFirst().get("dependencyBoundary"));
            consume(initialized);
            when(sessions.dispatch(anyString(),anyString(),anyString(),any(),any())).thenAnswer(call->{
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),"Node transport IO must be after commit");
                byte[] original=call.getArgument(4);var envelope=read(original,"Command");String command=text(object(envelope.get("data")),"commandId");
                assertArrayEquals(original,jdbc.queryForObject("SELECT wire_payload FROM agent_outbox_event WHERE command_id=?",byte[].class,command));
                assertArrayEquals(AgentCommandCanonicalCodec.sha256(original),jdbc.queryForObject("SELECT wire_payload_hash FROM agent_outbox_event WHERE command_id=?",byte[].class,command));
                dispatched.add(command);consume(peer.receive(original));return AgentRawCommandDispatchResult.sent(1,1);
            });
            } catch(Exception|AssertionError failure) {
                try {peer.close();} catch(Exception cleanup){failure.addSuppressed(cleanup);}throw failure;
            }
        }
        void consume(List<Map<String,Object>> rows) throws Exception {
            var committed=new ArrayList<byte[]>();
            for(var row:rows) {
                String kind=text(row,"kind");
                if(Set.of("ack","result").contains(kind)) {
                    byte[] original=Base64.getDecoder().decode(text(row,"wireBase64"));int before=frames.size();
                    handler.raw(socket,new String(original,StandardCharsets.UTF_8));
                    if("result".equals(kind)) {
                        results++;assertEquals(before+1,frames.size(),()->"Actual result must yield one post-commit receipt; result="+new String(original,StandardCharsets.UTF_8)+" replies="+frames.subList(before,frames.size()));
                        byte[] receipt=frames.getLast().getBytes(StandardCharsets.UTF_8);var accepted=read(receipt,null);
                        assertEquals("E0",accepted.get("code"),()->"Result rejected; result="+new String(original,StandardCharsets.UTF_8)+" reply="+new String(receipt,StandardCharsets.UTF_8));
                        validate("ResultReceipt",accepted);assertEquals("ACCEPTED",object(accepted.get("data")).get("receiptStatus"));
                        var event=read(original,"Result");assertEquals(event.get("messageId"),accepted.get("correlationId"));
                        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM economy_skill_management_result WHERE message_id=?",Integer.class,event.get("messageId")));
                        committed.add(receipt);receipts++;
                    } else {
                        assertEquals(before,frames.size(),()->"Valid ACK must not produce an error reply; ack="+new String(original,StandardCharsets.UTF_8)+" replies="+frames.subList(before,frames.size()));
                        read(original,"CommandAck"); // also validate the exact, unmodified peer bytes
                    }
                } else if("catalog".equals(kind)) {
                    var adapter=object(row.get("value"));validate("CapabilityAdapterInput",adapter);
                    assertEquals(AGENT,adapter.get("agentId"));assertEquals(runtime.getRuntimeInstallationId(),adapter.get("runtimeInstallationId"));
                    register(handler,socket,adapter.get("skillExecution"),frames);
                }
            }
            // Feed the actual durable Java receipts back to the real Runtime journal.
            for(byte[] receipt:committed)consume(peer.receive(receipt));
        }
        void supply(Map<String,Object> change) throws Exception {
            String installation=text(object(change.get("target")),"skillInstallationId");
            byte[] archive=service.packageBytes(proof(),installation); // real authenticated package service
            peer.call(map("op","putPackage","skillInstallationId",installation,"base64",Base64.getEncoder().encodeToString(archive)));
        }
        Map<String,Object> complete(Map<String,Object> change,boolean packageRequired) throws Exception {
            if(packageRequired)supply(change);
            // Bounded phase count, not a wait/retry loop. PREPARE creates ACTIVATE transactionally.
            recovery.recover();if(Set.of("INSTALL","UPGRADE").contains(change.get("action")))recovery.recover();
            var completed=current(change);assertEquals("SUCCEEDED",completed.get("status"),()->json(completed));assertEquals(true,completed.get("runtimeSynced"));return completed;
        }
        @Override public void close() throws Exception {try{handler.afterConnectionClosed(socket,CloseStatus.NORMAL);}finally{peer.close();}}
    }
    @Test void firstInstallWithRealNodePeer() throws Exception {
        try(var node=new NodeSession(false)) {
            var accepted=installOverHttp();assertEquals("INSTALL",accepted.get("action"));var completed=node.complete(accepted,true);
            assertEquals("CAPTURED",completed.get("fundingStatus"));assertEquals(2,node.results);assertEquals(2,node.receipts);assertEquals(2,node.dispatched.size());
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM economy_skill_order",Integer.class));
            assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM agent_outbox_event",Integer.class));
            var replay=node.peer.call(map("op","replay"));assertTrue(replay.stream().noneMatch(r->"result".equals(r.get("kind"))),"Committed receipts must retire real Node result outbox");
            node.consume(replay);
        }
    }
    @Test void realNativeNodeUpgradeAndPolicyKeepOldTaskPinnedWithoutBorrowingCurrent() throws Exception {
        pdfFirstInstall=true;publishInstruction("1.0.0",1);
        try(var node=new NodeSession(false)) {
            var first=node.complete(installOverHttp(),true);String old=text(object(first.get("target")),"skillInstallationId");
            var binding=service.admitNative(scope(),"pdf-export",old,()->service.nativeTaskBinding("0",ACTOR.clientId(),"old-execution","old-grant",1,AGENT,old));
            publishInstruction("2.0.0",2);
            jdbc.update("INSERT INTO economy_skill_upgrade_policy VALUES (?,?,?,?,?)","0",ACTOR.clientId(),"spv_pdf_1","spv_pdf_2","COVERED");
            var upgrade=packageChange("UPGRADE",old,text(first,"skillRevision"),"spv_pdf_2");node.supply(upgrade);
            assertThrows(cn.jia.agent.skill.SkillMarketplaceException.class,()->service.admitNative(scope(),"pdf-export",old,()->"never"));
            recovery.recover();assertEquals("ACTIVATING",current(upgrade).get("status"));
            assertThrows(cn.jia.agent.skill.SkillMarketplaceException.class,()->service.admitNative(scope(),"pdf-export",old,()->"never"));
            recovery.recover();upgrade=current(upgrade);assertEquals("SUCCEEDED",upgrade.get("status"));
            assertNotEquals(old,object(upgrade.get("target")).get("skillInstallationId"));
            assertEquals(binding,service.nativeTaskBinding("0",ACTOR.clientId(),"old-execution","old-grant",1,AGENT,old));
            var disabled=policyChange("DISABLE",upgrade);String active=text(object(upgrade.get("target")),"skillInstallationId");
            assertThrows(cn.jia.agent.skill.SkillMarketplaceException.class,()->service.admitNative(scope(),"pdf-export",active,()->"never"));
            disabled=node.complete(disabled,false);assertEquals(false,disabled.get("desiredEnabled"));
            var resolved=node.peer.call(map("op","resolveNativeTask","taskSkillBinding",binding));
            var exact=object(resolved.stream().filter(r->"taskPackage".equals(r.get("kind"))).findFirst().orElseThrow().get("value"));
            for(String field:List.of("skillInstallationId","productVersionId","skillKey","skillVersion","packageSha256","manifestSha256","approvedPermissionsSha256"))
                assertEquals(binding.get(field),exact.get(field),field);
            var enabled=node.complete(policyChange("ENABLE",disabled),false);
            assertEquals(active,object(enabled.get("target")).get("skillInstallationId"));
            assertEquals("new-admission",service.admitNative(scope(),"pdf-export",active,()->"new-admission"));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM economy_skill_order",Integer.class),"COVERED upgrade and policy commands cannot charge again");
            assertEquals(6,node.dispatched.size());assertEquals(6,node.results);assertEquals(node.results,node.receipts);
        }
    }

}
