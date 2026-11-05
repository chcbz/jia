package cn.jia.agent.api;

import cn.jia.agent.skill.SkillPackagePublicationService;
import cn.jia.agent.skill.SkillPublicationToolArchive;
import cn.jia.chat.service.ConversationToolProvider;
import tools.jackson.databind.JsonNode;
import java.util.*;
import static cn.jia.agent.skill.SkillMarketplaceException.require;

/** Business tool schemas and protocol conversion. Runtime transports them without skill-name branches. */
public final class SkillConversationTools {
    private SkillConversationTools() { }
    private static final Map<String,Object> TEXT=Map.of("type","string");
    private static final Map<String,Object> DECIMAL=Map.of("type","string","pattern","^(0|[1-9][0-9]*)$");
    private static Map<String,Object> schema(Map<String,Object> fields) {
        return Map.of("type","object","properties",fields,"required",new ArrayList<>(fields.keySet()),"additionalProperties",false);
    }
    private static Map<String,Object> tool(String name,String description,Map<String,Object> fields) {
        return Map.of("type","function","name",name,"description",description,"inputSchema",schema(fields));
    }
    public static List<Map<String,Object>> catalog() {
        return List.of(
            tool("skill_list","分页查询已发布技能；返回真实ID、简述和商品版本。",Map.of("offset",DECIMAL,"limit",DECIMAL)),
            tool("skill_read","读取指定技能当前元数据与商品版本，包括下架状态。",Map.of("productId",TEXT)),
            tool("skill_package_files","读取已审核版本的实际包。path为空时列出文件；指定path时读取该文件，先读SKILL.md再按需读脚本。",Map.of("productVersionId",TEXT,"path",TEXT)),
            tool("skill_update_metadata","按当前商品版本更新标题与描述，不修改已发布技能包。",Map.of("productId",TEXT,"name",TEXT,"description",TEXT,"expectedProductVersion",DECIMAL)),
            tool("skill_retire","下架用户指定技能；不卸载现有安装或删除历史。",Map.of("productId",TEXT,"expectedProductVersion",DECIMAL)),
            tool("skill_publish","审核通过并发布原生技能新版本。提交已审阅的完整文件；工具计算真实ZIP摘要。旧版本不可覆盖，包必须含SKILL.md，不包含旧执行manifest.json。",Map.ofEntries(
                Map.entry("productId",TEXT),Map.entry("productVersionId",TEXT),Map.entry("name",TEXT),Map.entry("description",TEXT),
                Map.entry("skillKey",TEXT),Map.entry("skillVersion",TEXT),Map.entry("priceMicro",DECIMAL),
                Map.entry("approvedPermissionsManifest",TEXT),Map.entry("expectedProductVersion",DECIMAL),
                Map.entry("files",Map.of("type","array","items",schema(Map.of("path",TEXT,"content",TEXT,"encoding",Map.of("type","string","enum",List.of("utf8","base64")))))))));
    }
    public static Object call(SkillConversationToolAdapter service,ConversationToolProvider.Context context,ConversationToolProvider.Boundary boundary,String tool,JsonNode args) {
        var spec=catalog().stream().filter(value->tool.equals(value.get("name"))).findFirst().orElse(null);
        require(spec!=null,400,"CONVERSATION_TOOL_UNKNOWN");
        require(args!=null&&args.isObject(),400,"BAD_REQUEST");
        var actual=new HashSet<String>();args.propertyNames().forEach(actual::add);
        var fields=(Map<?,?>)((Map<?,?>)spec.get("inputSchema")).get("properties");
        require(actual.equals(fields.keySet()),400,"BAD_REQUEST");
        return switch(tool) {
            case "skill_list" -> service.list(context,boundary,integer(args,"offset"),integer(args,"limit"));
            case "skill_read" -> service.read(context,boundary,text(args,"productId"));
            case "skill_package_files" -> service.readPackage(context,boundary,text(args,"productVersionId"),text(args,"path"));
            case "skill_update_metadata" -> service.updateMetadata(context,boundary,new SkillPackagePublicationService.UpdateMetadata(
                    text(args,"productId"),text(args,"name"),text(args,"description"),number(args,"expectedProductVersion")));
            case "skill_retire" -> service.retire(context,boundary,new SkillPackagePublicationService.RetireProduct(text(args,"productId"),number(args,"expectedProductVersion")));
            case "skill_publish" -> {
                require(args.get("files").isArray(),400,"BAD_REQUEST");
                var files=new ArrayList<SkillPublicationToolArchive.File>();
                for(var file:args.get("files"))files.add(new SkillPublicationToolArchive.File(text(file,"path"),text(file,"content"),text(file,"encoding")));
                var command=new SkillPackagePublicationService.Publish(text(args,"productId"),text(args,"productVersionId"),text(args,"name"),text(args,"description"),
                        text(args,"skillKey"),text(args,"skillVersion"),number(args,"priceMicro"),"NONE",text(args,"approvedPermissionsManifest"),null,null,number(args,"expectedProductVersion"),null);
                yield service.publish(context,boundary,command,files);
            }
            default -> throw new IllegalArgumentException();
        };
    }
    public static Object wire(Object value) {
        var result=new LinkedHashMap<String,Object>();
        if(value instanceof SkillPackagePublicationService.MetadataReceipt r) {
            result.put("productId",r.productId());result.put("name",r.name());result.put("description",r.description());
            result.put("productVersion",Long.toString(r.productVersion()));result.put("disposition",r.disposition());
        } else if(value instanceof SkillPackagePublicationService.RetirementReceipt r) {
            result.put("productId",r.productId());result.put("status",r.status());result.put("productVersion",Long.toString(r.productVersion()));result.put("disposition",r.disposition());
        } else if(value instanceof SkillPackagePublicationService.Receipt r) {
            result.put("productId",r.productId());result.put("productVersionId",r.productVersionId());result.put("productVersion",Long.toString(r.productVersion()));
            result.put("packageSha256",r.packageSha256());result.put("manifestSha256",r.manifestSha256());result.put("disposition",r.disposition());
        } else return value;
        return result;
    }
    private static String text(JsonNode node,String field) {require(node!=null&&node.isObject(),400,"BAD_REQUEST");var value=node.get(field);require(value!=null&&value.isTextual(),400,"BAD_REQUEST");return value.textValue();}
    private static int integer(JsonNode node,String field) {long value=number(node,field);require(value<=Integer.MAX_VALUE,400,"BAD_REQUEST");return (int)value;}
    private static long number(JsonNode node,String field) {String value=text(node,field);require(value.matches("0|[1-9][0-9]*"),400,"BAD_REQUEST");return Long.parseLong(value);}
}
