package com.abatalev.demo.etcdhikari.service.config;

import com.abatalev.demo.etcdhikari.service.metrics.PoolCounters;
import com.abatalev.demo.etcdhikari.service.otel.MechanismSpans;
import com.abatalev.demo.etcdhikari.service.pool.ManagedPool;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
public class PoolBeansConfiguration {

    /**
     * Пул создаётся только из конфигурации etcd: локального размера нет вовсе, поэтому при старте
     * пула нет. Первый принятый конфиг от провижера создаёт пул; нулевой целевой размер
     * (холодная группа, снятие конфигурации) закрывает его с дренажом.
     *
     * <p>{@code @Primary} обязателен: бин {@code dataSource} ниже отдаёт тот же объект, и как
     * только он создан, Spring знает его фактический тип и считает его вторым кандидатом на
     * {@code ManagedPool}. Без отметки контекст поднимался только потому, что потребители пула
     * создавались раньше бина {@code dataSource} — при любом другом порядке регистрации старт падал
     * на «found 2: managedPool,dataSource».
     */
    @Bean(destroyMethod = "close")
    @Primary
    ManagedPool managedPool(DbProperties properties, PoolCounters counters, MechanismSpans spans) {
        return new ManagedPool(
                properties,
                properties.isEagerFillOnResize(),
                properties.getDrainOnRecreateTimeout(),
                counters,
                spans);
    }

    @Bean
    DataSource dataSource(ManagedPool pool) {
        return pool;
    }

    @Bean
    @Primary
    JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new JdbcTransactionManager(dataSource);
    }
}
