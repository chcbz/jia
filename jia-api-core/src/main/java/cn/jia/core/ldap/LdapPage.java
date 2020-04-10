package cn.jia.core.ldap;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;

@EqualsAndHashCode(callSuper = true)
@Data
public class LdapPage<E> extends ArrayList<E> {

    private int pageSize;
    private long total;
    private String nextTag;
    private boolean more;
}
