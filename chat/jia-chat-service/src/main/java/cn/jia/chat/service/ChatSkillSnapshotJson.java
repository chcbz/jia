package cn.jia.chat.service;

import cn.jia.core.util.JsonUtil;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Exact core snapshot/result encoding: sorted object keys, array order retained, Jackson JSON scalars. */
public final class ChatSkillSnapshotJson {
    private ChatSkillSnapshotJson() { }
    static Object canonical(Object value) {
        if(value instanceof Map<?,?> map) {var result=new TreeMap<String,Object>();for(var e:map.entrySet()) {
            if(!(e.getKey() instanceof String key))throw new IllegalArgumentException("JSON key");result.put(key,canonical(e.getValue()));}return result;}
        if(value instanceof List<?> list)return list.stream().map(ChatSkillSnapshotJson::canonical).toList();
        return value;
    }
    public static String write(Object value) {return JsonUtil.toJson(canonical(value));}
    public static String sha(Object value) {return ChatSkillSourceAccessService.sha(write(value).getBytes(StandardCharsets.UTF_8));}
    @SuppressWarnings("unchecked") static Map<String,Object> object(String json) {return JsonUtil.getMapper().readValue(json,Map.class);}
}
