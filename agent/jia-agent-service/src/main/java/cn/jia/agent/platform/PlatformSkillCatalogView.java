package cn.jia.agent.platform;

/** Public metadata for an approved platform skill; never carries package or installation authority. */
public record PlatformSkillCatalogView(
        String key,
        String version,
        String packageSha256,
        String protocol) { }
