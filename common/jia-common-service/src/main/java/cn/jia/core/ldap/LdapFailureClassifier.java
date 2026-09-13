package cn.jia.core.ldap;

import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;

/** Classifies only safe LDAP transport failures without exposing provider details. */
public final class LdapFailureClassifier {
    private LdapFailureClassifier() {
    }

    public static Failure classify(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SocketTimeoutException || current instanceof InterruptedIOException) {
                return Failure.TIMEOUT;
            }
            String className = current.getClass().getName();
            if ("javax.naming.CommunicationException".equals(className)
                    || "javax.naming.ServiceUnavailableException".equals(className)
                    || "org.springframework.ldap.CommunicationException".equals(className)) {
                return Failure.UNAVAILABLE;
            }
        }
        return Failure.OTHER;
    }

    public enum Failure {
        TIMEOUT,
        UNAVAILABLE,
        OTHER
    }
}
