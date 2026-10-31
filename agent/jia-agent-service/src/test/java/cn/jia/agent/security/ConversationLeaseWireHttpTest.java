package cn.jia.agent.security;

import cn.jia.agent.api.PersonalWorkspaceConversationRuntimeController;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.agent.service.SkillExecutionService;
import cn.jia.core.config.*;
import cn.jia.core.filter.RequestIdFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real Q/R decoding and JSON encoding with a trusted Native principal, no production storage. */
class ConversationLeaseWireHttpTest {
    private static final String TOKEN="00000000-0000-0000-0000-000000000001";
    private static final String BASE="/internal/agent/tasks/task/runs/run/conversation";
    private PersonalWorkspaceExecutionService executions;
    private SkillExecutionService skills;
    private AgentRuntimeAuthentication auth;
    private MockMvc mvc;
    private AgentRuntimeAuthenticationService nativeAuth;
    @BeforeEach void setup() {
        executions=mock(PersonalWorkspaceExecutionService.class,withSettings().extraInterfaces(SkillExecutionService.class));
        skills=(SkillExecutionService)executions;
        nativeAuth=mock(AgentRuntimeAuthenticationService.class);
        when(nativeAuth.withNativeFence(any(),any())).thenAnswer(invocation->invocation.getArgument(1,Supplier.class).get());
        auth=new AgentRuntimeAuthentication(new AgentRuntimeAuthentication.Scope("tenant","client","owner","agent","runtime"));
        var converters=new ArrayList<HttpMessageConverter<?>>();
        converters.add(new StringHttpMessageConverter(java.nio.charset.StandardCharsets.UTF_8));
        converters.add(new JacksonJsonHttpMessageConverter());
        new ApiEnvelopeJsonConfig().extendMessageConverters(converters);
        mvc=MockMvcBuilders.standaloneSetup(new PersonalWorkspaceConversationRuntimeController(executions,nativeAuth),new cn.jia.agent.api.PersonalWorkspaceRuntimeFileController(executions,nativeAuth))
                .setControllerAdvice(new ApiEnvelopeAdvice(),new ApiRequestEnvelopeAdvice(),new ExceptionHandlerAdvice())
                .setMessageConverters(converters.toArray(HttpMessageConverter[]::new))
                .addFilters(new RequestIdFilter()).build();
    }
    private org.springframework.test.web.servlet.ResultActions call(String suffix,String data) throws Exception {
        return mvc.perform(post(BASE+suffix).principal(auth).contentType(MediaType.APPLICATION_JSON)
                .header("X-Request-Id","lease-test").content("{\"protocolVersion\":1,\"requestId\":\"lease-test\",\"data\":"+data+"}"));
    }
    private PersonalWorkspaceExecutionService.ConversationLease lease(long version) {
        return new PersonalWorkspaceExecutionService.ConversationLease("execution",version,TOKEN,1_800_000_000_000L);
    }
    @Test void leaseHasExactDecimalVersionAndNumericEpoch() throws Exception {
        when(executions.claimConversationStart(any(),eq("task"),eq("run"),eq("cmd"),eq("msg"))).thenReturn(lease(9007199254740993L));
        call("/lease","{\"commandId\":\"cmd\",\"messageId\":\"msg\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value("9007199254740993"))
                .andExpect(jsonPath("$.data.expiresAt").isNumber()).andExpect(jsonPath("$.data.token").value(TOKEN))
                .andExpect(jsonPath("$.requestId").value("lease-test"));
    }
    @Test void renewalPreservesFullInt64Fence() throws Exception {
        when(executions.renewConversationLease(any(),eq("task"),eq("run"),any())).thenReturn(lease(Long.MAX_VALUE));
        call("/lease/renew","{\"version\":\"9223372036854775807\",\"token\":\""+TOKEN+"\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value("9223372036854775807"));
        verify(executions).renewConversationLease(any(),eq("task"),eq("run"),eq(new PersonalWorkspaceExecutionService.ConversationFence(Long.MAX_VALUE,TOKEN)));
    }
    @Test void oldNumericNoncanonicalOverflowAndUnknownFieldsFailBeforeMutation() throws Exception {
        for(String version:List.of("1","1.0","true","null","\"01\"","\"0\"","\"-1\"","\"9223372036854775808\""))
            call("/lease/renew","{\"version\":"+version+",\"token\":\""+TOKEN+"\"}").andExpect(status().isBadRequest());
        call("/lease/renew","{\"version\":\"1\",\"token\":\""+TOKEN+"\",\"extra\":true}").andExpect(status().isBadRequest());
        verifyNoInteractions(executions);
    }
    @Test void inputAndResumeLeaseVersionsUseSameWireType() throws Exception {
        when(skills.inputs(any(),eq("task"),eq("run"),any())).thenReturn(new SkillExecutionService.Inputs(5,"execution",9007199254740993L,"a".repeat(64),true,List.of()));
        call("/inputs","{\"version\":\"9007199254740993\",\"token\":\""+TOKEN+"\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.leaseVersion").value("9007199254740993"));
        when(skills.resume(any(),eq("task"),eq("run"),any())).thenReturn(new SkillExecutionService.ResumeReceipt(lease(9007199254740993L),"RUNNING",List.of("EXECUTE"),null));
        call("/resume","{\"executionId\":\"execution\",\"snapshotSha256\":\""+"a".repeat(64)+"\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.lease.version").value("9007199254740993"));
    }
    @Test void nativeInputStreamsAndClosesOnSuccessfulResponse() throws Exception {
        var closed=new java.util.concurrent.atomic.AtomicBoolean();
        var stream=new java.io.ByteArrayInputStream(new byte[]{1,2,3}) {
            @Override public byte[] readAllBytes() {throw new AssertionError("Runtime input must stream");}
            @Override public void close() {closed.set(true);}
        };
        when(skills.inputContent(any(),eq("task"),eq("run"),eq("input_1"),any()))
                .thenReturn(new PersonalWorkspaceExecutionService.RuntimeDownload("source","image/png","a".repeat(64),3,stream));
        call("/inputs/input_1/content","{\"version\":\"1\",\"token\":\""+TOKEN+"\"}")
                .andExpect(status().isOk()).andExpect(header().string("Content-Length","3"))
                .andExpect(content().bytes(new byte[]{1,2,3}));
        org.junit.jupiter.api.Assertions.assertTrue(closed.get());
    }
    @Test void nativeInputClosesWhenPostIoIdentityFenceRejects() throws Exception {
        var closed=new java.util.concurrent.atomic.AtomicBoolean();
        var stream=new java.io.ByteArrayInputStream(new byte[]{1,2,3}) {
            @Override public void close() {closed.set(true);}
        };
        when(skills.inputContent(any(),eq("task"),eq("run"),eq("input_1"),any()))
                .thenReturn(new PersonalWorkspaceExecutionService.RuntimeDownload("source","image/png","a".repeat(64),3,stream));
        doThrow(new SecurityException("fixture")).when(nativeAuth).withNativeFence(any(),any());
        call("/inputs/input_1/content","{\"version\":\"1\",\"token\":\""+TOKEN+"\"}")
                .andExpect(status().isServiceUnavailable()).andExpect(header().doesNotExist("Content-Disposition"));
        org.junit.jupiter.api.Assertions.assertTrue(closed.get());
    }
    @Test void rawEffectFenceIsProjectedWithoutChangingItsDomainLong() throws Exception {
        when(skills.startEffect(any(),eq("task"),eq("run"),eq("effect"),any()))
                .thenReturn(new SkillExecutionService.EffectReceipt(1,"NEW","START_RECORDED",null,null));
        String fields="\"executionId\":\"execution\",\"snapshotSha256\":\""+"a".repeat(64)+"\",\"effectSha256\":\""+"b".repeat(64)+"\",\"providerBindingRevision\":\"0\",";
        call("/effects/effect/start","{"+fields+"\"fence\":{\"version\":\"9007199254740993\",\"token\":\""+TOKEN+"\"}}")
                .andExpect(status().isOk());
        verify(skills).startEffect(any(),eq("task"),eq("run"),eq("effect"),argThat(value->value.fence().version()==9007199254740993L));
        call("/effects/effect/start","{"+fields+"\"fence\":{\"version\":1,\"token\":\""+TOKEN+"\"}}")
                .andExpect(status().isBadRequest());
    }
    @Test void hashNamesAndArtifactLengthsProjectToUnchangedServiceFacts() throws Exception {
        when(skills.commitResult(any(),eq("task"),eq("run"),eq("manifest"),any()))
                .thenReturn(new SkillExecutionService.ResultReceipt("manifest","RESULT_COMMITTED","ARCHIVING","c".repeat(64)));
        String data="{\"fence\":{\"version\":\"1\",\"token\":\""+TOKEN+"\"},\"snapshotSha256\":\""+"a".repeat(64)+"\","
                +"\"data\":null,\"inputReceipts\":[],\"effectDependencies\":[],\"artifacts\":[{\"outputId\":\"output_1\",\"sha256\":\""+"b".repeat(64)+"\",\"byteLength\":\"9007199254740993\"}]}";
        call("/result-commits/manifest",data).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resultSha256").value("c".repeat(64))).andExpect(jsonPath("$.data.resultDigest").doesNotExist());
        verify(skills).commitResult(any(),eq("task"),eq("run"),eq("manifest"),argThat(value->
                value.snapshotDigest().equals("a".repeat(64))&&value.artifacts().getFirst().byteLength()==9007199254740993L));
        call("/result-commits/manifest",data.replace("snapshotSha256","snapshotDigest")).andExpect(status().isBadRequest());
        call("/result-commits/manifest",data.replace("\"effectDependencies\":[],", "")).andExpect(status().isBadRequest());
        call("/result-commits/manifest",data.replace("\"9007199254740993\"","1")).andExpect(status().isBadRequest());
    }

