package cn.jia.chat.archive.maintenance.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix="archive.maintenance")
public class ArchiveMaintenanceProperties {
    private boolean executionEnabled=false;
    private List<ManagerGrant> managerGrants=new ArrayList<>();
    public boolean isExecutionEnabled(){ return executionEnabled; }
    public void setExecutionEnabled(boolean value){ executionEnabled=value; }
    public List<ManagerGrant> getManagerGrants(){ return managerGrants; }
    public void setManagerGrants(List<ManagerGrant> value){ managerGrants=value==null?new ArrayList<>():value; }
    public static class ManagerGrant {
        private String collectionId="platform-classics"; private String tenantId="0";
        private String clientId; private String ownerJiacn; private String permissions="appoint,source.prepare,job.create,job.manage,draft.write,validate,publish";
        private long authorizationRevision=1;
        public String getCollectionId(){return collectionId;} public void setCollectionId(String v){collectionId=v;}
        public String getTenantId(){return tenantId;} public void setTenantId(String v){tenantId=v;}
        public String getClientId(){return clientId;} public void setClientId(String v){clientId=v;}
        public String getOwnerJiacn(){return ownerJiacn;} public void setOwnerJiacn(String v){ownerJiacn=v;}
        public String getPermissions(){return permissions;} public void setPermissions(String v){permissions=v;}
        public long getAuthorizationRevision(){return authorizationRevision;}
        public void setAuthorizationRevision(long v){authorizationRevision=v;}
    }
}
