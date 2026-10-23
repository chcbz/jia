package cn.jia.chat.service;

import cn.jia.agent.service.InstalledSkillDescriptorLookup;
import cn.jia.agent.service.SkillCapabilityLookup;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Only immutable verified package/manifest preparation. No grant, reservation, effect or authorization cache. */
@Service
public class ChatSkillPackagePreparationService {
    private final InstalledSkillDescriptorLookup installed;
    public ChatSkillPackagePreparationService(InstalledSkillDescriptorLookup installed) { this.installed=installed; }
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public void prepare(SkillCapabilityLookup.Scope scope) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new InstalledSkillDescriptorLookup.SourceUnavailable();
        if (installed.read(scope)==null) throw new InstalledSkillDescriptorLookup.SourceUnavailable();
    }
}
