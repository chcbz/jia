package cn.jia.chat.handler;

import cn.jia.agent.service.NativeProviderCredentialBindingLookup;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Exact registration sibling; it observes a configured executor and grants no cost authority. */
final class NativeProviderCredentialBindingDeclaration {
    static final String PROVIDER_LANE="CONTROLLED_IMAGE_HTTP_V1";
    private static final Set<String> DISABLED_KEYS=Set.of("schemaVersion","enabled");
    private static final Set<String> ENABLED_KEYS=Set.of("schemaVersion","enabled","providerLane",
            "bindingId","bindingEpoch","modelId","maxInputItems",
            "maxOutboundRequestAttempts","precallFenceVersion");

    private final NativeProviderCredentialBindingLookup.Snapshot snapshot;
    private NativeProviderCredentialBindingDeclaration(
            NativeProviderCredentialBindingLookup.Snapshot snapshot) { this.snapshot=snapshot; }

    static NativeProviderCredentialBindingDeclaration parse(Object raw) {
        if (raw==null) return state(NativeProviderCredentialBindingLookup.State.UNDECLARED);
        try {
            if (!(raw instanceof Map<?,?> value) || !value.keySet().stream().allMatch(String.class::isInstance)) throw invalid();
            @SuppressWarnings("unchecked") Map<String,Object> root=(Map<String,Object>)value;
            exactInteger(root.get("schemaVersion"),1); boolean enabled=exactBoolean(root.get("enabled"));
            if (!enabled) {
                if (!root.keySet().equals(DISABLED_KEYS)) throw invalid();
                return state(NativeProviderCredentialBindingLookup.State.DISABLED);
            }
            if (!root.keySet().equals(ENABLED_KEYS)) throw invalid();
            exactText(root.get("providerLane"),PROVIDER_LANE);
            String bindingId=exactId(root.get("bindingId"),100);
            long bindingEpoch=canonicalPositiveLong(root.get("bindingEpoch"));
            String modelId=exactId(root.get("modelId"),100);
            exactInteger(root.get("maxInputItems"),16);
            exactInteger(root.get("maxOutboundRequestAttempts"),1);
            exactInteger(root.get("precallFenceVersion"),1);
            return new NativeProviderCredentialBindingDeclaration(
                    new NativeProviderCredentialBindingLookup.Snapshot(
                            NativeProviderCredentialBindingLookup.State.READY,1,PROVIDER_LANE,
                            bindingId,bindingEpoch,modelId,16,1,1));
        } catch (RuntimeException invalid) {
            return state(NativeProviderCredentialBindingLookup.State.UNSUPPORTED);
        }
    }
    NativeProviderCredentialBindingLookup.Snapshot snapshot(){return snapshot;}
    Map<String,Object> normalizedForReceipt() {
        Map<String,Object> value=new LinkedHashMap<>();value.put("state",snapshot.state().name());
        if (snapshot.state()==NativeProviderCredentialBindingLookup.State.READY) {
            value.put("schemaVersion",1);value.put("providerLane",snapshot.providerLane());
            value.put("bindingId",snapshot.bindingId());
            value.put("bindingEpoch",Long.toString(snapshot.bindingEpoch()));
            value.put("modelId",snapshot.modelId());value.put("maxInputItems",16);
            value.put("maxOutboundRequestAttempts",1);value.put("precallFenceVersion",1);
        }
        return Map.copyOf(value);
    }
    private static NativeProviderCredentialBindingDeclaration state(
            NativeProviderCredentialBindingLookup.State state) {
        return new NativeProviderCredentialBindingDeclaration(
                new NativeProviderCredentialBindingLookup.Snapshot(state,null,null,null,null,null,null,null,null));
    }
    private static boolean exactBoolean(Object raw){if (!(raw instanceof Boolean value))throw invalid();return value;}
    private static void exactInteger(Object raw,int expected) {
        if (!(raw instanceof Byte||raw instanceof Short||raw instanceof Integer||raw instanceof Long)
                || ((Number)raw).longValue()!=expected) throw invalid();
    }
    private static void exactText(Object raw,String expected){if (!(raw instanceof String value)||!expected.equals(value))throw invalid();}
    private static String exactId(Object raw,int max) {
        if (!(raw instanceof String value) || value.isBlank() || !value.equals(value.strip())
                || value.codePointCount(0,value.length())>max
                || value.chars().anyMatch(Character::isISOControl)) throw invalid();
        return value;
    }
    private static long canonicalPositiveLong(Object raw) {
        if (!(raw instanceof String value)||!value.matches("[1-9][0-9]*"))throw invalid();
        try {long parsed=Long.parseLong(value);if(parsed<1||!Long.toString(parsed).equals(value))throw invalid();return parsed;}
        catch(NumberFormatException invalid){throw invalid();}
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid nativeProviderCredentialBinding declaration");}
}
