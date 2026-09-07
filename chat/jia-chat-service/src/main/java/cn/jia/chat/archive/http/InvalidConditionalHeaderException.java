package cn.jia.chat.archive.http;

public class InvalidConditionalHeaderException extends IllegalArgumentException {
    public InvalidConditionalHeaderException() {
        super("Malformed If-None-Match header");
    }
}
