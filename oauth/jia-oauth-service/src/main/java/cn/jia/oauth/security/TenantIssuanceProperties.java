package cn.jia.oauth.security;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Getter
@Setter
public final class TenantIssuanceProperties {
    public enum Mode { PLATFORM, FIXED_TENANT }

    private Mode admissionMode = Mode.PLATFORM;
    private String fixedTenantId;
    /** Exact external RegisteredClient.clientId to canonical fixed tenant ID. */
    private Map<String, String> fixedClientTenants = new LinkedHashMap<>();
    private boolean requireBoundIdentity;
    private List<String> allowedTenants = new ArrayList<>();
}
