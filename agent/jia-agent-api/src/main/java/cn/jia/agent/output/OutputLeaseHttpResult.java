package cn.jia.agent.output;

/** Exact HTTP status/body pair retained by lease mutation receipts. */
public record OutputLeaseHttpResult(int status, String responseJson) {
}
