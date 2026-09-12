package cn.jia;

import cn.jia.agent.output.mapper.OutputSourceBindingMapper;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.mapper.ClassPathMapperScanner;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutputMapperScanTest {
    @Test
    void aggregateApplicationMapperScanRegistersOutputDeliveryMappers() {
        MapperScan annotation = JiaApplication.class.getAnnotation(MapperScan.class);
        DefaultListableBeanFactory registry = new DefaultListableBeanFactory();
        ClassPathMapperScanner scanner = new ClassPathMapperScanner(registry);
        scanner.setPrintWarnLogIfNotFoundMappers(false);
        scanner.registerFilters();

        scanner.scan(annotation.value());

        assertTrue(Arrays.asList(annotation.value()).contains("cn.jia.agent.output.mapper"));
        assertTrue(registry.containsBeanDefinition("outputSourceBindingMapper"));
        Object mapperInterface = registry.getBeanDefinition("outputSourceBindingMapper")
                .getPropertyValues().get("mapperInterface");
        assertEquals(OutputSourceBindingMapper.class, mapperInterface);
    }
}
