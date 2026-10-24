package cn.jia.agent.entity;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static cn.jia.agent.entity.AgentRuntimeV2EffectCodec.*;
import static cn.jia.agent.entity.AgentRuntimeV2RegistrationFingerprints.sha256;
import static cn.jia.agent.entity.AgentRuntimeV2RegistrationFingerprints.hex;
import static org.junit.jupiter.api.Assertions.*;

/** R4 codec unit vectors only; not server/resource/OS authorization evidence. */
public class AgentRuntimeV2EffectCodecTest {
    private static final String ID="11111111-1111-4111-8111-111111111111",HASH="a".repeat(64);
    private static GrantReference grant(){return new GrantReference(ID,ID,ID,HASH,ID,HASH);}
    private static byte[] raw(String text){return text.getBytes(StandardCharsets.UTF_8);}
    // REGISTRATION_FIXED_PATTERN_ORACLE_BEGIN: unchanged Parser readers plus the original expressions.
    private record RegistrationPatternOutcome(Object value,Throwable failure,int cursor) {}
    private interface RegistrationDecoder<T> { T decode(byte[] wire) throws Exception; }
    private static Object registrationParser(byte[] wire) throws Exception {
        var constructor=Class.forName("cn.jia.agent.entity.AgentRuntimeV2RegistrationCodec$Parser").getDeclaredConstructor(byte[].class);
        constructor.setAccessible(true);return constructor.newInstance((Object)wire);
    }
    private static Object registrationParserCall(Object parser,String name,Object... arguments) throws Exception {
        Class<?>[] types=new Class<?>[arguments.length];
        for(int i=0;i<arguments.length;i++)types[i]=arguments[i] instanceof Boolean?boolean.class:arguments[i] instanceof Integer?int.class:String.class;
        var method=parser.getClass().getDeclaredMethod(name,types);method.setAccessible(true);
        try{return method.invoke(parser,arguments);}catch(java.lang.reflect.InvocationTargetException failure){
            if(failure.getCause() instanceof RuntimeException runtime)throw runtime;
            if(failure.getCause() instanceof Error error)throw error;
            throw (Exception)failure.getCause();
        }
    }
    private static String registrationString(Object parser,String key) throws Exception {return (String)registrationParserCall(parser,"string",key);}
    private static byte[] originalRegistrationHash(Object parser,String key,boolean nullable) throws Exception {
        String value=(String)registrationParserCall(parser,nullable?"nullable":"string",key);
        if(nullable&&value==null)return null;
        if(!value.matches("[0-9a-f]{64}"))return (byte[])registrationParserCall(parser,"fail");
        return HexFormat.of().parseHex(value);
    }
    private static long originalRegistrationDecimal(Object parser,String key,boolean positive) throws Exception {
        String value=registrationString(parser,key);
        if(!value.matches(positive?"[1-9][0-9]*":"0|[1-9][0-9]*"))return (long)registrationParserCall(parser,"fail");
        try{return Long.parseLong(value);}catch(NumberFormatException failure){return (long)registrationParserCall(parser,"fail");}
    }
    private static RegistrationPatternOutcome registrationPatternOutcome(byte[] wire,String operation,boolean positive,boolean original) throws Exception {
        Object parser=registrationParser(wire),value=null;Throwable failure=null;
        try{
            registrationParserCall(parser,"open");
            value=original?("decimal".equals(operation)?originalRegistrationDecimal(parser,"value",positive):originalRegistrationHash(parser,"value","nullableHash".equals(operation))):
                    ("decimal".equals(operation)?registrationParserCall(parser,operation,"value",positive):registrationParserCall(parser,operation,"value"));
            registrationParserCall(parser,"close");
        }catch(Exception error){failure=error;}
        var cursor=parser.getClass().getDeclaredField("p");cursor.setAccessible(true);
        return new RegistrationPatternOutcome(value,failure,cursor.getInt(parser));
    }
    private static void assertRegistrationFailureExact(Throwable expected,Throwable actual){
        if(expected==null){assertNull(actual);return;}
        assertNotNull(actual);assertEquals(expected.getClass(),actual.getClass());assertEquals(expected.getMessage(),actual.getMessage());
        assertRegistrationFailureExact(expected.getCause(),actual.getCause());
        assertEquals(expected.getSuppressed().length,actual.getSuppressed().length);
    }
    private static void assertRegistrationPatternOriginal(byte[] token,String operation,boolean positive) throws Exception {
        var out=new java.io.ByteArrayOutputStream();out.writeBytes(raw("{\"value\":"));out.writeBytes(token);out.write('}');byte[] wire=out.toByteArray();
        var original=registrationPatternOutcome(wire,operation,positive,true);var actual=registrationPatternOutcome(wire,operation,positive,false);
        assertRegistrationFailureExact(original.failure(),actual.failure());assertEquals(original.cursor(),actual.cursor());
        if(original.value() instanceof byte[] bytes)assertArrayEquals(bytes,(byte[])actual.value());else assertEquals(original.value(),actual.value());
    }
    private static byte[] registrationQuotedToken(String value){
        StringBuilder quoted=new StringBuilder("\"");
        value.codePoints().forEach(c->{if(c=='"')quoted.append("\\\"");else if(c=='\\')quoted.append("\\\\");else if(c<=31)quoted.append(String.format(Locale.ROOT,"\\u%04x",c));else quoted.appendCodePoint(c);});
        return raw(quoted.append('"').toString());
    }
    private static List<byte[]> registrationMalformedTokens(){
        List<byte[]> tokens=new ArrayList<>();
        for(String token:List.of("null","true","0","\"unterminated","\"\\n\"","\"\\u000A\"","\"\\ud800\"","\"\\udfff\"","\"\\ud83d\\ude00\"","\"raw\nline\"","\"raw\rline\"","\"raw\0control\"","\"x\"\n"))tokens.add(raw(token));
        tokens.add(new byte[]{'"',(byte)0xed,(byte)0xa0,(byte)0x80,'"'});
        tokens.add(new byte[]{'"',(byte)0xed,(byte)0xbf,(byte)0xbf,'"'});
        tokens.add(new byte[]{'"',(byte)0xc0,(byte)0xaf,'"'});
        tokens.add(new byte[]{'"',(byte)0xf0,(byte)0x9f,(byte)0x98,'"'});
        return tokens;
    }
    private static List<byte[]> registrationHashTokens(){
        List<byte[]> tokens=registrationMalformedTokens();
        for(String value:List.of("",HASH,"0123456789abcdef".repeat(4),"0".repeat(64),"f".repeat(64),HASH.substring(1),HASH+"a",HASH.toUpperCase(Locale.ROOT),"g"+HASH.substring(1),"-"+HASH.substring(1)," "+HASH,HASH+" ",HASH+"\n",HASH+"\r",HASH+"\r\n",HASH+"\0",HASH+"\u2028",HASH+"\u2029",HASH+"中文",HASH+"😀",HASH.replace('a','\uff41'),"١".repeat(64),"１".repeat(64),"0".repeat(63)+"１","\\"+HASH.substring(1),"\""+HASH.substring(1)))tokens.add(registrationQuotedToken(value));
        for(int control=0;control<32;control++)tokens.add(registrationQuotedToken(HASH.substring(0,31)+(char)control+HASH.substring(32)));
        return Collections.unmodifiableList(tokens);
    }
    private static List<byte[]> registrationDecimalTokens(){
        List<byte[]> tokens=registrationMalformedTokens();
        for(String value:List.of("","0","1","9","10",Long.toString(Long.MAX_VALUE-1),Long.toString(Long.MAX_VALUE),"9223372036854775808","18446744073709551615","9".repeat(80),"00","01","0001","+0","+1","-0","-1",Long.toString(Long.MIN_VALUE)," 1","1 ","1.0","1e0","1E1","0x1","١","１","𝟙","1١","1\n","1\r","1\r\n","\n1","1\0","1\t","1\u2028","1\u2029","😀","中文"))tokens.add(registrationQuotedToken(value));
        return Collections.unmodifiableList(tokens);
    }
    @Test void registrationParserFixedPatternsRetainOriginalHashAndNullableHashOutcomes() throws Exception {
        for(int repeat=0;repeat<8;repeat++)for(byte[] token:registrationHashTokens())for(String operation:List.of("hash","nullableHash"))assertRegistrationPatternOriginal(token,operation,false);
        var valid=registrationPatternOutcome(raw("{\"value\":\""+HASH+"\"}"),"hash",false,false);assertNull(valid.failure());assertArrayEquals(HexFormat.of().parseHex(HASH),(byte[])valid.value());
        var nullable=registrationPatternOutcome(raw("{\"value\":null}"),"nullableHash",false,false);assertNull(nullable.failure());assertNull(nullable.value());
        var rejected=registrationPatternOutcome(raw("{\"value\":null}"),"hash",false,false);assertEquals(IllegalArgumentException.class,rejected.failure().getClass());assertEquals("wire is not exact canonical registration JSON at byte 9",rejected.failure().getMessage());assertNull(rejected.failure().getCause());
    }
    @Test void registrationParserFixedPatternsRetainOriginalDecimalOutcomesAndCursor() throws Exception {
        for(int repeat=0;repeat<8;repeat++)for(byte[] token:registrationDecimalTokens())for(boolean positive:new boolean[]{false,true})assertRegistrationPatternOriginal(token,"decimal",positive);
        for(boolean positive:new boolean[]{false,true}){var maximum=registrationPatternOutcome(raw("{\"value\":\""+Long.MAX_VALUE+"\"}"),"decimal",positive,false);assertNull(maximum.failure());assertEquals(Long.MAX_VALUE,maximum.value());}
        var zero=registrationPatternOutcome(raw("{\"value\":\"0\"}"),"decimal",false,false);assertNull(zero.failure());assertEquals(0L,zero.value());
        var overflow=registrationPatternOutcome(raw("{\"value\":\"9223372036854775808\"}"),"decimal",true,false);assertEquals(IllegalArgumentException.class,overflow.failure().getClass());assertEquals("wire is not exact canonical registration JSON at byte 30",overflow.failure().getMessage());assertNull(overflow.failure().getCause());
    }
    @Test void registrationParserFixedPatternsUseFreshMatchersForConcurrentIndependentParsers() throws Exception {
        var workers=java.util.concurrent.Executors.newFixedThreadPool(8);var start=new java.util.concurrent.CountDownLatch(1);List<java.util.concurrent.Future<Void>> futures=new ArrayList<>();
        List<byte[]> hashes=registrationHashTokens(),decimals=registrationDecimalTokens();
        try{
            for(int worker=0;worker<8;worker++)futures.add(workers.submit(()->{start.await();for(int repeat=0;repeat<4;repeat++){
                for(byte[] token:hashes)for(String operation:List.of("hash","nullableHash"))assertRegistrationPatternOriginal(token,operation,false);
                for(byte[] token:decimals)for(boolean positive:new boolean[]{false,true})assertRegistrationPatternOriginal(token,"decimal",positive);
            }return null;}));
            start.countDown();for(var future:futures)future.get(30,java.util.concurrent.TimeUnit.SECONDS);
        }finally{start.countDown();workers.shutdownNow();assertTrue(workers.awaitTermination(30,java.util.concurrent.TimeUnit.SECONDS));}
    }
    private static Object originalRegistrationPublicParser(byte[] wire,String type) throws Exception {
        Object parser=registrationParser(wire);registrationParserCall(parser,"open");registrationParserCall(parser,"number","schemaVersion",2);registrationParserCall(parser,"literal","messageType",type);return parser;
    }
    private static <T>T originalRegistrationPublicClose(byte[] wire,Object parser,T value,java.util.function.Function<T,byte[]> encoder) throws Exception {
        registrationParserCall(parser,"close");if(!Arrays.equals(wire,encoder.apply(value)))throw new IllegalArgumentException("wire is not canonical");return value;
    }
    private static AgentRuntimeV2RegistrationCodec.ChallengeRequest originalRegistrationChallengeRequest(byte[] wire) throws Exception {
        Object p=originalRegistrationPublicParser(wire,"runtime.registration.reconnect.challenge.request");
        var value=new AgentRuntimeV2RegistrationCodec.ChallengeRequest(registrationString(p,"challengeRequestId"),registrationString(p,"runtimeBindingId"),registrationString(p,"priorRegistrationReceiptId"),originalRegistrationHash(p,"priorRegistrationReceiptSha256",false),registrationString(p,"runtimeInstanceId"));
        return originalRegistrationPublicClose(wire,p,value,AgentRuntimeV2RegistrationCodec::encodeChallengeRequest);
    }
    private static AgentRuntimeV2RegistrationCodec.Challenge originalRegistrationChallenge(byte[] wire) throws Exception {
        Object p=originalRegistrationPublicParser(wire,"runtime.registration.reconnect.challenge");
        String request=registrationString(p,"challengeRequestId"),challenge=registrationString(p,"challengeId"),binding=registrationString(p,"runtimeBindingId");long generation=originalRegistrationDecimal(p,"bindingGeneration",false);
        String instance=registrationString(p,"runtimeInstanceId"),prior=registrationString(p,"priorRegistrationReceiptId");byte[] priorHash=originalRegistrationHash(p,"priorRegistrationReceiptSha256",false);registrationParserCall(p,"literal","challengeCipherSuite",AgentRuntimeV2RegistrationCodec.CIPHER_SUITE);
        var value=new AgentRuntimeV2RegistrationCodec.Challenge(request,challenge,binding,generation,instance,prior,priorHash,(byte[])registrationParserCall(p,"base64","serverEphemeralPublicKeyBase64",44),(byte[])registrationParserCall(p,"base64","nonceBase64",12),(byte[])registrationParserCall(p,"base64","sealedChallengeBase64",48),originalRegistrationHash(p,"challengeSha256",false),originalRegistrationDecimal(p,"expiresAt",true));
        return originalRegistrationPublicClose(wire,p,value,AgentRuntimeV2RegistrationCodec::encodeChallenge);
    }
    private static AgentRuntimeV2RegistrationCodec.Receipt originalRegistrationReceipt(byte[] wire) throws Exception {
        Object p=originalRegistrationPublicParser(wire,"runtime.registration.receipt");
        String id=registrationString(p,"registrationReceiptId"),kind=registrationString(p,"receiptKind"),request=registrationString(p,"registrationRequestId"),previous=(String)registrationParserCall(p,"nullable","previousRegistrationReceiptId");byte[] previousHash=originalRegistrationHash(p,"previousRegistrationReceiptSha256",true);
        String binding=registrationString(p,"runtimeBindingId");long generation=originalRegistrationDecimal(p,"bindingGeneration",false);String fact=registrationString(p,"bindingGenerationFactId"),instance=registrationString(p,"runtimeInstanceId");
        byte[] processHash=originalRegistrationHash(p,"processPublicKeySha256",false);long version=originalRegistrationDecimal(p,"verifierVersion",true);byte[] verifierHash=originalRegistrationHash(p,"verifierSha256",false);registrationParserCall(p,"literal","verifierCipherSuite",AgentRuntimeV2RegistrationCodec.CIPHER_SUITE);
        byte[] server=(byte[])registrationParserCall(p,"base64","serverEphemeralPublicKeyBase64",44),nonce=(byte[])registrationParserCall(p,"base64","nonceBase64",12),sealed=(byte[])registrationParserCall(p,"base64","sealedVerifierBase64",48);
        var value=new AgentRuntimeV2RegistrationCodec.Receipt(id,kind,request,previous,previousHash,binding,generation,fact,instance,processHash,version,verifierHash,server,nonce,sealed,registrationString(p,"tenantId"),registrationString(p,"dataDomainId"),registrationString(p,"executionPoolId"),registrationString(p,"runtimeCredentialClientId"),registrationString(p,"ownerJiacn"),registrationString(p,"agentId"),registrationString(p,"registrationAuthorityId"),originalRegistrationDecimal(p,"registrationAuthorityRevision",false),registrationString(p,"claimantDeploymentId"),originalRegistrationDecimal(p,"claimantWorkerGeneration",false),originalRegistrationDecimal(p,"receiverRegistrationRevision",false),originalRegistrationDecimal(p,"admissionEpoch",false),registrationString(p,"moduleId"),originalRegistrationDecimal(p,"migrationEpoch",false),originalRegistrationDecimal(p,"connectedAt",true));
        return originalRegistrationPublicClose(wire,p,value,AgentRuntimeV2RegistrationCodec::encodeReceipt);
    }
    private static <T>void assertRegistrationPublicOriginal(byte[] wire,RegistrationDecoder<T> oracle,RegistrationDecoder<T> actual,java.util.function.Function<T,byte[]> encoder) throws Exception {
        T expected=null,parsed=null;Throwable expectedFailure=null,actualFailure=null;
        try{expected=oracle.decode(wire);}catch(Exception failure){expectedFailure=failure;}
        try{parsed=actual.decode(wire);}catch(Exception failure){actualFailure=failure;}
        assertRegistrationFailureExact(expectedFailure,actualFailure);
        if(expectedFailure==null){assertArrayEquals(encoder.apply(expected),encoder.apply(parsed));assertArrayEquals(wire,encoder.apply(parsed));}
    }
    private static byte[] registrationReplaceToken(byte[] wire,String field,byte[] previous,byte[] token){
        byte[] prefix=raw("\""+field+"\":");int offset=-1;
        outer:for(int i=0;i<=wire.length-prefix.length;i++){for(int j=0;j<prefix.length;j++)if(wire[i+j]!=prefix[j])continue outer;offset=i+prefix.length;break;}
        assertTrue(offset>=0);assertArrayEquals(previous,Arrays.copyOfRange(wire,offset,offset+previous.length));
        var out=new java.io.ByteArrayOutputStream();out.write(wire,0,offset);out.writeBytes(token);out.write(wire,offset+previous.length,wire.length-offset-previous.length);return out.toByteArray();
    }
    @Test void registrationParserFixedPatternsRetainPublicCanonicalRoundTripsAndExactFailures() throws Exception {
        byte[] hash=HexFormat.of().parseHex(HASH),spki=HexFormat.of().parseHex("302a300506032b656e032100"+"0".repeat(64));
        byte[] request=AgentRuntimeV2RegistrationCodec.encodeChallengeRequest(new AgentRuntimeV2RegistrationCodec.ChallengeRequest(ID,ID,ID,hash,ID));
        assertRegistrationPublicOriginal(request,AgentRuntimeV2EffectCodecTest::originalRegistrationChallengeRequest,AgentRuntimeV2RegistrationCodec::decodeChallengeRequest,AgentRuntimeV2RegistrationCodec::encodeChallengeRequest);
        for(byte[] token:registrationHashTokens())assertRegistrationPublicOriginal(registrationReplaceToken(request,"priorRegistrationReceiptSha256",registrationQuotedToken(HASH),token),AgentRuntimeV2EffectCodecTest::originalRegistrationChallengeRequest,AgentRuntimeV2RegistrationCodec::decodeChallengeRequest,AgentRuntimeV2RegistrationCodec::encodeChallengeRequest);
        for(long generation:new long[]{0,1,Long.MAX_VALUE}){
            byte[] challenge=AgentRuntimeV2RegistrationCodec.encodeChallenge(new AgentRuntimeV2RegistrationCodec.Challenge(ID,ID,ID,generation,ID,ID,hash,spki,new byte[12],new byte[48],hash,Long.MAX_VALUE));
            assertRegistrationPublicOriginal(challenge,AgentRuntimeV2EffectCodecTest::originalRegistrationChallenge,AgentRuntimeV2RegistrationCodec::decodeChallenge,AgentRuntimeV2RegistrationCodec::encodeChallenge);
            for(String field:List.of("bindingGeneration","expiresAt"))for(byte[] token:registrationDecimalTokens())assertRegistrationPublicOriginal(registrationReplaceToken(challenge,field,registrationQuotedToken(Long.toString("bindingGeneration".equals(field)?generation:Long.MAX_VALUE)),token),AgentRuntimeV2EffectCodecTest::originalRegistrationChallenge,AgentRuntimeV2RegistrationCodec::decodeChallenge,AgentRuntimeV2RegistrationCodec::encodeChallenge);
            for(String field:List.of("priorRegistrationReceiptSha256","challengeSha256"))for(byte[] token:registrationHashTokens())assertRegistrationPublicOriginal(registrationReplaceToken(challenge,field,registrationQuotedToken(HASH),token),AgentRuntimeV2EffectCodecTest::originalRegistrationChallenge,AgentRuntimeV2RegistrationCodec::decodeChallenge,AgentRuntimeV2RegistrationCodec::encodeChallenge);
        }
        for(boolean create:new boolean[]{true,false}){
            long generation=create?0:Long.MAX_VALUE-1,version=generation+1;
            byte[] receipt=AgentRuntimeV2RegistrationCodec.encodeReceipt(new AgentRuntimeV2RegistrationCodec.Receipt(ID,create?"CREATE":"RECONNECT",ID,create?null:ID,create?null:hash,ID,generation,ID,ID,hash,version,hash,spki,new byte[12],new byte[48],"1","domain","pool","client","owner","agent",ID,Long.MAX_VALUE,"deployment",0,Long.MAX_VALUE,0,"module",Long.MAX_VALUE,Long.MAX_VALUE));
            assertRegistrationPublicOriginal(receipt,AgentRuntimeV2EffectCodecTest::originalRegistrationReceipt,AgentRuntimeV2RegistrationCodec::decodeReceipt,AgentRuntimeV2RegistrationCodec::encodeReceipt);
            for(byte[] token:registrationHashTokens())assertRegistrationPublicOriginal(registrationReplaceToken(receipt,"previousRegistrationReceiptSha256",create?raw("null"):registrationQuotedToken(HASH),token),AgentRuntimeV2EffectCodecTest::originalRegistrationReceipt,AgentRuntimeV2RegistrationCodec::decodeReceipt,AgentRuntimeV2RegistrationCodec::encodeReceipt);
            for(String field:List.of("bindingGeneration","verifierVersion","registrationAuthorityRevision","connectedAt"))for(byte[] token:registrationDecimalTokens())assertRegistrationPublicOriginal(registrationReplaceToken(receipt,field,registrationQuotedToken(Long.toString("bindingGeneration".equals(field)?generation:"verifierVersion".equals(field)?version:Long.MAX_VALUE)),token),AgentRuntimeV2EffectCodecTest::originalRegistrationReceipt,AgentRuntimeV2RegistrationCodec::decodeReceipt,AgentRuntimeV2RegistrationCodec::encodeReceipt);
            for(byte[] altered:List.of(raw(" "+new String(receipt,StandardCharsets.UTF_8)),raw(new String(receipt,StandardCharsets.UTF_8)+"\n")))assertRegistrationPublicOriginal(altered,AgentRuntimeV2EffectCodecTest::originalRegistrationReceipt,AgentRuntimeV2RegistrationCodec::decodeReceipt,AgentRuntimeV2RegistrationCodec::encodeReceipt);
        }
    }
    // REGISTRATION_FIXED_PATTERN_ORACLE_END
    private static final String ORIGINAL_REGISTRATION_UUID4="[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";
    private static List<String> registrationUuidPatternVectors(){
        String letters="abcdefab-cdef-4abc-8abc-abcdefabcdef";
        List<String> values=new ArrayList<>(Arrays.asList(null,"",ID,letters,letters.toUpperCase(Locale.ROOT),
                ID.substring(1),ID+"0",ID.replace('-','_'),ID.replace("-",""),ID+"\n",ID+"\r",ID+"\r\n",ID+"\0",
                "\n"+ID," "+ID,ID+" ",ID.replace('1','\u0661'),letters.replace('a','\uff41'),
                ID.replace('-','\u2010'),ID+"中文",ID+"😀",ID+"\u2028",ID+"\u2029",ID+"\ud800",ID+"\udfff"));
        for(char version:"0123456789abcdef".toCharArray())values.add(ID.substring(0,14)+version+ID.substring(15));
        for(char variant:"0123456789abcdef".toCharArray())values.add(ID.substring(0,19)+variant+ID.substring(20));
        for(int index=0;index<ID.length();index++)values.add(ID.substring(0,index)+"\0"+ID.substring(index+1));
        return Collections.unmodifiableList(values);
    }
    private static void assertRegistrationUuidPatternMatchesOriginal(String value,String name){
        boolean originalAccepted=value!=null&&value.matches(ORIGINAL_REGISTRATION_UUID4);
        if(originalAccepted)assertDoesNotThrow(()->AgentRuntimeV2RegistrationFingerprints.uuid(value,name));
        else{
            var failure=assertThrows(IllegalArgumentException.class,()->AgentRuntimeV2RegistrationFingerprints.uuid(value,name));
            assertEquals(IllegalArgumentException.class,failure.getClass());
            assertEquals(name+" is not UUIDv4",failure.getMessage());
        }
    }
    @Test void registrationUuidPatternRetainsOriginalMatchesNullFormatAndExactExceptions(){
        for(String value:registrationUuidPatternVectors())for(String name:Arrays.asList("runtimeBindingId","sessionFence","字段😀",null))
            assertRegistrationUuidPatternMatchesOriginal(value,name);
        for(int repeat=0;repeat<32;repeat++)for(String value:registrationUuidPatternVectors())
            assertRegistrationUuidPatternMatchesOriginal(value,"repeated");
    }
    @Test void registrationUuidPatternUsesIndependentMatchersForConcurrentRepeatedCalls() throws Exception {
        var workers=java.util.concurrent.Executors.newFixedThreadPool(8);
        var start=new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<Void>> futures=new ArrayList<>();
        List<String> values=registrationUuidPatternVectors();
        try{
            for(int worker=0;worker<8;worker++){
                String name="worker-"+worker;
                futures.add(workers.submit(()->{
                    start.await();
                    for(int repeat=0;repeat<32;repeat++)for(String value:values)
                        assertRegistrationUuidPatternMatchesOriginal(value,name);
                    return null;
                }));
            }
            start.countDown();
            for(var future:futures)future.get(30,java.util.concurrent.TimeUnit.SECONDS);
        }finally{
            start.countDown();workers.shutdownNow();
            assertTrue(workers.awaitTermination(30,java.util.concurrent.TimeUnit.SECONDS));
        }
    }
    @Test void fixedTreeStreamingMatchesOriginalJacksonBytesEscapesNestedTypesAndExactCaps() throws Exception {
        var oracle=new tools.jackson.databind.ObjectMapper(tools.jackson.core.json.JsonFactory.builder().enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
        var encoded=AgentRuntimeV2EffectCodec.class.getDeclaredMethod("encoded",tools.jackson.databind.node.ObjectNode.class,int.class);encoded.setAccessible(true);
        List<String> texts=new ArrayList<>(List.of("","ascii / \\\"","中文😀","\u2028\u2029",new String(Character.toChars(0x10ffff))));
        for(int character=0;character<=255;character++)texts.add(Character.toString(character));
        for(String text:texts)for(long number:new long[]{0,1,-1,Integer.MIN_VALUE,Integer.MAX_VALUE,Long.MIN_VALUE,Long.MAX_VALUE,9007199254740993L}){
            var node=oracle.createObjectNode();node.put("schemaVersion",2);node.put(text+"-key",text);node.put("integer",number);node.put("boolean",true);node.putNull("null");
            node.putArray("array").add(false).addNull().add(text).add(number).addObject().put("nested",text);
            byte[] expected=oracle.writeValueAsBytes(node);assertArrayEquals(expected,(byte[])encoded.invoke(null,node,expected.length));
        }
        for(int cap:List.of(RESOURCE_CAP,65536,FRAME_CAP))for(int delta:List.of(-1,0,1)){
            var node=oracle.createObjectNode();node.put("text","x".repeat(cap-11+delta));byte[] expected=oracle.writeValueAsBytes(node);assertEquals(cap+delta,expected.length);
            if(expected.length<=cap)assertArrayEquals(expected,(byte[])encoded.invoke(null,node,cap));
            else{var failure=assertThrows(java.lang.reflect.InvocationTargetException.class,()->encoded.invoke(null,node,cap));assertInstanceOf(IllegalArgumentException.class,failure.getCause());}
        }
        // No new permissive tree API: unsupported internal types fail closed.
        var floating=oracle.createObjectNode();floating.put("unsupported",1.5);
        var failure=assertThrows(java.lang.reflect.InvocationTargetException.class,()->encoded.invoke(null,floating,RESOURCE_CAP));assertInstanceOf(IllegalArgumentException.class,failure.getCause());
    }
    @Test void exactInspectIsCanonicalAndDoesNotAlterGrant17OrRenew8(){
        var request=new Inspect(grant());byte[] bytes=encode(request);
        String expected="{\"schemaVersion\":2,\"messageType\":\"command.execution.control.inspect\",\"requestId\":\""+ID+"\",\"runtimeDispatchId\":\""+ID+"\",\"startedReceiptId\":\""+ID+"\",\"startedReceiptSha256\":\""+HASH+"\",\"executionGrantId\":\""+ID+"\",\"executionGrantSha256\":\""+HASH+"\"}";
        assertArrayEquals(raw(expected),bytes);assertEquals(request,decode(bytes));
        for(String altered:List.of(" "+expected,expected+"\n",expected.replace("\"schemaVersion\":2","\"schemaVersion\":2.0"),expected.replace("\"schemaVersion\":2","\"schemaVersion\":2,\"schemaVersion\":2"),expected.replace("\"schemaVersion\":2","\"unknown\":0,\"schemaVersion\":2"),expected.replace(",\"messageType\"",", \"messageType\"")))assertThrows(IllegalArgumentException.class,()->decode(raw(altered)));
    }
    @Test void fixedAsciiGrammarKeepsExactUuidHashEffectAndSignedLongBoundaries(){
        for(String id:List.of(ID,"ffffffff-ffff-4fff-bfff-ffffffffffff"))assertEquals(new Inspect(new GrantReference(id,ID,ID,HASH,ID,HASH)),decode(encode(new Inspect(new GrantReference(id,ID,ID,HASH,ID,HASH)))));
        for(String id:List.of("",ID.substring(1),ID+"0",ID.replace('4','3'),ID.replace('8','7'),ID.replace('-','_'),"ffffffff-ffff-4fff-bfff-fffffffffffG",ID+"\n"))assertThrows(IllegalArgumentException.class,()->encode(new Inspect(new GrantReference(id,ID,ID,HASH,ID,HASH))));
        for(String hash:List.of("",HASH.substring(1),HASH+"0",HASH.toUpperCase(),"g"+HASH.substring(1),HASH+"\n"))assertThrows(IllegalArgumentException.class,()->encode(new Inspect(new GrantReference(ID,ID,ID,hash,ID,HASH))));
        for(String effect:List.of("eff_"+HASH.substring(1),"EFF_"+HASH,"eff_"+HASH.toUpperCase(),"eff_"+HASH+"\n"))assertThrows(IllegalArgumentException.class,()->encodeProof(new EffectSuccessReference(ID,ID,effect,Kind.FILE_INPUT_READ,Phase.READ,"READ",HASH,ID,HASH,HASH,HASH)));
        var local=new LocalCommitObservation("eff_"+HASH,HASH,ID,HASH,HASH,0,100,0,100,HASH,true,0,Long.MAX_VALUE);
        String encoded=new String(encodeEvidence(local),StandardCharsets.UTF_8);assertEquals(local,decodeEvidence(raw(encoded)));
        for(String value:List.of("","00","+0","-0"," 0","0 ","0.0","0e0","١","9223372036854775808","18446744073709551615"))assertThrows(IllegalArgumentException.class,()->decodeEvidence(raw(encoded.replace("\"commitAdmittedAt\":\"0\"","\"commitAdmittedAt\":\""+value+"\""))));
    }
    @Test void wholeEmptyFull65536AndMultiChunkBindingsAreStrictAndContentIsImmutable(){
        for(int length:new int[]{0,5,65536}){
            byte[] original=new byte[length];Arrays.fill(original,(byte)42);String digest=hex(sha256(original));
            var resource=new FileStage("pwe-1","task-1","item-1","run-1","output-1",digest,length,0,length);
            var request=new EffectRequest(grant(),ID,"eff_"+HASH,Kind.FILE_STAGE,Phase.STAGE,resource,original);byte[] encoded=encode(request);
            var parsed=(EffectRequest)decode(encoded);assertArrayEquals(original,parsed.content());if(length>0){original[0]=11;assertEquals(42,request.content()[0]);byte[] returned=parsed.content();returned[0]=13;assertEquals(42,parsed.content()[0]);}
            var bad=new FileStage("pwe-1","task-1","item-1","run-1","output-1",HASH,length,0,length);
            assertThrows(IllegalArgumentException.class,()->encode(new EffectRequest(grant(),ID,"eff_"+HASH,Kind.FILE_STAGE,Phase.STAGE,bad,request.content())));
        }
        var part=new FileStage("pwe-1","task-1","item-1","run-1","output-1",HASH,9007199254740998L,9007199254740993L,5);
        assertEquals(part,decodeResource(Kind.FILE_STAGE,Phase.STAGE,encodeResource(Kind.FILE_STAGE,Phase.STAGE,part)));
        assertThrows(IllegalArgumentException.class,()->encodeResource(Kind.FILE_STAGE,Phase.READ,part));
        assertThrows(IllegalArgumentException.class,()->encodeResource(Kind.FILE_STAGE,Phase.STAGE,new FileStage("pwe-1","task-1","item-1","run-1","output-1",HASH,65537,0,65537)));
    }
    private static Control control(Access access,String tenant,String module,long until,long checked,long valid){return new Control(access,ID,"process-1",9007199254740993L,ID,HASH,ID,HASH,ID,0,until,1,1,1,tenant,module,"ACTIVE",null,null,null,checked,valid);}
    @Test void realFrozenSqlFiveTenantAndThreeModuleStatesHaveNoInventedAliases(){
        for(String tenant:List.of("DISABLED","ENABLED","AUTH_CLOSED","WRITE_CLOSED_CLAIM_CLOSED","DRAINING"))for(String module:List.of("LEGACY_CLIENT","QUIESCING","DOMAIN")){
            var value=control(Access.DENIED,tenant,module,3000,1000,2000);assertEquals(value,decodeControl(encodeControl(value)));
        }
        assertEquals(Access.OPEN,decodeControl(encodeControl(control(Access.OPEN,"AUTH_CLOSED","DOMAIN",3000,1000,2000))).access());
        for(String alias:List.of("LEGACY","PREPARING","DOMAIN_PREPARE"))assertThrows(IllegalArgumentException.class,()->encodeControl(control(Access.DENIED,"ENABLED",alias,3000,1000,2000)));
        for(String tenant:List.of("DISABLED","WRITE_CLOSED_CLAIM_CLOSED","DRAINING","WRITE_CLOSED"))assertThrows(IllegalArgumentException.class,()->encodeControl(control(Access.OPEN,tenant,"DOMAIN",3000,1000,2000)));
        var expired=control(Access.DENIED,"ENABLED","DOMAIN",999,1000,999);assertEquals(expired,decodeControl(encodeControl(expired)));
        assertThrows(IllegalArgumentException.class,()->encodeControl(control(Access.OPEN,"ENABLED","DOMAIN",999,1000,999)));
    }
    @Test void stableIdentityHasNoHolderSocketRequestTimeAndResultBytesAreTyped(){
        var resource=new SkillIdentity("installation-1","product-1","sha256:"+HASH,"test-skill","1.0");String stable="[\"1\",\"domain\",\"pool\",\"command\",\"delivery\"]";
        String effect=effectId(stable,HASH,ID,ID,ID,Kind.SKILL_ACTIVATE,Phase.ADMIT,resource);
        assertEquals(effect,effectId(stable,HASH,ID,ID,ID,Kind.SKILL_ACTIVATE,Phase.OBSERVE,resource));
        assertNotEquals(effect,effectId(stable,HASH,ID,ID,UUID.randomUUID().toString(),Kind.SKILL_ACTIVATE,Phase.ADMIT,resource));
        var result=new ReadResult(0,5,5,hex(sha256(raw("hello"))),raw("hello"));byte[] bytes=encodeEffectResult("READ",result);
        assertArrayEquals(bytes,encodeEffectResult("READ",decodeEffectResult("READ",bytes)));
        assertThrows(IllegalArgumentException.class,()->decodeEffectResult("COMMITTED",raw("{}")));
        var denied=new Receipt("command.execution.effect.receipt",ID,ID,ID,HASH,"DENIED",1000,null);assertEquals(denied,decodeReceipt(encodeReceipt(denied)));
    }
    @Test void descriptorsPreserveInitialAbsentWorkspaceAndStrictFourFieldFileContract(){
        var codex=new CodexDescriptor("task-1",null,"legacy-codex","workspace-1",null);assertEquals(codex,decodeDescriptor("CODEX",encodeDescriptor("CODEX",codex)));
        assertThrows(IllegalArgumentException.class,()->encodeResource(Kind.WORKSPACE_PREPARE,Phase.ADMIT,new WorkspacePrepare("task-1",null,"workspace-1",null)));
        var input=new FileInput("input-1","inputs/input.txt","/internal/agent/tasks/task-1/runs/run-1/inputs/input-1/content",5,HASH);
        var output=new FileOutput("output-1","outputs/output.txt","text/plain",65536,"/internal/agent/tasks/task-1/runs/run-1/outputs/output-1/content");
        var file=new FileDescriptor("task-1","run-1",List.of(input),List.of(output));byte[] bytes=encodeDescriptor("WORKSPACE_FILE",file);assertEquals(file,decodeDescriptor("WORKSPACE_FILE",bytes));
        for(String altered:List.of(new String(bytes,StandardCharsets.UTF_8).replace("\"length\":5","\"length\":\"5\""),new String(bytes,StandardCharsets.UTF_8).replace("inputs/input.txt","../escape"),new String(bytes,StandardCharsets.UTF_8).replace("/inputs/input-1/content","/inputs/other/content"),new String(bytes,StandardCharsets.UTF_8).replace("\"inputId\":\"input-1\",\"relativePath\":\"inputs/input.txt\"","\"relativePath\":\"inputs/input.txt\",\"inputId\":\"input-1\"")))assertThrows(IllegalArgumentException.class,()->decodeDescriptor("WORKSPACE_FILE",raw(altered)));
        var overlap=new FileInput("input-1","outputs",input.downloadPath(),5,HASH);assertThrows(IllegalArgumentException.class,()->encodeDescriptor("WORKSPACE_FILE",new FileDescriptor("task-1","run-1",List.of(overlap),List.of(output))));
        var described=new DescribedResult("WORKSPACE_FILE","pwe-1","item-1",file);assertEquals(described,decodeEffectResult("DESCRIBED",encodeEffectResult("DESCRIBED",described)));
        assertThrows(IllegalArgumentException.class,()->encodeEffectResult("DESCRIBED",new DescribedResult("WORKSPACE_FILE",null,"item-1",file)));
    }
    @Test void skillResourceAndDescriptorPreserveFrozenPurchaseDigestAndRejectAliases(){
        var identity=new SkillIdentity("installation-1","product-1","sha256:"+HASH,"test-skill","1.0");
        assertEquals(identity,decodeResource(Kind.SKILL_ACTIVATE,Phase.ADMIT,encodeResource(Kind.SKILL_ACTIVATE,Phase.ADMIT,identity)));
        var descriptor=new SkillDescriptor(identity,5);assertEquals(descriptor,decodeDescriptor("SKILL",encodeDescriptor("SKILL",descriptor)));
        for(String digest:List.of(HASH,"SHA256:"+HASH,"sha256:sha256:"+HASH,"sha256:"+HASH.toUpperCase(),"sha256:"+HASH.substring(1),"sha256:"+HASH+"0"," sha256:"+HASH)){
            var altered=new SkillIdentity(identity.installationId(),identity.productVersionId(),digest,identity.skillKey(),identity.skillVersion());
            assertThrows(IllegalArgumentException.class,()->encodeResource(Kind.SKILL_ACTIVATE,Phase.ADMIT,altered));
            assertThrows(IllegalArgumentException.class,()->encodeDescriptor("SKILL",new SkillDescriptor(altered,5)));
        }
    }
    private static CommitProjection projection(){return new CommitProjection("pwe-1","run-1","manifest-1",HASH,List.of(new CommitItem("output-1","file-1",1,HASH,"text/plain",5)));}
    private static Receipt effectReceipt(String decision,Result result){return new Receipt("command.execution.effect.receipt",ID,ID,ID,HASH,decision,2000,result);}
    @Test void committedReceiptIsTypedAndGenericKnownSettlementCannotProveCommitButMixedOutcomesSurvive(){
        var committed=new CommittedResult("eff_"+HASH,HASH,ID,1000,projection());assertEquals(committed,decodeEffectResult("COMMITTED",encodeEffectResult("COMMITTED",committed)));
        var committedProof=new EffectReceiptProof("eff_"+HASH,HASH,encodeReceipt(effectReceipt("COMMITTED",committed)));
        var generic=new SettledResult("eff_"+HASH,HASH,"SETTLED_KNOWN",null,ID,2000);var genericProof=new EffectReceiptProof("eff_"+HASH,HASH,encodeReceipt(effectReceipt("SETTLED",generic)));
        assertThrows(IllegalArgumentException.class,()->encodeProof(new ExecutionEffectsProof(ID,ID,List.of(genericProof))));
        assertThrows(IllegalArgumentException.class,()->encodeEffectResult("SETTLED",new SettledResult("eff_"+HASH,HASH,"COMMITTED_PROVEN",null,ID,2000)));
        assertThrows(IllegalArgumentException.class,()->encodeEffectResult("SETTLED",new SettledResult("eff_"+HASH,HASH,"NOT_RUN_PROVEN",ID,ID,2000)));
        var absent=new SettledResult("eff_"+"b".repeat(64),HASH,"NOT_RUN_PROVEN",null,ID,2000);var absentProof=new EffectReceiptProof(absent.effectId(),HASH,encodeReceipt(effectReceipt("SETTLED",absent)));
        var mixed=new ExecutionEffectsProof(ID,ID,List.of(committedProof,absentProof));byte[] bytes=encodeProof(mixed);assertArrayEquals(bytes,encodeProof(decodeProof(bytes)));
        assertThrows(IllegalArgumentException.class,()->encodeProof(new ExecutionEffectsProof(ID,ID,List.of(committedProof,committedProof))));
        var reconciled=new Reconciliation(Subject.EXECUTION,ID,"COMMITTED_PROVEN",null,mixed);assertArrayEquals(encodeReconciliation(reconciled),encodeReconciliation(decodeReconciliation(encodeReconciliation(reconciled))));
    }
    @Test void kernelProjectionAndObservationHaveNoCallerMapOrFabricatedKnownDrainFallback(){
        var process=new ProcessProjection(100,1000,"pid:[100]",true,true);var ns=new NamespaceProjection("pid:[100]",2,true,100L,1000L,true,true);var closed=new CloseProjection(0,null,1000);
        var drain=new DrainProjection(ID,"DRAINED_KNOWN",true,closed,closed,List.of(ns),List.of(process),List.of(),closed);assertEquals(drain,decodeDrainProjection(encodeDrainProjection(drain)));
        assertThrows(IllegalArgumentException.class,()->encodeDrainProjection(new DrainProjection(ID,"DRAINED_KNOWN",true,closed,closed,List.of(ns),List.of(process),List.of(new RemainingProjection(100,1000,"pid:[100]")),closed)));
        assertThrows(IllegalArgumentException.class,()->encodeDrainProjection(new DrainProjection(ID,"DRAINED_KNOWN",true,closed,closed,List.of(ns),List.of(process),List.of(),null)));
        var local=new LocalCommitObservation("eff_"+HASH,HASH,ID,HASH,HASH,0,9007199254740993L,0,100,HASH,true,999,1000);assertEquals(local,decodeEvidence(encodeEvidence(local)));
        var observed=new ObservedResult("eff_"+HASH,HASH,"LOCAL_COMMIT_OBSERVED",local);assertEquals(observed,decodeEffectResult("OBSERVED",encodeEffectResult("OBSERVED",observed)));
        assertThrows(IllegalArgumentException.class,()->encodeEffectResult("UNKNOWN",observed));assertThrows(IllegalArgumentException.class,()->encodeEffectResult("OBSERVED",new ObservedResult("eff_"+HASH,HASH,"SERVER_COMMIT",local)));
    }
    @Test void localCommitAdmissionTimeHasExactAdditiveOrderAndRejectsMissingOrNontruthfulValues(){
        for(long[] time:List.of(new long[]{0,0},new long[]{999,1000},new long[]{1000,1000},new long[]{Long.MAX_VALUE,Long.MAX_VALUE})){
            var local=new LocalCommitObservation("eff_"+HASH,HASH,ID,HASH,HASH,0,100,0,100,HASH,true,time[0],time[1]);
            byte[] encoded=encodeEvidence(local);assertEquals(local,decodeEvidence(encoded));
            assertTrue(new String(encoded,StandardCharsets.UTF_8).endsWith("\"commitAdmittedAt\":\""+time[0]+"\",\"observedAt\":\""+time[1]+"\"}"));
        }
        var local=new LocalCommitObservation("eff_"+HASH,HASH,ID,HASH,HASH,0,100,0,100,HASH,true,999,1000);
        String canonical=new String(encodeEvidence(local),StandardCharsets.UTF_8);
        for(String replacement:List.of("\"1001\"","\"-1\"","\"01\"","\"0.5\"","\"9223372036854775808\"","999","null"))assertThrows(IllegalArgumentException.class,()->decodeEvidence(raw(canonical.replace("\"commitAdmittedAt\":\"999\"","\"commitAdmittedAt\":"+replacement))));
        for(String altered:List.of(canonical.replace("\"commitAdmittedAt\":\"999\",",""),canonical.replace("\"commitAdmittedAt\":\"999\",\"observedAt\":\"1000\"","\"observedAt\":\"1000\",\"commitAdmittedAt\":\"999\""),canonical.replace("\"commitAdmittedAt\":\"999\"","\"commitAdmittedAt\":\"999\",\"commitAdmittedAt\":\"999\""),canonical.replace("\"observedAt\":","\"extra\":null,\"observedAt\":"),canonical+"\n"))assertThrows(IllegalArgumentException.class,()->decodeEvidence(raw(altered)));
    }
    @Test void onlyFixedLocalObserveContentCanBecomeTypedObservationAndAdmissionNeverSuccess(){
        var skill=new SkillIdentity("installation-1","product-1","sha256:"+HASH,"test-skill","1.0");
        var prepare=new WorkspacePrepare("task-1","item-1","workspace-1",HASH);var mutate=new WorkspaceMutate("task-1","item-1","workspace-1",HASH,HASH);
        var start=new ProcessStart("task-1","item-1","legacy-codex","workspace-1");var exit=new ProcessExit("task-1","item-1","legacy-codex","workspace-1",100,1000,0,null);
        var close=new CloseProjection(0,null,1000);var drain=new DrainProjection(ID,"DRAINED_KNOWN",true,close,close,List.of(new NamespaceProjection("pid:[100]",2,true,100L,1000L,true,true)),List.of(new ProcessProjection(100,1000,"pid:[100]",true,true)),List.of(),close);
        var resources=List.<Resource>of(skill,prepare,mutate,exit);var kinds=List.of(Kind.SKILL_ACTIVATE,Kind.WORKSPACE_PREPARE,Kind.WORKSPACE_MUTATE,Kind.PROCESS_EXIT);
        for(int i=0;i<resources.size();i++){
            Resource resource=resources.get(i);Kind kind=kinds.get(i);String resourceHash=hex(sha256(encodeResource(kind,Phase.OBSERVE,resource)));
            Evidence observation=kind==Kind.SKILL_ACTIVATE?new LocalCommitObservation("eff_"+HASH,resourceHash,ID,HASH,HASH,0,100,0,100,HASH,true,999,1000):kind==Kind.PROCESS_EXIT?new ProcessDrainObservation(ID,drain,1000):new WorkspaceTreeObservation("eff_"+HASH,resourceHash,ID,"workspace-1",HASH,HASH,HASH,1000);
            var request=new EffectRequest(grant(),ID,"eff_"+HASH,kind,Phase.OBSERVE,resource,encodeEvidence(observation));assertArrayEquals(encode(request),encode(decode(encode(request))));
            if(kind!=Kind.PROCESS_EXIT)assertThrows(IllegalArgumentException.class,()->encode(new EffectRequest(grant(),ID,"eff_"+HASH,kind,Phase.ADMIT,resource,encodeEvidence(observation))));
            assertThrows(IllegalArgumentException.class,()->encode(new EffectRequest(grant(),"22222222-2222-4222-8222-222222222222","eff_"+HASH,kind,Phase.OBSERVE,resource,encodeEvidence(observation))));
            assertThrows(IllegalArgumentException.class,()->encode(new EffectRequest(grant(),ID,"eff_"+HASH,kind,Phase.OBSERVE,resource,raw("{}"))));
            var reference=new EffectSuccessReference(ID,ID,"eff_"+HASH,kind,Phase.OBSERVE,"OBSERVED",resourceHash,ID,HASH,HASH,HASH);assertEquals(reference,decodeProof(encodeProof(reference)));
            assertThrows(IllegalArgumentException.class,()->encodeProof(new EffectSuccessReference(ID,ID,"eff_"+HASH,kind,Phase.ADMIT,"ADMITTED",resourceHash,ID,HASH,HASH,HASH)));
        }
        byte[] content=encodeEvidence(new ProcessDrainObservation(ID,drain,1000));assertThrows(IllegalArgumentException.class,()->encode(new EffectRequest(grant(),ID,"eff_"+HASH,Kind.PROCESS_START,Phase.OBSERVE,start,content)));
        var reference=new EffectSuccessReference(ID,ID,"eff_"+HASH,Kind.PROCESS_START,Phase.OBSERVE,"OBSERVED",HASH,ID,HASH,HASH,HASH);assertEquals(reference,decodeProof(encodeProof(reference)));
    }
    @Test void originalDescriptiveSuccessProofsCannotBecomePerEffectMutationClaims(){
        var descriptor=new FileDescriptor("task-1","run-1",List.of(),List.of(new FileOutput("output-1","outputs/output.txt","text/plain",65536,"/internal/agent/tasks/task-1/runs/run-1/outputs/output-1/content")));
        var results=List.<Result>of(new DescribedResult("WORKSPACE_FILE","pwe-1","item-1",descriptor),new ReadResult(0,5,5,hex(sha256(raw("hello"))),raw("hello")),new StagedResult("output-1",HASH,5));var decisions=List.of("DESCRIBED","READ","STAGED");var proofs=new java.util.ArrayList<EffectReceiptProof>();
        for(int index=0;index<results.size();index++){var proof=new EffectReceiptProof("eff_"+Integer.toString(index+1).repeat(64),HASH,encodeReceipt(effectReceipt(decisions.get(index),results.get(index))));proofs.add(proof);assertArrayEquals(encodeProof(proof),encodeProof(decodeProof(encodeProof(proof))));for(String disposition:List.of("COMMITTED_PROVEN","SETTLED_KNOWN","NOT_RUN_PROVEN"))assertThrows(IllegalArgumentException.class,()->encodeReconciliation(new Reconciliation(Subject.EFFECT,proof.effectId(),disposition,null,proof)));}
        assertThrows(IllegalArgumentException.class,()->encodeProof(new ExecutionEffectsProof(ID,ID,proofs)));
        byte[] ack=AgentRuntimeV2ExecutionCodec.encodeAck(new AgentRuntimeV2ExecutionCodec.Ack("terminal-1",ID,"source-1","SUCCEEDED",2000));byte[] terminal=AgentRuntimeV2WireCodec.encodeReceipt(new AgentRuntimeV2WireCodec.Receipt(ID,ID,"terminal-1","source-1","SUCCEEDED",2000,2000,3,3,ID));var committed=new CommittedResult("eff_"+HASH,HASH,ID,1000,projection());proofs.add(new EffectReceiptProof(committed.effectId(),HASH,encodeReceipt(effectReceipt("COMMITTED",committed))));var complete=new ExecutionSettlementProof(ID,ID,ack,terminal,proofs);assertArrayEquals(encodeProof(complete),encodeProof(decodeProof(encodeProof(complete))));
    }
    @Test void compactGraphAndOriginalSuccessReferenceAreStrictBoundedAndNotMutationAuthority(){
        var reference=new EffectSuccessReference(ID,ID,"eff_"+HASH,Kind.FILE_INPUT_READ,Phase.READ,"READ",HASH,ID,HASH,HASH,HASH);assertEquals(reference,decodeProof(encodeProof(reference)));
        var result=new Reconciliation(Subject.EFFECT_SUCCESS,reference.effectId(),"SUCCESS_REFERENCE",null,reference);assertEquals(result,decodeReconciliation(encodeReconciliation(result)));
        assertThrows(IllegalArgumentException.class,()->encodeReconciliation(new Reconciliation(Subject.EFFECT,reference.effectId(),"SUCCESS_REFERENCE",null,reference)));
        assertThrows(IllegalArgumentException.class,()->encodeReconciliation(new Reconciliation(Subject.EFFECT_SUCCESS,reference.effectId(),"UNKNOWN",null,reference)));
        assertThrows(IllegalArgumentException.class,()->encodeProof(new EffectSuccessReference(ID,ID,"eff_"+HASH,Kind.FILE_INPUT_READ,Phase.READ,"COMMITTED",HASH,ID,HASH,HASH,HASH)));
        byte[] ack=AgentRuntimeV2ExecutionCodec.encodeAck(new AgentRuntimeV2ExecutionCodec.Ack("terminal-1",ID,"source-1","SUCCEEDED",2000));byte[] terminal=AgentRuntimeV2WireCodec.encodeReceipt(new AgentRuntimeV2WireCodec.Receipt(ID,ID,"terminal-1","source-1","SUCCEEDED",2000,2000,3,3,ID));
        for(int count:new int[]{0,129,4096}){
            var members=new ArrayList<EffectSuccessReference>();for(int i=0;i<count;i++)members.add(new EffectSuccessReference(ID,ID,"eff_"+String.format("%064x",i),Kind.FILE_INPUT_READ,Phase.READ,"READ",HASH,ID,HASH,HASH,HASH));
            String digest=completeGraphSha256(ID,ID,HASH,HASH,ID,members);var reversed=new ArrayList<>(members);Collections.reverse(reversed);assertEquals(digest,completeGraphSha256(ID,ID,HASH,HASH,ID,reversed));var graph=new ExecutionSettlementGraphProof(ID,ID,ack,terminal,count,digest);assertArrayEquals(encodeProof(graph),encodeProof(decodeProof(encodeProof(graph))));assertTrue(encodeProof(graph).length<65536);var settled=new Reconciliation(Subject.EXECUTION,ID,"SETTLED_KNOWN",null,graph);assertArrayEquals(encodeReconciliation(settled),encodeReconciliation(decodeReconciliation(encodeReconciliation(settled))));
            assertNotEquals(digest,completeGraphSha256(ID,ID,"b".repeat(64),HASH,ID,members));if(count>0){assertNotEquals(digest,completeGraphSha256(ID,ID,HASH,HASH,ID,members.subList(0,count-1)));members.add(members.getFirst());assertThrows(IllegalArgumentException.class,()->completeGraphSha256(ID,ID,HASH,HASH,ID,members));}
        }
        for(long count:new long[]{-1,4097})assertThrows(IllegalArgumentException.class,()->encodeProof(new ExecutionSettlementGraphProof(ID,ID,ack,terminal,count,HASH)));
        String raw=new String(encodeProof(new ExecutionSettlementGraphProof(ID,ID,ack,terminal,129,HASH)),StandardCharsets.UTF_8);for(String altered:List.of(raw.replace("\"effectCount\":\"129\"","\"effectCount\":129"),raw.replace("\"129\"","\"0129\""),raw.replace("\"effectCount\":\"129\"","\"effectCount\":\"4097\"")," "+raw,raw.replace("\"effectGraphSha256\":","\"extra\":null,\"effectGraphSha256\":")))assertThrows(IllegalArgumentException.class,()->decodeProof(raw(altered)));
    }
    @Test void recoveryMappingUsesExactOriginalRenewalAndUnknownNeverBecomesDenialOrAbsenceProof(){
        String other="22222222-2222-4222-8222-222222222222";
        byte[] renewal=AgentRuntimeV2ExecutionCodec.encodeRenew(new AgentRuntimeV2ExecutionCodec.Renew(ID,ID,ID,HASH,ID,HASH));
        byte[] grant=AgentRuntimeV2WireCodec.encodeExecutionGrant(new AgentRuntimeV2WireCodec.ExecutionGrant(other,"RENEWAL",ID,ID,HexFormat.of().parseHex(HASH),ID,"process-1",0,1,ID,other,1,3000,ID,1000));
        var mapping=new RenewalMapping(ID,hex(sha256(renewal)),ID,ID,HASH,ID,HASH,other,HASH,grant);var history=new Reconciliation(Subject.RENEWAL,ID,"GRANTED_HISTORICAL",hex(sha256(renewal)),mapping);
        byte[] receipt=encodeReceipt(new Receipt("command.execution.reconcile.receipt",other,ID,ID,HASH,"GRANTED_HISTORICAL",2000,history));assertArrayEquals(receipt,encodeReceipt(decodeReceipt(receipt)));
        var request=new Reconcile(other,ID,Subject.RENEWAL,ID,renewal,null);assertArrayEquals(encode(request),encode(decode(encode(request))));
        assertThrows(IllegalArgumentException.class,()->encode(new Reconcile(ID,ID,Subject.RENEWAL,ID,renewal,null)));
        assertThrows(IllegalArgumentException.class,()->encodeReconciliation(new Reconciliation(Subject.RENEWAL,ID,"DENIED_HISTORICAL",hex(sha256(renewal)),mapping)));
        var unknown=new Reconciliation(Subject.RENEWAL,ID,"UNKNOWN",hex(sha256(renewal)),null);assertEquals(unknown,decodeReconciliation(encodeReconciliation(unknown)));
        assertThrows(IllegalArgumentException.class,()->encodeReconciliation(new Reconciliation(Subject.RENEWAL,ID,"UNKNOWN",hex(sha256(renewal)),mapping)));
        var barrier=new RetirementBarrier(ID,hex(sha256(renewal)),ID,ID,HASH,other,HASH,other,HASH,2000);var notCommitted=new Reconciliation(Subject.RENEWAL,ID,"NOT_COMMITTED",hex(sha256(renewal)),barrier);assertEquals(notCommitted,decodeReconciliation(encodeReconciliation(notCommitted)));
        assertThrows(IllegalArgumentException.class,()->encodeProof(new RetirementBarrier(ID,HASH,ID,ID,HASH,ID,HASH,other,HASH,2000)));
    }
}
