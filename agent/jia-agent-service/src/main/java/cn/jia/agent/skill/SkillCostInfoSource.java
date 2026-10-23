package cn.jia.agent.skill;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.util.*;

/** Public informational metadata only. Never consulted by authorization, readiness or effects.
 * The same shared binding file is consumed by the runtime; secrets remain outside this file. */
@Service
public final class SkillCostInfoSource {
    private static final JsonMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private static final Set<String> FIELDS=Set.of("mode","amount","currency","unit","description","source","updatedAt","informationRevision");
    private final Path file;
    public SkillCostInfoSource(@Value("${agent.skill.bindings-file:/home/isp/conf/cyf-agent-runtime-v1/skill-bindings.json}") String file) { this.file=Path.of(file); }
    /** One read per directory; an absent, inaccessible or malformed informational source cannot block a request. */
    public JsonNode read() {
        try {
            if(!file.isAbsolute()||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||!file.toRealPath().equals(file.normalize()))return JSON.createObjectNode();
            var document=JSON.readTree(Files.readAllBytes(file));
            if(document==null||!document.isObject()||document.path("schemaVersion").asInt()!=1||!document.path("costInfoOverrides").isObject())return JSON.createObjectNode();
            return document;
        } catch(Exception failure) { return JSON.createObjectNode(); }
    }
    /** Keys are skillKey@skillVersion, optionally suffixed #bindingId. Binding identity is resolved
     * by the server from this subject's configured provider alias, never supplied by the browser. */
    public String decorate(JsonNode config,String agentId,String manifestJson) {
        try {
            ObjectNode manifest=(ObjectNode)JSON.readTree(manifestJson);
            String key=manifest.path("skillKey").asText()+"@"+manifest.path("skillVersion").asText();
            String alias=manifest.path("requirements").path("providerAlias").asText("");
            String binding=config.path("subjects").path(agentId).path("providerAliases").path(alias).asText("");
            JsonNode overrides=config.path("costInfoOverrides");
            JsonNode selected=!binding.isEmpty()&&overrides.has(key+"#"+binding)?overrides.get(key+"#"+binding):overrides.get(key);
            if(selected==null)return manifestJson;
            ObjectNode value=JSON.createObjectNode();value.put("mode","UNKNOWN");
            boolean valid=selected.isObject()&&Set.of("FREE","FIXED","METERED","UNKNOWN").contains(selected.path("mode").asText());
            if(valid) {
                for(var names=selected.fieldNames();names.hasNext();)if(!FIELDS.contains(names.next()))valid=false;
                for(String name:FIELDS)if(selected.hasNonNull(name)&&!selected.get(name).isTextual())valid=false;
                if(selected.hasNonNull("amount")&&!selected.get("amount").asText().matches("(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?"))valid=false;
                if(!selected.path("informationRevision").asText().matches("[1-9][0-9]*"))valid=false;
            }
            if(valid)selected.fields().forEachRemaining(e->value.set(e.getKey(),e.getValue().deepCopy()));
            manifest.set("costInfo",value);return manifest.toString();
        } catch(Exception ignored) {return manifestJson;}
    }
}
