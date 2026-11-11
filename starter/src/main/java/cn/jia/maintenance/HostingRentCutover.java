package cn.jia.maintenance;

import cn.jia.agent.config.*;
import cn.jia.agent.dao.impl.*;
import cn.jia.agent.hosting.*;
import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import cn.jia.agent.service.impl.AgentIdentityServiceImpl;
import cn.jia.agent.service.impl.AgentRuntimeV1ServiceImpl;
import cn.jia.core.config.ProcessTenantConfiguration;
import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.economy.config.*;
import cn.jia.economy.service.impl.*;
import cn.jia.task.mapper.ScheduledWorkMapper;
import cn.jia.task.scheduling.ScheduledWorkStore;
import cn.jia.user.dao.impl.*;
import cn.jia.user.security.AccountSecurityServiceImpl;
import cn.jia.user.service.impl.UserBusinessMembership;
import cn.jia.user.service.impl.UserServiceImpl;
import cn.jia.point.dao.impl.PointAccountDaoImpl;
import cn.jia.point.service.impl.PointAccountServiceImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.ssl.SslAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** One-shot, explicit-source maintenance entry. No application/component scan, worker, DDL or HTTP server. */
public final class HostingRentCutover {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    public record Subject(String actorId, String ownerJiacn, String agentId, String installationId) { }
    public record Batch(long cutoverAt, List<Subject> subjects) { }

