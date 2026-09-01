package cn.jia.chat.serialization;

/** Chat wire identifiers are decimal strings; timestamps and non-ID numbers are unaffected. */
public final class ExactWireIds {
    private ExactWireIds() {
    }

    public static String decimal(Object identifier) {
        if (identifier == null) {
            return null;
        }
        return String.valueOf(identifier);
    }
}
