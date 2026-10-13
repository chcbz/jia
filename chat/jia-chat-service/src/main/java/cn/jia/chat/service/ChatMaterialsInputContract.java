package cn.jia.chat.service;
import java.util.*;
import cn.jia.core.util.JsonUtil;

/** Public native-input transport facts, not a skill grant or an independent analysis turn. */
public final class ChatMaterialsInputContract {
    private ChatMaterialsInputContract() { }
    @SuppressWarnings("unchecked") public static Map<String,Object> declaration(Object raw) {
        if(!(raw instanceof Map<?,?> m)||!m.keySet().equals(Set.of("schemaVersion","supportedInputs"))
                ||!Integer.valueOf(1).equals(m.get("schemaVersion"))||!(m.get("supportedInputs") instanceof List<?> list))throw new IllegalArgumentException("materials declaration");
        var inputs=new ArrayList<Map<String,Object>>();var seen=new HashSet<String>();
        for(var value:list) {
            if(!(value instanceof Map<?,?> item)||!item.keySet().equals(Set.of("mediaKind","mimeType","carrier","carrierContractDigest")))throw new IllegalArgumentException("materials input");
            String media=(String)item.get("mediaKind"),mime=(String)item.get("mimeType"),carrier=(String)item.get("carrier"),digest=(String)item.get("carrierContractDigest");
            if(media==null||!Set.of("text","image","audio","file").contains(media)||mime==null||!mime.matches("[a-z0-9.+-]+/[a-z0-9.+-]+")
                    ||carrier==null||!Set.of("DIRECT_TEXT","LOCAL_IMAGE","LOCAL_AUDIO","PARSED_TEXT").contains(carrier)
                    ||digest==null||!digest.matches("sha256:[0-9a-f]{64}")||!seen.add(mime)
                    ||!Map.of("DIRECT_TEXT","text","LOCAL_IMAGE","image","LOCAL_AUDIO","audio","PARSED_TEXT","file").get(carrier).equals(media))throw new IllegalArgumentException("materials input");
            inputs.add(Map.of("mediaKind",media,"mimeType",mime,"carrier",carrier,"carrierContractDigest",digest));
        }
        return Map.of("schemaVersion",1,"supportedInputs",List.copyOf(inputs));
    }
    public static Map<String,Object> candidates(Map<String,Object> registration,List<Map<String,Object>> catalog) {
        Object raw=registration.get("materialsInput");if(raw==null)return Map.of("profile",Map.of("schemaVersion",1,"supportedInputs",List.of()),"sources",List.of());
        var profile=declaration(raw);var selected=new ArrayList<Map<String,Object>>();
        for(var source:catalog)for(Object supported:(List<?>)profile.get("supportedInputs")) {
            var input=(Map<?,?>)supported;if(!source.get("contentMimeType").equals(input.get("mimeType")))continue;
            selected.add(Map.of("sourceRefId",source.get("sourceRefId"),"sourceKind",source.get("sourceKind"),"sha256",source.get("contentHash"),
                    "byteLength",source.get("byteLength"),"mimeType",source.get("contentMimeType"),"carrier",input.get("carrier"),"carrierContractDigest",input.get("carrierContractDigest")));
        }
        selected.sort(Comparator.comparing(x->(String)x.get("sourceRefId")));return Map.of("profile",profile,"sources",List.copyOf(selected));
    }
    @SuppressWarnings("unchecked") public static Map<String,Object> bind(Object candidates,String tenant,String owner,String client,
            String conversation,long generation,String task,String request,String turn,String target) {
        var c=(Map<String,Object>)candidates;var selected=(List<Map<String,Object>>)c.get("sources");if(selected.isEmpty())return null;
        var sources=new ArrayList<Map<String,Object>>();
        for(var source:selected) {var copy=new LinkedHashMap<>(source);copy.put("downloadUrl","/internal/agent/chat/requests/"+request+"/turns/"+turn+"/materials/inputs/"+source.get("sourceRefId")+"/content");sources.add(Collections.unmodifiableMap(copy));}
        var manifest=Map.of("schemaVersion",1,"profile",c.get("profile"),"sources",List.copyOf(sources));
        var binding=Map.of("tenantId",tenant,"ownerJiacn",owner,"clientId",client,"conversationId",conversation,"conversationGeneration",Long.toString(generation),
                "taskId",task,"requestId",request,"turnId",turn,"targetAgentId",target);
        String digest=ChatDeliberationService.digest(Map.of("domain","juyiting.materials-input","binding",binding,"manifest",manifest));
        return Map.of("schemaVersion",1,"authorizationId","materials_"+digest.substring(7,47),"manifestDigest",digest,"manifest",manifest);
    }
    @SuppressWarnings("unchecked") public static Map<String,Object> authority(Object raw) {
        if(raw==null)return null;var m=(Map<String,Object>)raw;var manifest=(Map<String,Object>)m.get("manifest");
        return Map.of("authorizationId",m.get("authorizationId"),"manifestDigest",m.get("manifestDigest"),"sources",manifest.get("sources"));
    }
    public static List<String> refs(Object raw) {
        var a=authority(raw);if(a==null)return List.of();return ((List<?>)a.get("sources")).stream().map(x->(String)((Map<?,?>)x).get("sourceRefId")).toList();
    }
}
