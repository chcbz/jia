package cn.jia.user.entity;

/** Minimal authenticated-own-user projection for browser identity hydration. */
public record CurrentUserIdentityDTO(
        Long id,
        String username,
        String nickname,
        String avatar,
        String openid,
        String jiacn,
        String tenantId,
        String clientId) {
}
