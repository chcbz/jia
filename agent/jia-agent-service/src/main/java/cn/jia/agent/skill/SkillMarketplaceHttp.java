package cn.jia.agent.skill;
import cn.jia.agent.hosting.HostingRentHttp;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.type.TypeReference;
import java.util.*;
import static cn.jia.agent.skill.SkillMarketplaceException.require;

/** Decimal strings and an explicitly approved permission array; unknown/duplicate fields fail closed. */
public final class SkillMarketplaceHttp {
    private static final JsonMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private SkillMarketplaceHttp() { }
    public static Map<String,String> body(byte[] raw,boolean purchase) {
        require(raw!=null && raw.length>0 && raw.length<=8192,400,"BAD_REQUEST");
        try {
            Map<String,Object> node=JSON.readValue(raw,new TypeReference<Map<String,Object>>(){});
            Set<String> fields=purchase?Set.of("quoteId","productVersionId","targetAgentId","expectedPriceMicro","expectedAgentVersion","approvedPermissions")
                    :Set.of("productVersionId","targetAgentId","expectedAgentVersion");
            require(node!=null && node.keySet().equals(fields),400,"BAD_REQUEST");
            Map<String,String> out=new TreeMap<>();
            for(String name:fields) {
                Object value=node.get(name);
                if(name.equals("approvedPermissions")) {
                    require(value instanceof List<?>,400,"BAD_REQUEST");
                    List<?> list=(List<?>)value; require(list.size()<=32,400,"BAD_REQUEST");
                    Set<String> seen=new HashSet<>(); List<String> approved=new ArrayList<>();
                    for(Object item:list) {
                        require(item instanceof String,400,"BAD_REQUEST");
                        String text=(String)item; HostingRentHttp.exact(text,100);
                        require(seen.add(text),400,"BAD_REQUEST"); approved.add(text);
                    }
                    approved.sort(String::compareTo); out.put(name,JSON.writeValueAsString(approved));
                } else {
                    require(value instanceof String,400,"BAD_REQUEST");
                    String text=(String)value; HostingRentHttp.exact(text,100); out.put(name,text);
                }
            }
            HostingRentHttp.positive(out.get("expectedAgentVersion"));
            if(purchase) {
                String price=out.get("expectedPriceMicro");
                require(price.matches("0|[1-9][0-9]{0,18}"),400,"BAD_REQUEST"); Long.parseLong(price);
            }
            return Collections.unmodifiableMap(out);
        } catch(SkillMarketplaceException e) { throw e; }
        catch(Exception e) { throw new SkillMarketplaceException(400,"BAD_REQUEST"); }
    }
    static List<String> permissions(String raw) {
        return JSON.readValue(raw,new TypeReference<List<String>>(){});
    }
}
