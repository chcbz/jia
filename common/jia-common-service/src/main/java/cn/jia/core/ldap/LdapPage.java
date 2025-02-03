package cn.jia.core.ldap;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.util.ArrayList;

@EqualsAndHashCode(callSuper = true)
@Data
public class LdapPage<E> extends ArrayList<E> {
    @Serial
    private static final long serialVersionUID = 1L;

    private int pageSize;
    private long total;
    private String nextTag;
    private boolean more;
}
