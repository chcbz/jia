package cn.jia.economy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;

/** Configuration-only grant campaigns; no registration-date eligibility or client-supplied amounts. */
@ConfigurationProperties(prefix = "economy.campaigns")
public record EconomyCampaignProperties(List<Campaign> grants) {
    public enum Event { HALL_ENTERED }

    public EconomyCampaignProperties {
        grants = grants == null ? List.of() : List.copyOf(grants);
        var ids = new HashSet<String>();
        for (Campaign campaign : grants) {
            if (!ids.add(campaign.id())) throw new IllegalArgumentException("Duplicate economy campaign id");
        }
    }

    public static EconomyCampaignProperties disabled() { return new EconomyCampaignProperties(List.of()); }

    public record Campaign(String id, Event event, boolean enabled, long amountWen,
            Long startsAt, Long endsAt) {
        public Campaign {
            if (id == null || id.isBlank() || !id.equals(id.strip())
                    || id.getBytes(StandardCharsets.UTF_8).length > 100
                    || id.codePoints().anyMatch(Character::isISOControl)
                    || event == null || amountWen <= 0
                    || startsAt != null && startsAt < 0 || endsAt != null && endsAt < 0
                    || startsAt != null && endsAt != null && endsAt <= startsAt) {
                throw new IllegalArgumentException("Invalid economy grant campaign");
            }
        }
        public boolean eligible(Event observed, long now) {
            return enabled && event == observed && (startsAt == null || now >= startsAt)
                    && (endsAt == null || now < endsAt);
        }
    }
}
