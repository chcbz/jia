package cn.jia.chat.service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** Domain tools share native/current-turn admission, without exposing request-supplied identities. */
public interface ConversationToolProvider {
    record Context(PrivateConversationToolAccess.Subject subject, String runtimeInstallationId, String runtimeHostId) { }
    interface Boundary { <T> T execute(Supplier<T> operation); }
    record Skill(String skillKey,String skillVersion,String skillInstallationId,String packageSha256) { }
    record Catalog(List<Map<String,Object>> tools,List<Skill> skills) {
        public static Catalog empty() { return new Catalog(List.of(),List.of()); }
    }
    Set<String> names();
    List<Map<String,Object>> catalog(Context context,Boundary boundary);
    Object call(Context context,Boundary boundary,String name,Map<String,Object> arguments);
}