    static List<HostingRentTransitionApplication.Request> requests(byte[] input, FixedTenantAdmissionPolicy scope) {
        Batch batch = JSON.readValue(input, Batch.class);
        if (batch == null || batch.cutoverAt() <= 0 || batch.cutoverAt() > System.currentTimeMillis()
                || batch.subjects() == null || batch.subjects().isEmpty()) throw new IllegalArgumentException("Fixed cutover and subjects required");
        var seen = new HashSet<String>();
        var result = new ArrayList<HostingRentTransitionApplication.Request>();
        for (Subject subject : batch.subjects()) {
            Objects.requireNonNull(subject);
            var actor = new HostingRentHttp.Actor(subject.actorId(), scope.businessTenantId(), scope.clientId(), subject.ownerJiacn());
            HostingRentHttp.exact(subject.agentId(), 100); HostingRentHttp.exact(subject.installationId(), 100);
            if (!seen.add(subject.agentId())) throw new IllegalArgumentException("Duplicate cutover Agent");
            result.add(new HostingRentTransitionApplication.Request(actor, subject.agentId(), subject.installationId(), batch.cutoverAt()));
        }
        return List.copyOf(result);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !(args[0].equals("--preview") || args[0].equals("--apply")))
            throw new IllegalArgumentException("Usage: --preview|--apply INPUT.json NEW-RECEIPT.jsonl");
        byte[] input = Files.readAllBytes(Path.of(args[1]));
        // CREATE_NEW prevents a retry from rewriting prior evidence. No secrets in the input or receipt.
        try (var output = Files.newBufferedWriter(Path.of(args[2]), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
            var app = new SpringApplication(Context.class);
            app.setWebApplicationType(WebApplicationType.NONE);
            app.setAdditionalProfiles("schema-maintenance", "hosting-rent-cutover");
            try (var context = app.run()) {
                var scope = context.getBean(FixedTenantAdmissionPolicy.class);
                var commands = requests(input, scope);
                var application = context.getBean(HostingRentTransitionApplication.class);
                var previews = commands.stream().map(application::preview).toList();
                output.write(JSON.writeValueAsString(Map.of("mode", args[0], "inputSha256",
                        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input)),
                        "tenantId", scope.businessTenantId(), "clientId", scope.clientId(), "previews", previews)) + "\n");
                output.flush();
                if (args[0].equals("--apply")) {
                    for (var command : commands) {
                        String lease = application.register(command);
                        output.write(JSON.writeValueAsString(Map.of("agentId", command.agentId(), "leaseId", lease,
                                "status", "REGISTERED_OR_IDENTICAL_REPLAY")) + "\n");
                        output.flush();
                    }
                }
                output.write(JSON.writeValueAsString(Map.of("status", "COMPLETED", "subjects", commands.size())) + "\n");
            }
        }
    }

    /** Only this profile can register the explicit maintenance graph in the normal classpath. */
    @Configuration(proxyBeanMethods = false)
    @Profile("hosting-rent-cutover")
    @EnableTransactionManagement
    @ImportAutoConfiguration({DataSourceAutoConfiguration.class, SslAutoConfiguration.class})
    @EnableConfigurationProperties({AgentHostingRentProperties.class, ManagedHostingAdapterProperties.class,
            HostingControlRemoteProperties.class, AgentTaskEventsProperties.class, EconomyPreviewProperties.class})
    @Import({ProcessTenantConfiguration.class, AccountSecurityServiceImpl.class, UserServiceImpl.class,
            UserBusinessMembership.class, PointAccountServiceImpl.class, UserInfoDaoImpl.class,
            UserBusinessMembershipDaoImpl.class, UserRoleRelDaoImpl.class, UserOrgRelDaoImpl.class,
            UserGroupRelDaoImpl.class, PointAccountDaoImpl.class, AgentIdentityRegistryDaoImpl.class,
            AgentIdentityAliasDaoImpl.class, AgentPersonaBindingDaoImpl.class, AgentRuntimeDaoImpl.class,
            AgentRuntimeV1InstallationDaoImpl.class, AgentIdentityServiceImpl.class, AgentRuntimeV1ServiceImpl.class,
            AgentRuntimeAuthenticationService.class, HostingRentOwnerResolver.class, ManagedHostingCredentials.class,
            HostingControlChannel.class, UnixManagedHostingProvisioner.class, HostingRentTransitionApplication.class,
            HostingRentRenewalServiceImpl.class, EconomyPostingServiceImpl.class})
    public static class Context {
        // Register exact mapper interfaces, not a wildcard mapper/component scan.
        private static final List<Class<?>> MAPPERS = List.of(
                cn.jia.user.mapper.InfoMapper.class, cn.jia.user.mapper.UserBusinessMembershipMapper.class,
                cn.jia.user.mapper.RoleRelMapper.class, cn.jia.user.mapper.OrgRelMapper.class, cn.jia.user.mapper.GroupRelMapper.class,
                cn.jia.point.mapper.PointAccountMapper.class, cn.jia.agent.mapper.AgentIdentityRegistryMapper.class,
                cn.jia.agent.mapper.AgentIdentityAliasMapper.class, cn.jia.agent.mapper.AgentPersonaBindingMapper.class,
                cn.jia.agent.mapper.AgentRuntimeMapper.class, cn.jia.agent.mapper.AgentRuntimeV1InstallationMapper.class,
                cn.jia.economy.mapper.EconomyLedgerMapper.class, cn.jia.economy.mapper.EconomyHostingRentMapper.class,
                cn.jia.economy.mapper.EconomyHostingRenewalMapper.class, ScheduledWorkMapper.class);
        @Bean static BeanDefinitionRegistryPostProcessor cutoverMappers() {
            return registry -> MAPPERS.forEach(mapper -> {
                var definition = new RootBeanDefinition(MapperFactoryBean.class);
                definition.getConstructorArgumentValues().addGenericArgumentValue(mapper);
                definition.getPropertyValues().add("sqlSessionTemplate", new RuntimeBeanReference("cutoverSqlSession"));
                registry.registerBeanDefinition(mapper.getName(), definition);
            });
        }
        @Bean SqlSessionFactory cutoverSqlFactory(DataSource source) throws Exception {
            var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
            var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(source); factory.setConfiguration(config);
            return factory.getObject();
        }
        @Bean SqlSessionTemplate cutoverSqlSession(SqlSessionFactory factory) { return new SqlSessionTemplate(factory); }
        @Bean PlatformTransactionManager cutoverTransactions(DataSource source) { return new DataSourceTransactionManager(source); }
        @Bean EconomyPreviewGate cutoverEconomyGate(EconomyPreviewProperties properties) { return new EconomyPreviewGate(properties); }
        @Bean AgentTaskEventsGate cutoverTaskEventsGate(AgentTaskEventsProperties properties) { return new AgentTaskEventsGate(properties); }
        @Bean ScheduledWorkStore cutoverSchedules(ScheduledWorkMapper mapper, FixedTenantAdmissionPolicy scope,
                PlatformTransactionManager transactions) { return new ScheduledWorkStore(mapper, scope, transactions); }
        @Bean HostingRentLeaseWakeup cutoverFirstDue(ScheduledWorkStore schedules, FixedTenantAdmissionPolicy scope) {
            return new HostingRentLeaseWakeup(schedules, scope);
        }
    }
}
