package cn.jia.oauth.security;

import java.net.URI;

/** Operator-controlled central issuer and persistent signing key configuration. */
public class CentralSigningProperties {
    private boolean enabled;
    private String issuer;
    private String privateJwkPath;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getIssuer() { return issuer; }
    public void setIssuer(String issuer) { this.issuer = issuer; }
    public String getPrivateJwkPath() { return privateJwkPath; }
    public void setPrivateJwkPath(String privateJwkPath) { this.privateJwkPath = privateJwkPath; }

    public String validatedIssuer() {
        requireTrustedUri(issuer, "signing issuer");
        return issuer;
    }

    public static void requireTrustedUri(String raw, String name) {
        if (raw == null || raw.isBlank() || !raw.equals(raw.strip())) {
            throw new IllegalStateException("Central JWT " + name + " is missing or malformed");
        }
        try {
            URI uri = URI.create(raw);
            String host = uri.getHost();
            String scheme = uri.getScheme();
            boolean local = "127.0.0.1".equals(host) || "::1".equals(host) || "localhost".equals(host);
            if (host == null || host.isBlank() || uri.getUserInfo() != null || uri.getFragment() != null
                    || uri.getQuery() != null || !("https".equalsIgnoreCase(scheme)
                    || "http".equalsIgnoreCase(scheme) && local)) {
                throw new IllegalStateException("Central JWT " + name + " must be HTTPS or loopback HTTP");
            }
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("Central JWT " + name + " is malformed", invalid);
        }
    }
}
