package cn.jia.agent.skill;
import cn.jia.agent.mapper.skill.SkillManagementMapper;

import cn.jia.agent.dao.AgentCommandRecoveryDao;
import cn.jia.agent.entity.*;
import cn.jia.agent.mapper.AgentSkillInstallRecoveryMapper;
import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import cn.jia.agent.mapper.skill.SkillInstallationMapper;
import cn.jia.agent.mapper.skill.SkillCatalogMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.Set;

/** Native skill-order ACKs authenticate the original WS command, not a Rabbit publication.
 * ACK only advances delivery state. Package/result verification and settlement remain separate. */
@Service
public final class SkillInstallAckService {
    private static final Set<String> TERMINAL = Set.of("SUCCEEDED", "FAILED", "REJECTED");
    private final SkillInstallationMapper app;
    private final SkillCatalogMapper market;
    private final AgentCommandRecoveryDao commands;
    private final AgentSkillInstallRecoveryMapper recovery;
    private final AgentRuntimeAuthenticationService authentication;
    private final SkillMarketplaceService skills;
    private final TransactionTemplate tx;

    public SkillInstallAckService(SkillInstallationMapper app, SkillCatalogMapper market,
            AgentCommandRecoveryDao commands, AgentSkillInstallRecoveryMapper recovery,
            AgentRuntimeAuthenticationService authentication,  SkillMarketplaceService skills,
            PlatformTransactionManager manager) {
        this.app=app; this.market=market; this.commands=commands; this.recovery=recovery;
        this.authentication=authentication;  this.skills=skills; tx=new TransactionTemplate(manager);
    }

    /** Null means no skill installation exists for this command; malformed skill ACKs never fall back. */
    public AgentCommandAckResult acknowledgeIfSkill(AgentRuntimeAuthenticationService.Proof proof,
            AgentCommandAck ack, long now) {
        if(app.byCommand(ack.tenantId(),ack.clientId(),ack.commandId())!=null
                ||managed!=null&&managed.findCommand(ack.tenantId(),ack.clientId(),ack.commandId())!=null)
            throw new SkillMarketplaceException(410,"ENDPOINT_RETIRED");
        return null;
    }
    private cn.jia.agent.mapper.skill.SkillManagementMapper managed;
    @org.springframework.beans.factory.annotation.Autowired
    public void setManagement(cn.jia.agent.mapper.skill.SkillManagementMapper mapper){managed=mapper;}
    private static void check(boolean valid) {
        if(!valid) throw new AgentCommandAckRejectedException("SKILL_NATIVE_ACK_REJECTED");
    }
}
