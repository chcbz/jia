package cn.jia.chat.handler;

import java.util.*;
import java.util.function.BooleanSupplier;

/** One complete readiness snapshot controls BOTH hello and the strict registration entry. */
public final class AgentRuntimeV2Readiness {
    public enum Dependency { ACTIVATION, BASELINE_SCHEMA, DELTA_SCHEMA, KEYS, RESOLVER, TRANSACTION_SERVICE, STORE, ROUTING, POST_COMMIT_NETWORK, RECEIVER_DISPATCH, EXECUTION_HOLDER_TRANSFER, EFFECT_ISOLATION, NODE_INTEROPERABILITY, CONSUMER_TOPOLOGY }
    private final Map<Dependency,BooleanSupplier> evidence;
    public AgentRuntimeV2Readiness(Map<Dependency,BooleanSupplier> evidence){this.evidence=Map.copyOf(evidence);}
    public boolean ready(){for(Dependency dependency:Dependency.values()){BooleanSupplier check=evidence.get(dependency);try{if(check==null||!check.getAsBoolean())return false;}catch(RuntimeException unavailable){return false;}}return true;}
    public int[] supportedProtocolVersions(){return ready()?new int[]{1,2}:new int[]{1};}
}
