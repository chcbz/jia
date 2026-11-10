package cn.jia.task.scheduling;

/** Internal business reference. Empty clientId denotes tenant-wide maintenance, not a customer.
 * Only a process without a client restriction may use it. Empty targetNode means cluster work; otherwise node-affine. */
public record ScheduleKey(String clientId, String handler, String businessKey, String targetNode) { }
