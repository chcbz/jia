package cn.jia.chat.config;

/** Runtime availability contract, independent of deployment-time schema maintenance. */
public interface ChatTypedDeliberationReadiness {
    enum Readiness { INITIALIZING, READY, DISABLED, UNAVAILABLE, FAILED }

    Readiness readiness();

    default boolean ready() {
        return readiness() == Readiness.READY;
    }
}
