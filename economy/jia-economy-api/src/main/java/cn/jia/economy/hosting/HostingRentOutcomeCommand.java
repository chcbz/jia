package cn.jia.economy.hosting;

import cn.jia.economy.service.EconomyPrincipal;
import cn.jia.economy.service.EconomyScope;

public record HostingRentOutcomeCommand(
        EconomyScope scope,
        EconomyPrincipal principal,
        String intentId,
        long expectedIntentVersion,
        String evidenceRef) {
}
