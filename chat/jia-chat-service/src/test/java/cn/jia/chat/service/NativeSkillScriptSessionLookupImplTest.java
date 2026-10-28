package cn.jia.chat.service;

import cn.jia.agent.service.*;
import cn.jia.chat.handler.TypedDeliberationSessionRegistry;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NativeSkillScriptSessionLookupImplTest {
    @Test void deliveryRequiresSameCurrentFenceAndExplicitSessionPolicy() {
        var policies=mock(TaskSkillPolicyService.class);var runtimes=mock(SkillCapabilityLookup.class);
        var sessions=new TypedDeliberationSessionRegistry();var adapter=new NativeSkillScriptSessionLookupImpl(policies,runtimes,sessions);
        var key=new SkillCapabilityLookup.Scope("0","client","owner","agent");
        var fence=new AgentManagedSessionLookup.SessionFence("install","host","instance",1);
        var policy=new TaskSkillPolicyService.Snapshot(true,null,1,fence,key);
        when(policies.read(key,"task")).thenReturn(policy);
        when(runtimes.lookup(key,fence)).thenReturn(new SkillCapabilityLookup.View(SkillCapabilityLookup.State.AVAILABLE,"session",fence,new SkillCapabilityLookup.Catalog("1",List.of())));
        var scope=new PersonalWorkspaceExecutionService.RuntimeScope("0","client","owner","agent","instance");
        var nativeExecution=new java.util.LinkedHashMap<String,Object>(Map.of("schemaVersion",1,"executionProtocolVersion",5,"toolPolicyRef","NATIVE_LOCAL_FILES_V1","outputMimeTypes",List.of("text/plain")));
        var declaration=new java.util.LinkedHashMap<String,Object>(Map.of("schemaVersion",5,"state","READY","carrier","CHAT_MESSAGE_FINAL_SIDECAR_V5", "referenceModes",List.of("NONE","AVAILABLE"),"outcomeKinds",List.of("ANSWER","CLARIFY","ACTION_REQUEST","REVISION_REQUEST","SUBMISSION_REQUEST"),"engine","CODEX_APP_SERVER_NATIVE_OUTPUT_SCHEMA","strictNoToolsVerified",false,"toolPolicy","read-only-constrained"));
        declaration.put("nativeExecution",nativeExecution);sessions.register("session","0","owner","client","agent",declaration);
        assertFalse(adapter.supports(scope,"task"));
        nativeExecution.put("supportedToolPolicyRefs",List.of("NATIVE_SKILL_SCRIPT_V1"));sessions.register("session","0","owner","client","agent",declaration);
        assertTrue(adapter.supports(scope,"task"));
        assertFalse(adapter.supports(new PersonalWorkspaceExecutionService.RuntimeScope("0","client","owner","agent","other"),"task"));
        sessions.remove("session");assertFalse(adapter.supports(scope,"task"));
    }
}
