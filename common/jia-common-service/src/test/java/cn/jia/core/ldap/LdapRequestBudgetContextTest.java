package cn.jia.core.ldap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LdapRequestBudgetContextTest {
    @Test
    void allowsOnlyBudgetsThatCoverTheLongestLdapOperation() {
        assertDoesNotThrow(() -> {
            try (LdapRequestBudgetContext.Scope ignored = LdapRequestBudgetContext.open(2500, 100, 2250)) {
                LdapRequestBudgetContext.requireBudgetBeforeNewOperation();
            }
        });
        assertThrows(IllegalArgumentException.class,
                () -> LdapRequestBudgetContext.open(2500, 100, 2401));
    }
}
