package cn.jia.economy.service;

/** The only supported balance-mutating entry point for the economy domain. */
public interface EconomyPostingService {
    EconomyPostingResult post(EconomyPostingCommand command);
}
