package cn.jia.agent.output;

/** Exact HTTP status/body pair retained by formal-delivery idempotency receipts. */
public record TaskDeliveryHttpResult(int status, String responseJson) { }
