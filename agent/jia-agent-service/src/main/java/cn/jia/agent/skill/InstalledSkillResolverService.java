package cn.jia.agent.skill;

import cn.jia.agent.service.InstalledSkillResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/** Fail-closed origin dispatcher. Missing MARKET/platform adapters never imply entitlement. */
@Service
public final class InstalledSkillResolverService implements InstalledSkillResolver {
    private final ObjectProvider<InstalledSkillSourceResolver> adapters;

    public InstalledSkillResolverService(ObjectProvider<InstalledSkillSourceResolver> adapters) {
        this.adapters = Objects.requireNonNull(adapters);
    }

    @Override
    public Resolution resolve(Request request) {
        Objects.requireNonNull(request, "request");
        try {
            List<InstalledSkillSourceResolver> matches = adapters.orderedStream()
                    .filter(adapter -> adapter.origin() == request.origin()).toList();
            if (matches.size() != 1) return Resolution.unavailable();
            Resolution result = matches.getFirst().resolve(request);
            if (result == null || result.state() == State.UNAVAILABLE) return Resolution.unavailable();
            Proof proof = result.proof();
            if (proof == null || !request.key().equals(proof.key())
                    || !request.version().equals(proof.version())
                    || !request.packageDigest().equals(proof.packageDigest())) {
                return Resolution.unavailable();
            }
            return result;
        } catch (RuntimeException unavailable) {
            return Resolution.unavailable();
        }
    }
}
