package cn.jia.chat.service;

import cn.jia.core.util.JsonUtil;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Shared core snapshot/result encoding: UTF16 keys, array order, shortest portable JSON numbers. */
public final class ChatSkillSnapshotJson {
    private ChatSkillSnapshotJson() { }
    public static String write(Object value) {return cn.jia.agent.service.SkillCanonicalJson.write(value);}
    public static String sha(Object value) {return ChatSkillSourceAccessService.sha(write(value).getBytes(StandardCharsets.UTF_8));}
    @SuppressWarnings("unchecked") static Map<String,Object> object(String json) {return JsonUtil.getMapper().readValue(json,Map.class);}
}
