package cn.jia.agent.security;

import cn.jia.agent.entity.AgentRuntimeV2RegistrationFingerprints.HmacKeyRef;
import javax.crypto.SecretKey;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable versioned server ring. Removing keys referenced by live bindings/challenges is forbidden. */
public final class AgentRuntimeV2RegistrationKeyRing {
    private final Map<String,SecretKey> keys;
    private final String active;
    public AgentRuntimeV2RegistrationKeyRing(String active, Map<String,SecretKey> keys, Set<String> liveReferences) {
        this.keys=Map.copyOf(keys);this.active=Objects.requireNonNull(active);
        if(!keys.containsKey(active)||!keys.keySet().containsAll(liveReferences))throw new IllegalArgumentException("missing registration HMAC key");
        keys.forEach((id,key)->{if(id.isBlank()||key.getEncoded()==null||key.getEncoded().length<32)throw new IllegalArgumentException("registration HMAC key must have at least 256 bits");});
    }
    public HmacKeyRef active() { return require(active); }
    public HmacKeyRef require(String id) { SecretKey key=keys.get(id);if(key==null)throw new SecurityException("unknown registration key");return new HmacKeyRef(id,key); }
}
