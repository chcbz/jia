package cn.jia.core.context;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class EsContext {
    private String tenantId;

    private String clientId;

    private String appcn;

    private String jiacn;

    private String username;

    /** Authenticated business area; null means a legacy/non-business identity. */
    private String tenantId;

    /** Server-owned product boundary, never inferred from clientId. */
    private String dataDomainId;

}
