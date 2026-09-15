package cn.jia.agent.config;

import cn.jia.agent.output.OutputMalwareScanner;
import cn.jia.agent.output.OutputObjectStorage;
import cn.jia.agent.output.OutputRunAuthorizationService;
import cn.jia.agent.output.OutputUploadService;
import cn.jia.agent.output.OutputDeliveryService;
import cn.jia.agent.output.OutputVersionProvider;
import cn.jia.agent.output.dao.OutputUploadDao;
import cn.jia.agent.output.dao.TaskDeliveryDao;
import cn.jia.agent.output.dao.impl.OutputUploadDaoImpl;
import cn.jia.agent.output.service.ClamAvOutputMalwareScanner;
import cn.jia.agent.output.service.OutputUploadServiceImpl;
import cn.jia.agent.output.service.OutputDeliveryServiceImpl;
import cn.jia.agent.output.service.OutputUploadRecoveryScheduler;
import cn.jia.agent.output.service.S3OutputObjectStorage;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

@Configuration(proxyBeanMethods=false)
@Conditional(OutputDeliveryEnabledCondition.class)
@EnableConfigurationProperties(OutputDeliveryProperties.class)
public class OutputUploadConfiguration {
    @Bean public OutputUploadDao outputUploadDao(JdbcTemplate jdbc){return new OutputUploadDaoImpl(jdbc);}
    @Bean(destroyMethod="close") public OutputObjectStorage outputObjectStorage(OutputDeliveryProperties p){return new S3OutputObjectStorage(p.storageEndpoint(),p.storageAccessKey(),p.storageSecretKey(),p.storageBucket());}
    @Bean public OutputMalwareScanner outputMalwareScanner(OutputDeliveryProperties p){return new ClamAvOutputMalwareScanner(p.scannerHost(),p.scannerPort());}
    @Bean public OutputUploadService outputUploadService(OutputRunAuthorizationService auth,OutputUploadDao dao,OutputObjectStorage storage,OutputMalwareScanner scanner,PlatformTransactionManager tx,OutputDeliveryProperties p){return new OutputUploadServiceImpl(auth,dao,storage,scanner,tx,p);}
    @Bean public OutputDeliveryService outputDeliveryService(OutputRunAuthorizationService auth,
            OutputUploadDao dao, OutputObjectStorage storage, PlatformTransactionManager tx,
            OutputDeliveryProperties p, List<OutputVersionProvider> providers,
            TaskDeliveryDao deliveryDao){
        return new OutputDeliveryServiceImpl(auth,dao,storage,tx,p,providers,deliveryDao);
    }
    @Bean public OutputUploadRecoveryScheduler outputUploadRecoveryScheduler(OutputUploadService service){return new OutputUploadRecoveryScheduler(service);}
}
