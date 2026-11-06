package cn.jia.economy.pricing;

import java.util.Optional;

/** Immutable current manual-CNY prices. Callers retain the selected snapshot before invoking
 * a provider; missing routes remain pending, never a zero-price or default-model substitution.
 * Independent of task estimation and wallet admission.
 */
public interface ModelPriceCatalog {
    Optional<ModelTokenPrice> find(String channel, String model, String tier);
}
