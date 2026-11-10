package cn.jia.chat.memory;

/** Exact client/account pair discovered from live, owned conversation messages. */
public record MemoryOwner(String clientId, String jiacn) {}
