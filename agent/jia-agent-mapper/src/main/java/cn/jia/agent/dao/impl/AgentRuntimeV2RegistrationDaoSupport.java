package cn.jia.agent.dao.impl;

import cn.jia.core.tenant.TenantId;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;

final class AgentRuntimeV2RegistrationDaoSupport {
    private AgentRuntimeV2RegistrationDaoSupport() {}
    static void transaction(boolean write){if(!TransactionSynchronizationManager.isActualTransactionActive()||(write&&TransactionSynchronizationManager.isCurrentTransactionReadOnly()))throw new IllegalStateException(write?"active writable transaction required":"active transaction required");}
    static void id(String v,String n){if(v==null||!v.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))throw new IllegalArgumentException(n+" is not UUIDv4");}
    static void exact(String v,int max,String n){
        if(v==null||v.isEmpty()||!v.equals(v.strip())||v.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException(n+" is not exact");
        for(int index=0;index<v.length();index++){char current=v.charAt(index);if(Character.isHighSurrogate(current)){if(index+1>=v.length()||!Character.isLowSurrogate(v.charAt(++index)))throw new IllegalArgumentException(n+" has an incomplete Unicode character");}else if(Character.isLowSurrogate(current))throw new IllegalArgumentException(n+" has an incomplete Unicode character");}
        if(v.getBytes(StandardCharsets.UTF_8).length>max)throw new IllegalArgumentException(n+" exceeds its UTF-8 byte limit");
    }
    static void tenant(String v){TenantId.of(v);}
    static void hash(byte[] v,String n){if(v==null||v.length!=32)throw new IllegalArgumentException(n+" must be 32 bytes");}
    static void nonnegative(Long v,String n){if(v==null||v<0)throw new IllegalArgumentException(n+" must be non-negative");}
    static void positive(Long v,String n){if(v==null||v<=0)throw new IllegalArgumentException(n+" must be positive");}
}
