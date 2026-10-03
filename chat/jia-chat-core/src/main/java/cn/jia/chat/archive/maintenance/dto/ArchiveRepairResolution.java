package cn.jia.chat.archive.maintenance.dto;

/** Structured, one-time authorization that a manager completed an actual repair. */
public record ArchiveRepairResolution(String failureId, String resolutionCode) { }
