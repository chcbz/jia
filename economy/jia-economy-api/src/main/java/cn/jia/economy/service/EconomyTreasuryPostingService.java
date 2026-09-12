package cn.jia.economy.service;

/**
 * Internal treasury capability for controlled {@code ISSUE_SILVER} postings.
 *
 * <p>The generic {@link EconomyPostingService} deliberately rejects issuance. A later, separately
 * authorized non-production issuance flow may depend on this capability, but no HTTP surface is
 * provided by the economy foundation.</p>
 */
public interface EconomyTreasuryPostingService {
    EconomyPostingResult issue(EconomyPostingCommand command);
}
