package cn.jia.agent.skill;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class InstalledSkillEntitlementLookupTest {
    @Test void supportsFrozenMinimumAndExactReleaseRequirementsOnly() {
        assertTrue(InstalledSkillEntitlementLookup.matchesVersion("1.0.0",">=1.0.0"));
        assertTrue(InstalledSkillEntitlementLookup.matchesVersion("1.10.0",">=1.2.0"));
        assertFalse(InstalledSkillEntitlementLookup.matchesVersion("1.2.0",">=1.10.0"));
        assertFalse(InstalledSkillEntitlementLookup.matchesVersion("1.0.0","*"));
        assertFalse(InstalledSkillEntitlementLookup.matchesVersion("1.0.0","01.0.0"));
        assertFalse(InstalledSkillEntitlementLookup.matchesVersion("1.0.0",">=1.0.0 "));
        assertTrue(InstalledSkillEntitlementLookup.matchesVersion("1.0.0","1.0.0"));
    }
}