    @Test void v5EmptySkillCommandAndResultUseTheSharedExactWireFixture() throws Exception {
        var mapper=tools.jackson.databind.json.JsonMapper.builder().build();
        var fixture=mapper.readTree(new org.springframework.core.io.ClassPathResource("contracts/native-execution-v5-local-files.json")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        var command=fixture.path("commandResponse").path("data").path("items").get(0);
        var outputs=mapper.convertValue(command.path("outputs"),java.util.Map.class);
        var view=new SkillExecutionService.Command(5,"execution","task","run","42","1","cmd","msg",List.of(),
                "a".repeat(64),java.util.Map.of(),command.path("instruction").asText(),List.of(),outputs,SkillExecutionService.NATIVE_LOCAL_FILES_POLICY);
        when(executions.runtimeConversationCommandViews(any(),eq(16))).thenAnswer(i->List.of(view));
        String actual=mvc.perform(get("/internal/agent/tasks/conversation-executions/commands").principal(auth).header("X-Request-Id","lease-test"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].skills").isEmpty())
                .andExpect(jsonPath("$.data.items[0].skill").doesNotExist()).andExpect(jsonPath("$.data.items[0].taskSkillBinding").doesNotExist())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assertions.assertEquals(command,mapper.readTree(actual).path("data").path("items").get(0));
        var preimage=mapper.convertValue(fixture.path("resultPreimage"),java.util.Map.class);
        String canonical=cn.jia.agent.service.SkillCanonicalJson.write(preimage);
        org.junit.jupiter.api.Assertions.assertEquals(fixture.path("canonicalPreimage").asText(),canonical);
        String digest=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        org.junit.jupiter.api.Assertions.assertEquals(fixture.path("resultSha256").asText(),digest);
        String manifest=fixture.path("manifestId").asText();
        when(skills.commitResult(any(),eq("task"),eq("run"),eq(manifest),any()))
                .thenReturn(new SkillExecutionService.ResultReceipt(manifest,"RESULT_COMMITTED","ARCHIVING",digest));
        mvc.perform(post(BASE+"/result-commits/"+manifest).principal(auth).header("X-Request-Id","lease-test")
                .contentType(MediaType.APPLICATION_JSON).content(fixture.path("resultCommitRequest").toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.resultSha256").value(digest));
        verify(skills).commitResult(any(),eq("task"),eq("run"),eq(manifest),argThat(value->
                value.effectDependencies().isEmpty()&&value.artifacts().getFirst().byteLength()==14));
    }
    @Test void resumeProjectsRealEffectFactsWithoutLeakingInternalDigestNames() throws Exception {
        var effect=new java.util.LinkedHashMap<String,Object>();effect.put("effectDigest","a".repeat(64));effect.put("resultDigest",null);
        effect.put("status","START_RECORDED");effect.put("bindingRevision",null);
        when(skills.resume(any(),eq("task"),eq("run"),any())).thenReturn(new SkillExecutionService.ResumeReceipt(lease(1),
                "WAITING_PROVIDER",List.of("QUERY_EFFECT","RECOVER_LOCAL_RESULT"),null,java.util.Map.of("operation_1",effect)));
        call("/resume","{\"executionId\":\"execution\",\"snapshotSha256\":\""+"a".repeat(64)+"\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.effects.operation_1.effectSha256").value("a".repeat(64)))
                .andExpect(jsonPath("$.data.effects.operation_1.status").value("START_RECORDED"))
                .andExpect(jsonPath("$.data.effects.operation_1.effectDigest").doesNotExist());
    }

    @Test void resumeReturnsAuthoritativeFailedTurnWithoutAResultOrLease() throws Exception {
        when(skills.resume(any(),eq("task"),eq("run"),any())).thenReturn(new SkillExecutionService.ResumeReceipt(null,
                "FAILED",List.of(),null,java.util.Map.of(),"NATIVE_EXECUTION_TURN_FAILED"));
        call("/resume","{\"executionId\":\"execution\",\"snapshotSha256\":\""+"a".repeat(64)+"\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.phase").value("FAILED"))
                .andExpect(jsonPath("$.data.turnFailureCode").value("NATIVE_EXECUTION_TURN_FAILED"))
                .andExpect(jsonPath("$.data.allowedWork").isEmpty())
                .andExpect(jsonPath("$.data.lease").doesNotExist()).andExpect(jsonPath("$.data.resultManifest").doesNotExist());
    }

    @Test void multipartStageUsesByteLengthAndKeepsBytesAndLeaseProof() throws Exception {
        byte[] bytes="中文成果".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String sha=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        when(executions.stageConversationOutputStream(any(),eq("task"),eq("run"),any(),eq("output_1"),any(),any(),any(),eq((long)bytes.length),eq(sha)))
                .thenReturn(new PersonalWorkspaceExecutionService.StagedOutput("output_1",sha,bytes.length,"STAGED"));
        var file=new org.springframework.mock.web.MockMultipartFile("file","result.txt","text/plain",bytes);
        mvc.perform(multipart(BASE+"/outputs/output_1/content").file(file).principal(auth)
                .param("version","9007199254740993").param("token",TOKEN).param("sha256",sha).param("byteLength",Integer.toString(bytes.length))
                .header("X-Request-Id","lease-test"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.byteLength").value(Integer.toString(bytes.length)));
        var source = org.mockito.ArgumentCaptor.forClass(cn.jia.agent.service.PersonalWorkspaceService.UploadSource.class);
        verify(executions).stageConversationOutputStream(any(),eq("task"),eq("run"),eq(new PersonalWorkspaceExecutionService.ConversationFence(9007199254740993L,TOKEN)),
                eq("output_1"),eq("result.txt"),eq("text/plain"),source.capture(),eq((long)bytes.length),eq(sha));
        try (var input = source.getValue().open()) {
            org.junit.jupiter.api.Assertions.assertArrayEquals(bytes, input.readAllBytes());
        }
        mvc.perform(multipart(BASE+"/outputs/output_1/content").file(file).principal(auth)
                .param("version","1").param("token",TOKEN).param("sha256",sha).param("length",Integer.toString(bytes.length)))
                .andExpect(status().isBadRequest());
    }

    @Test void retiredConversationOutputCommitHasNoRoute() throws Exception {
        call("/output-commits/manifest","{}").andExpect(status().isNotFound());
        verifyNoInteractions(executions);
    }

    @Test void workspaceOutputCommitUsesSameWireProjection() throws Exception {
        when(executions.commitOutputs(any(),eq("task"),eq("run"),eq("manifest"),any()))
                .thenReturn(new PersonalWorkspaceExecutionService.CommitView("manifest","COMMITTED",List.of(
                        new PersonalWorkspaceExecutionService.CommitItem("output_1","file",3,"b".repeat(64),"text/plain",12L))));
        String data="{\"outputs\":[{\"outputId\":\"output_1\",\"sha256\":\""+"b".repeat(64)+"\",\"byteLength\":\"12\"}]}";
        mvc.perform(post("/internal/agent/tasks/task/runs/run/output-commits/manifest").principal(auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"protocolVersion\":1,\"requestId\":\"test\",\"data\":"+data+"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].fileVersion").value("3"))
                .andExpect(jsonPath("$.data.items[0].byteLength").value("12"));
        verify(executions).commitOutputs(any(),eq("task"),eq("run"),eq("manifest"),argThat(items->items.getFirst().byteLength()==12L));
    }
    @Test void workspaceMultipartUsesByteLengthAndRejectsLegacyParameter() throws Exception {
        byte[] bytes="abc".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String sha=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        when(executions.stageOutput(any(),eq("task"),eq("run"),eq("output_1"),any(),any(),any()))
                .thenReturn(new PersonalWorkspaceExecutionService.StagedOutput("output_1",sha,3L,"STAGED"));
        var file=new org.springframework.mock.web.MockMultipartFile("file","result.txt","text/plain",bytes);
        for(String field:List.of("byteLength","length")) {
            var result=mvc.perform(multipart("/internal/agent/tasks/task/runs/run/outputs/output_1/content").file(file).principal(auth)
                .param("taskId","task").param("runId","run").param("outputId","output_1").param("sha256",sha).param(field,"3"));
            if(field.equals("byteLength"))result.andExpect(status().isCreated()).andExpect(jsonPath("$.data.byteLength").value("3"));
            else result.andExpect(status().isBadRequest());
        }
        verify(executions,times(1)).stageOutput(any(),eq("task"),eq("run"),eq("output_1"),any(),any(),any());
    }

    @Test void nativeConversationMultipartStreamsWithoutWholeFileArray() throws Exception {
        byte[] bytes={1,2,3};String hash="a".repeat(64);
        when(executions.stageConversationOutputStream(any(),eq("task"),eq("run"),any(),eq("output_1"),
                eq("result.txt"),eq("text/plain"),any(),eq(3L),eq(hash))).thenAnswer(i->{
            try(var input=i.getArgument(7,cn.jia.agent.service.PersonalWorkspaceService.UploadSource.class).open()) {
                org.junit.jupiter.api.Assertions.assertArrayEquals(bytes,input.readAllBytes());
            }
            return new PersonalWorkspaceExecutionService.StagedOutput("output_1",hash,3,"STAGED");
        });
        var file=new org.springframework.mock.web.MockMultipartFile("file","result.txt","text/plain",bytes) {
            @Override public byte[] getBytes(){throw new AssertionError("Native stage must stream");}
        };
        mvc.perform(multipart(BASE+"/outputs/output_1/content").file(file).principal(auth)
                .param("version","1").param("token",TOKEN).param("sha256",hash).param("byteLength","3"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.byteLength").value("3"));
    }

}
