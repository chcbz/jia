package cn.jia.agent.service;

/** Stable R00 unavailability reasons; neither reason authorizes provisioning. */
public final class HostingRentAdmissionException extends RuntimeException {
    private final Reason reason;

    public HostingRentAdmissionException(Reason reason) {
        super(message(reason));
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public String code() {
        return reason.name();
    }

    private static String message(Reason reason) {
        return switch (reason) {
            case HOSTING_RENT_NOT_CONFIGURED -> "Hosting rent is not configured";
            case HOSTING_RENT_NOT_READY -> "Hosting rent is not ready";
        };
    }

    public enum Reason {
        HOSTING_RENT_NOT_CONFIGURED,
        HOSTING_RENT_NOT_READY
    }
}
