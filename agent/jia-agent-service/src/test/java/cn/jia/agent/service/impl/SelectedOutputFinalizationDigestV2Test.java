package cn.jia.agent.service.impl;

import cn.jia.agent.service.SelectedOutputFinalizationDigest;
import cn.jia.agent.service.SelectedOutputFinalizationDigest.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SelectedOutputFinalizationDigestV2Test {
    static final JsonNode FIXTURE=fixture();
    static JsonNode fixture() {
        try(var stream=SelectedOutputFinalizationDigestV2Test.class.getResourceAsStream("/contracts/juyiting-ui-workflow.json")) {
            assertNotNull(stream);return JsonMapper.builder().build().readTree(stream).get("promotionV2DigestVector");
        }catch(Exception failure) {throw new AssertionError(failure);}
    }
    static Selection retained() {
        var item=FIXTURE.get("items").get(0);
        return new Selection(null,null,null,item.get("sha256").asText(),item.get("title").asText(),item.get("purpose").asText(),null,
                new FormalArtifactSource(item.get("sourceDeliveryId").asText(),item.get("sourceArtifactId").asText(),Integer.parseInt(item.get("sourceArtifactVersion").asText())));
    }
    static Selection output(String request,String id) {return new Selection(request,"step",id,"a".repeat(64),"成果","result");}
    static Selection message(String id) {
        return new Selection("request",null,null,"b".repeat(64),"文本","result",new MessageSource("turn",id,"snapshot","sha256:"+"c".repeat(64)));
    }
    static String digest(List<Selection> items) {return SelectedOutputFinalizationDigest.requestV2("task",8,1,"conversation","本轮成果",items);}
    @Test void executesExactApprovedEncodedBytesAndShaVectorNotAHandwrittenMock() throws Exception {
        var item=retained();String result=SelectedOutputFinalizationDigest.requestV2(FIXTURE.get("taskId").asText(),Long.parseLong(FIXTURE.get("expectedTaskVersion").asText()),
                Long.parseLong(FIXTURE.get("expectedAssignmentRevision").asText()),FIXTURE.get("conversationId").asText(),FIXTURE.get("summary").asText(),List.of(item));
        assertEquals(FIXTURE.get("expectedSha256").asText(),result);
        assertEquals(result,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(HexFormat.of().parseHex(FIXTURE.get("encodedHex").asText()))));
    }
    @Test void formalSourceCannotBeSilentlyHashedWithV1Fields() {
        assertThrows(IllegalArgumentException.class,()->SelectedOutputFinalizationDigest.request("task",8,1,"conversation","summary",List.of(retained())));
    }
    @Test void oldOutputAndMessageAuditDigestsStayByteIdentical() throws Exception {
        var items=List.of(output("request","output"),message("9223372036854775807"));
        var h=MessageDigest.getInstance("SHA-256");put(h,"MMD_SELECTED_OUTPUT_FINALIZATION_V1");put(h,"task");put(h,8L);put(h,1L);put(h,"conversation");put(h,"summary");put(h,2L);
        for(var item:items) {
            put(h,item.requestId());put(h,item.stepId());put(h,item.outputId());put(h,item.sha256());put(h,item.title());put(h,item.purpose());
            if(item.messageSource()!=null) {put(h,"COMPLETED_MESSAGE");put(h,item.messageSource().turnId());put(h,item.messageSource().messageId());put(h,item.messageSource().snapshotId());put(h,item.messageSource().finalDigest());}
        }
        assertEquals(HexFormat.of().formatHex(h.digest()),SelectedOutputFinalizationDigest.request("task",8,1,"conversation","summary",items));
        assertNotEquals(SelectedOutputFinalizationDigest.request("task",8,1,"conversation","summary",items),SelectedOutputFinalizationDigest.requestV2("task",8,1,"conversation","summary",items));
    }
    @Test void allThreeSourceBranchesAndVisibleOrderAreBound() {
        var a=output("request","output");var b=message("1");var c=retained();
        assertNotEquals(digest(List.of(a,b,c)),digest(List.of(c,b,a)));
        assertNotEquals(digest(List.of(a,b)),digest(List.of(a,b,c)));
        assertNotEquals(digest(List.of(message("1"))),digest(List.of(message("2"))));
    }
    @Test void formalVersionAndHashAreNotDroppedOrCoerced() {
        var a=retained();var f=a.formalArtifactSource();var b=new Selection(null,null,null,a.sha256(),a.title(),a.purpose(),null,new FormalArtifactSource(f.sourceDeliveryId(),f.sourceArtifactId(),2));
        assertNotEquals(digest(List.of(a)),digest(List.of(b)));assertDoesNotThrow(()->digest(List.of(a,b)));
        var changed=new Selection(null,null,null,"1".repeat(64),a.title(),a.purpose(),null,f);assertNotEquals(digest(List.of(a)),digest(List.of(changed)));
    }
    @Test void sourceUnionsCannotMixPositioningFields() {
        var f=retained().formalArtifactSource();
        for(var s:List.of(new Selection("request",null,null,"a".repeat(64),"title","purpose",null,f),
                new Selection(null,null,null,"a".repeat(64),"title","purpose",message("1").messageSource(),f),
                new Selection("request","step","output","a".repeat(64),"title","purpose",message("1").messageSource())))
            assertThrows(IllegalArgumentException.class,()->digest(List.of(s)));
    }
    @Test void sourceMultiplicityIsExactNotHashBasedDeduplication() {
        for(var s:List.of(output("request","output"),message("1"),retained()))assertThrows(IllegalArgumentException.class,()->digest(List.of(s,s)));
        assertDoesNotThrow(()->digest(List.of(output("request-a","output"),output("request-b","output"))));
    }
    @Test void rejectsMissingNullInvalidVersionsAndUnpairedUnicodeInsteadOfEncodingReplacementBytes() {
        assertThrows(IllegalArgumentException.class,()->digest(null));assertThrows(IllegalArgumentException.class,()->digest(List.of()));
        assertThrows(IllegalArgumentException.class,()->digest(java.util.Arrays.asList((Selection)null)));
        for(String id:List.of("0","01","9223372036854775808","1.0"))assertThrows(IllegalArgumentException.class,()->digest(List.of(message(id))));
        assertThrows(IllegalArgumentException.class,()->new FormalArtifactSource("delivery","artifact",0));
        assertThrows(IllegalArgumentException.class,()->digest(List.of(new Selection("request","step","output","a".repeat(64),"\uD800","purpose"))));
        assertThrows(IllegalArgumentException.class,()->digest(List.of(new Selection("request","step","output","a".repeat(64),"title","\u0000"))));
        assertThrows(IllegalArgumentException.class,()->SelectedOutputFinalizationDigest.requestV2("task",-1,1,"conversation","summary",List.of(retained())));
    }
    static void put(MessageDigest digest,String value) {if(value==null) {put(digest,-1L);return;}var bytes=value.getBytes(StandardCharsets.UTF_8);put(digest,bytes.length);digest.update(bytes);}
    static void put(MessageDigest digest,long value) {digest.update(ByteBuffer.allocate(8).putLong(value).array());}
}
