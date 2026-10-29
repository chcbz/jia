package cn.jia.point.entity;

/** Read-only balance projection; identity and scope are supplied by the point service. */
public record PointAccountBalance(String jiacn, int balance) { }
