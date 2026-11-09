package cn.jia.task.scheduling;

/** Internal business reference. Empty targetNode means cluster work; otherwise node-affine. */
public record ScheduleKey(String clientId, String handler, String businessKey, String targetNode) { }
