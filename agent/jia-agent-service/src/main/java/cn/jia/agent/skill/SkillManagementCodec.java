package cn.jia.agent.skill;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.*;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import com.networknt.schema.*;
import com.networknt.schema.regex.JoniRegularExpressionFactory;
import java.nio.*;
import java.nio.charset.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static cn.jia.agent.skill.SkillMarketplaceException.require;

/** SM-C1 wire boundary, shared by real HTTP, command and result adapters. Historical hashes stay untouched. */
public final class SkillManagementCodec {
    private static final ObjectMapper JSON = JsonMapper.builderWithJackson2Defaults()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final JsonNode SCHEMA;
    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_7,
            b -> b.schemaRegistryConfig(SchemaRegistryConfig.builder().typeLoose(false)
                    .regularExpressionFactory(JoniRegularExpressionFactory.getInstance()).build())
                    .schemaLoader(l -> l.fetchRemoteResources(false)));
    private static final Map<String,Schema> DEFINITIONS = new ConcurrentHashMap<>();
    public static final Comparator<String> UTF8 = (a,b) -> Arrays.compareUnsigned(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8));
    static {
        try(var in=SkillManagementCodec.class.getResourceAsStream("management-contract-v1.schema.json")) {
            SCHEMA=JSON.readTree(Objects.requireNonNull(in));
        } catch(Exception e) { throw new ExceptionInInitializerError(e); }
    }
    private SkillManagementCodec() {}
    public static Map<String,Object> read(byte[] bytes,String definition) {
        try {
            String raw=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            // Validate original number tokens before a tree parser can normalize -0 or 1e0.
            try(var parser=JSON.createParser(raw)) {
                while(parser.nextToken()!=null) {
                    if(parser.currentToken().isNumeric()) require(parser.currentToken()==JsonToken.VALUE_NUMBER_INT
                            && parser.getText().matches("0|[1-9][0-9]*"),400,"BAD_REQUEST");
                }
            }
            var node=JSON.readTree(raw); unicode(node);
            if(node.isObject()&&node.has("protocolVersion"))require(node.get("protocolVersion").isIntegralNumber()&&node.get("protocolVersion").intValue()==1,400,"PROTOCOL_VERSION_UNSUPPORTED");
            if(definition!=null)validate(definition,node);
            return JSON.convertValue(node,new tools.jackson.core.type.TypeReference<LinkedHashMap<String,Object>>(){});
        } catch(SkillMarketplaceException e) { throw e; }
        catch(Exception e) { throw new SkillMarketplaceException(400,"BAD_REQUEST"); }
    }
    /** Called on the complete registration wire BEFORE generic map normalization. */
    public static void validateEmbeddedDeclaration(String raw) {
        try(var parser=JSON.createParser(raw)) {
            while(parser.nextToken()!=null) {
                if(parser.currentToken()==JsonToken.PROPERTY_NAME&&"skillExecution".equals(parser.currentName())) {
                    parser.nextToken();int start=Math.toIntExact(parser.currentTokenLocation().getCharOffset());
                    parser.skipChildren();int end=Math.toIntExact(parser.currentLocation().getCharOffset());
                    read(raw.substring(start,end).getBytes(StandardCharsets.UTF_8),null);
                }
            }
        } catch(SkillMarketplaceException invalid){throw invalid;}
        catch(Exception invalid){throw new SkillMarketplaceException(400,"BAD_REQUEST");}
    }
    public static void validate(String definition,Object value) {
        var node=value instanceof JsonNode n?n:JSON.valueToTree(value);
        var schema=DEFINITIONS.computeIfAbsent(definition,name->{
            var root=((ObjectNode)SCHEMA).deepCopy();
            // Each wrapper selects a different root definition. Registry caches are keyed by
            // canonical schema URI, so sharing the frozen corpus $id would alias these roots.
            // The checked-in corpus itself is never changed.
            root.put("$id",SCHEMA.path("$id").asText()+"?definition="+name);
            root.put("$ref","#/definitions/"+name);
            return REGISTRY.getSchema(root.toString(),InputFormat.JSON);
        });
        require(schema.validate(node.toString(),InputFormat.JSON).isEmpty(),400,"BAD_REQUEST");
        unicode(node);
    }
    /** Separate v5 execution binding; the frozen v1 definition and its digest remain unchanged. */
    public static void validateNativeTaskBinding(Object value) {
        var node=value instanceof JsonNode n?n:JSON.valueToTree(value);
        var schema=DEFINITIONS.computeIfAbsent("NativeTaskSkillBindingV5",name->{
            var root=((ObjectNode)SCHEMA).deepCopy();
            root.put("$id",SCHEMA.path("$id").asText()+"?definition="+name);
            var definitions=(ObjectNode)root.get("definitions");
            var binding=((ObjectNode)definitions.get("TaskSkillBinding")).deepCopy();
            var manifest=((ObjectNode)binding.get("properties")).putObject("manifestSha256");
            var options=manifest.putArray("anyOf");
            options.addObject().put("$ref","#/definitions/Sha256");
            options.addObject().put("type","null");
            definitions.set(name,binding);root.put("$ref","#/definitions/"+name);
            return REGISTRY.getSchema(root.toString(),InputFormat.JSON);
        });
        require(schema.validate(node.toString(),InputFormat.JSON).isEmpty(),400,"BAD_REQUEST");
        unicode(node);
    }
    public static Map<String,Object> object(Object value) {
        require(value instanceof Map<?,?>,400,"BAD_REQUEST");
        @SuppressWarnings("unchecked") var map=(Map<String,Object>)value;return map;
    }
    public static String text(Map<String,Object> m,String key) { return (String)m.get(key); }
    public static String json(Object value) {var out=new StringBuilder();append(out,value);return out.toString();}
    public static byte[] bytes(Object value) {return json(value).getBytes(StandardCharsets.UTF_8);}
    public static String hash(String domain,Object value) {
        try {var digest=MessageDigest.getInstance("SHA-256");digest.update(domain.getBytes(StandardCharsets.UTF_8));digest.update((byte)0);
            return HexFormat.of().formatHex(digest.digest(bytes(value)));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    public static Map<String,Object> map(Object... pairs) {
        var out=new LinkedHashMap<String,Object>();for(int i=0;i<pairs.length;i+=2)out.put((String)pairs[i],pairs[i+1]);return out;
    }
    public static String path(String id) {
        validate("Id",id);
        require(id.codePoints().noneMatch(c->c=='/'||c=='\\'||c=='?'||c=='#'||Character.isISOControl(c)),400,"BAD_REQUEST");return id;
    }
    private static void unicode(JsonNode node) {
        require(node!=null,400,"BAD_REQUEST");
        if(node.isTextual())checkText(node.textValue());
        if(node.isObject())node.properties().iterator().forEachRemaining(e->{checkText(e.getKey());unicode(e.getValue());});
        else if(node.isArray())node.forEach(SkillManagementCodec::unicode);
    }
    private static void checkText(String s) {
        for(int i=0;i<s.length();i++) {
            char c=s.charAt(i);if(Character.isHighSurrogate(c))require(++i<s.length()&&Character.isLowSurrogate(s.charAt(i)),400,"BAD_REQUEST");
            else require(!Character.isLowSurrogate(c),400,"BAD_REQUEST");
        }
    }
    private static void append(StringBuilder out,Object v) {
        if(v==null)out.append("null");
        else if(v instanceof String s) {checkText(s);out.append('"');for(int i=0;i<s.length();i++) {char c=s.charAt(i);switch(c) {
            case '"'->out.append("\\\"");case '\\'->out.append("\\\\");case '\b'->out.append("\\b");case '\f'->out.append("\\f");
            case '\n'->out.append("\\n");case '\r'->out.append("\\r");case '\t'->out.append("\\t");
            default->{if(c<32)out.append(String.format(Locale.ROOT,"\\u%04x",(int)c));else out.append(c);}
        }}out.append('"');}
        else if(v instanceof Boolean)out.append(v);
        else if(v instanceof Byte||v instanceof Short||v instanceof Integer||v instanceof Long||v instanceof java.math.BigInteger) {
            var n=new java.math.BigInteger(v.toString());require(n.signum()>=0&&n.compareTo(java.math.BigInteger.valueOf(9007199254740991L))<=0,400,"BAD_REQUEST");out.append(n);
        } else if(v instanceof Map<?,?> m) {
            var keys=new ArrayList<String>();for(var key:m.keySet()){require(key instanceof String,400,"BAD_REQUEST");keys.add((String)key);}keys.sort(UTF8);
            out.append('{');boolean comma=false;for(var key:keys){if(comma)out.append(',');append(out,key);out.append(':');append(out,m.get(key));comma=true;}out.append('}');
        } else if(v instanceof Iterable<?> list) {out.append('[');boolean comma=false;for(var item:list){if(comma)out.append(',');append(out,item);comma=true;}out.append(']');}
        else throw new SkillMarketplaceException(400,"BAD_REQUEST");
    }
}
