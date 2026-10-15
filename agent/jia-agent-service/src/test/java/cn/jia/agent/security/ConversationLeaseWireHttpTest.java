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
    @BeforeEach void setup() {
        executions=mock(PersonalWorkspaceExecutionService.class,withSettings().extraInterfaces(SkillExecutionService.class));
        skills=(SkillExecutionService)executions;
        var nativeAuth=mock(AgentRuntimeAuthenticationService.class);
        when(nativeAuth.withNativeFence(any(),any())).thenAnswer(invocation->invocation.getArgument(1,Supplier.class).get());
        auth=new AgentRuntimeAuthentication(new AgentRuntimeAuthentication.Scope("tenant","client","owner","agent","runtime"));
        var converters=new ArrayList<HttpMessageConverter<?>>();
        converters.add(new StringHttpMessageConverter(java.nio.charset.StandardCharsets.UTF_8));
        converters.add(new JacksonJsonHttpMessageConverter());
        new ApiEnvelopeJsonConfig().extendMessageConverters(converters);
        mvc=MockMvcBuilders.standaloneSetup(new PersonalWorkspaceConversationRuntimeController(executions,nativeAuth))
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
        when(skills.inputs(any(),eq("task"),eq("run"),any())).thenReturn(new SkillExecutionService.Inputs(4,"execution",9007199254740993L,"a".repeat(64),true,List.of()));
        call("/inputs","{\"version\":\"9007199254740993\",\"token\":\""+TOKEN+"\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.leaseVersion").value("9007199254740993"));
        when(skills.resume(any(),eq("task"),eq("run"),any())).thenReturn(new SkillExecutionService.ResumeReceipt(lease(9007199254740993L),"RUNNING",List.of("EXECUTE"),null));
        call("/resume","{\"executionId\":\"execution\",\"snapshotSha256\":\""+"a".repeat(64)+"\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.lease.version").value("9007199254740993"));
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
                +"\"data\":null,\"inputReceipts\":[],\"artifacts\":[{\"outputId\":\"output_1\",\"sha256\":\""+"b".repeat(64)+"\",\"byteLength\":\"9007199254740993\"}]}";
        call("/result-commits/manifest",data).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resultSha256").value("c".repeat(64))).andExpect(jsonPath("$.data.resultDigest").doesNotExist());
        verify(skills).commitResult(any(),eq("task"),eq("run"),eq("manifest"),argThat(value->
                value.snapshotDigest().equals("a".repeat(64))&&value.artifacts().getFirst().byteLength()==9007199254740993L));
        call("/result-commits/manifest",data.replace("snapshotSha256","snapshotDigest")).andExpect(status().isBadRequest());
        call("/result-commits/manifest",data.replace("\"9007199254740993\"","1")).andExpect(status().isBadRequest());
    }

    @Test void multipartStageUsesByteLengthAndKeepsBytesAndLeaseProof() throws Exception {
        byte[] bytes="中文成果".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String sha=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        when(executions.stageConversationOutput(any(),eq("task"),eq("run"),any(),eq("output_1"),any(),any(),any()))
                .thenReturn(new PersonalWorkspaceExecutionService.StagedOutput("output_1",sha,bytes.length,"STAGED"));
        var file=new org.springframework.mock.web.MockMultipartFile("file","result.txt","text/plain",bytes);
        mvc.perform(multipart(BASE+"/outputs/output_1/content").file(file).principal(auth)
                .param("version","9007199254740993").param("token",TOKEN).param("sha256",sha).param("byteLength",Integer.toString(bytes.length))
                .header("X-Request-Id","lease-test"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.byteLength").value(Integer.toString(bytes.length)));
        verify(executions).stageConversationOutput(any(),eq("task"),eq("run"),eq(new PersonalWorkspaceExecutionService.ConversationFence(9007199254740993L,TOKEN)),
                eq("output_1"),eq("result.txt"),eq("text/plain"),org.mockito.AdditionalMatchers.aryEq(bytes));
        mvc.perform(multipart(BASE+"/outputs/output_1/content").file(file).principal(auth)
                .param("version","1").param("token",TOKEN).param("sha256",sha).param("length",Integer.toString(bytes.length)))
                .andExpect(status().isBadRequest());
    }

}
