package cn.jia.economy.hosting;

/** Verified public inventory supplied by the hosting boundary; economy performs no Runtime I/O. */
public record HostingRentInitialSkills(long count, String inventorySha256, String hostId, String snapshotJson) { }
