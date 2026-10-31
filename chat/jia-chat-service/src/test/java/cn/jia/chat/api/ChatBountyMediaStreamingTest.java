package cn.jia.chat.api;

import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.chat.service.*;
import cn.jia.core.context.EsContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.io.InputStream;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** HTTP contract with a virtual multi-GiB source: no large allocation or production data. */
class ChatBountyMediaStreamingTest {
    final ChatDeliberationService requests=mock(ChatDeliberationService.class);
    final PersonalWorkspaceExecutionService files=mock(PersonalWorkspaceExecutionService.class);
    final HumanSenderIdentityResolver identities=mock(HumanSenderIdentityResolver.class);
    final TenantScopeResolver tenants=mock(TenantScopeResolver.class);
    final Authentication auth=mock(Authentication.class);
    final PersonalWorkspaceExecutionService.OwnerScope scope=new PersonalWorkspaceExecutionService.OwnerScope("0","client","owner");
    static final String PATH="/chat/requests/request/steps/step/outputs/output";
    static final String HASH="a".repeat(64);
    MockMvc mvc;
    VirtualFile opened;
    @BeforeEach void setup() {
        when(identities.resolve(nullable(EsContext.class))).thenReturn(new ServerResolvedSender("user","Owner","owner","client",DisplayNameSource.JIACN));
        when(tenants.resolve(auth)).thenReturn("0");
        var request=mock(ChatDeliberationService.RequestView.class);
        var step=mock(ChatDeliberationService.StepView.class);
        when(request.steps()).thenReturn(List.of(step));when(request.conversationId()).thenReturn("42");
        when(step.stepId()).thenReturn("step");when(step.kind()).thenReturn("EXECUTE");
        when(step.taskId()).thenReturn("task");when(step.executionId()).thenReturn("execution");when(step.targetAgentId()).thenReturn("agent");
        when(requests.getRequest("0","owner","client","request")).thenReturn(request);
        when(files.get(scope,"execution")).thenReturn(new PersonalWorkspaceExecutionService.ExecutionView(
                "execution","task","run","42","agent","RESULT_COMMITTED",null,null,1,"text/plain",List.of(),null,
                "CONVERSATION",null,null,null));
        mvc=MockMvcBuilders.standaloneSetup(new ChatBountyMediaController(requests,files,identities,tenants)).build();
    }
    void source(long size,String mime) {
        when(files.openConversationOutput(scope,"task","run","output")).thenAnswer(i -> {
            opened=new VirtualFile(size);
            return new PersonalWorkspaceExecutionService.ConversationDownload("execution","output","source",mime,HASH,size,opened);
        });
    }
    @Test void longRangeStreamsOnlyRequestedBytesAndCloses() throws Exception {
        long size=3_000_000_010L,start=3_000_000_000L;source(size,"text/plain");
        var result=mvc.perform(get(PATH).principal(auth).header("Range","bytes="+start+"-"+(start+3)))
                .andExpect(status().isPartialContent()).andExpect(header().string("Content-Length","4"))
                .andExpect(header().string("Content-Range","bytes "+start+"-"+(start+3)+"/"+size))
                .andExpect(header().string("Accept-Ranges","bytes")).andReturn();
        assertArrayEquals(new byte[]{0,1,2,3},result.getResponse().getContentAsByteArray());
        assertEquals(4,opened.readBytes);assertTrue(opened.closed);
        verify(files,never()).readConversationOutput(any(),anyString(),anyString(),anyString());
    }
    @Test void headAuthenticatesAndVerifiesWithoutReadingBody() throws Exception {
        source(3_000_000_000L,"text/plain");
        mvc.perform(head(PATH).principal(auth)).andExpect(status().isOk())
                .andExpect(header().string("Content-Length","3000000000")).andExpect(content().bytes(new byte[0]));
        assertTrue(opened.closed);assertEquals(0,opened.readBytes);
    }
    @Test void suffixAndIfRangeMismatchRetainExistingSemantics() throws Exception {
        source(8,"text/plain");
        mvc.perform(get(PATH).principal(auth).header("Range","bytes=-2"))
                .andExpect(status().isPartialContent()).andExpect(content().bytes(new byte[]{6,7}));
        assertTrue(opened.closed);
        mvc.perform(get(PATH).principal(auth).header("Range","bytes=1-2").header("If-Range","\"old\""))
                .andExpect(status().isOk()).andExpect(content().bytes(new byte[]{0,1,2,3,4,5,6,7}));
        assertTrue(opened.closed);
    }
    @Test void invalidRangesCloseWithoutSendingFileBytes() throws Exception {
        source(8,"text/plain");
        for(String range:List.of("bytes=8-","bytes=2-1","bytes=0-1,3-4","bytes=-0")) {
            mvc.perform(get(PATH).principal(auth).header("Range",range)).andExpect(status().isRequestedRangeNotSatisfiable())
                    .andExpect(header().string("Content-Range","bytes */8")).andExpect(content().bytes(new byte[0]));
            assertTrue(opened.closed);assertEquals(0,opened.readBytes);
        }
    }
    @Test void activeFormatsRemainAttachmentOnly() throws Exception {
        source(0,"text/html");
        mvc.perform(get(PATH).principal(auth)).andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition","attachment; filename=\"output.html\""))
                .andExpect(header().string("X-Content-Type-Options","nosniff"));
        assertTrue(opened.closed);
    }
    @Test void unavailableObjectNeverSendsSuccessfulHeadersOrBytes() throws Exception {
        when(files.openConversationOutput(scope,"task","run","output")).thenThrow(
                new PersonalWorkspaceExecutionService.Failure(PersonalWorkspaceExecutionService.Reason.STORAGE_UNAVAILABLE));
        mvc.perform(get(PATH).principal(auth)).andExpect(status().isNotFound())
                .andExpect(header().doesNotExist("Content-Disposition"));
    }
    @Test void assetDigestMismatchClosesBeforeHeadersOrBytes() {
        source(16,"image/png");
        var controller=new ChatBountyMediaController(requests,files,identities,tenants);
        var response=new org.springframework.mock.web.MockHttpServletResponse();
        assertThrows(ChatDeliberationException.class,()->controller.contentForAsset("request","step","output",false,
                null,null,new org.springframework.mock.web.MockHttpServletRequest("GET",PATH),auth,response,"b".repeat(64)));
        assertEquals(0,response.getContentAsByteArray().length);assertNull(response.getHeader("ETag"));
        assertTrue(opened.closed);assertEquals(0,opened.readBytes);
    }
    static class VirtualFile extends InputStream {
        final long size;long position,readBytes;boolean closed;
        VirtualFile(long size) {this.size=size;}
        @Override public int read() {if(position==size)return -1;readBytes++;return (int)(position++&255);}
        @Override public int read(byte[] bytes,int offset,int length) {
            if(position==size)return -1;
            int count=(int)Math.min(length,size-position);
            for(int i=0;i<count;i++)bytes[offset+i]=(byte)read();
            return count;
        }
        @Override public long skip(long count) {long n=Math.min(count,size-position);position+=n;return n;}
        @Override public byte[] readAllBytes() {throw new AssertionError("Must not buffer entire file");}
        @Override public void close() {closed=true;}
    }
}
