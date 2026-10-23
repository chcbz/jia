package cn.jia.core.config;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
class ApiJsonBoundedInputTest {
    private static MockHttpServletRequest request(String body) { var r=new MockHttpServletRequest();r.setContentType("application/json");r.setContent(body.getBytes(StandardCharsets.UTF_8));return r; }
    private static String q(String data,String id) { return "{\"data\":"+data+",\"requestId\":\""+id+"\",\"protocolVersion\":1}"; }
    @Test void permitsLongCorrelationOutsideBusinessBudgetAndPreservesStrings() throws Exception {
        String data="{\"a\":\"空 白 \\\"值\",\"n\":null}";
        var r=request(q(data,"r".repeat(20000)));
        assertEquals(data,new String(ApiJsonInput.readData(r,100,true),StandardCharsets.UTF_8));
    }
    @Test void foldsOnlyOutsideStringWhitespace() throws Exception {
        assertEquals("{ \"a\": \" x  y \" }",new String(ApiJsonInput.readData(request(q("{   \"a\":   \" x  y \"   }","r")),64,true),StandardCharsets.UTF_8));
    }
    @Test void rejectsOversizeDuplicatesTrailingAndMalformedValues() {
        for(String body: new String[]{q("{\"a\":\""+"x".repeat(100)+"\"}","r"),q("{\"a\":1,\"a\":2}","r"),q("1 2","r"),q("null","r")+"{}", "{\"protocolVersion\":1,\"requestId\":\"r\",\"requestId\":\"s\",\"data\":null}",q("[1,}","r")})
            assertThrows(org.springframework.http.converter.HttpMessageNotReadableException.class,()->ApiJsonInput.readData(request(body),50,true),body);
    }
    @Test void rejectsMismatchAndAcceptsExactBudget() throws Exception {
        var r=request(q("null","r"));r.addHeader("X-Request-Id","other");
        assertThrows(org.springframework.http.converter.HttpMessageNotReadableException.class,()->ApiJsonInput.readData(r,4,true));
        assertArrayEquals("null".getBytes(StandardCharsets.UTF_8),ApiJsonInput.readData(request(q("null","r")),4,true));
    }
}
