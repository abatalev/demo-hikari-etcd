package com.example.poolsvc.config;

import com.example.poolsvc.pool.ManagedPool;
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
     * Пул создаётся сразу на локальных дефолтах, чтобы сервис поднялся даже без etcd.
     * Дальше конфиг из etcd дотюнивает этот же пул на лету.
     */
    @Bean(destroyMethod = "close")
    ManagedPool managedPool(DbProperties properties) {
        return new ManagedPool(
                properties.toSettings(),
                properties.getInitializationFailTimeoutMs(),
                properties.isRegisterMbeans(),
                properties.isEagerFillOnResize(),
                properties.getDrainOnRecreateTimeout());
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

    /** Для интроспекции (pg_stat_activity): свой таймаут, чтобы не висеть на насыщенном пуле. */
    @Bean
    JdbcTemplate metaJdbcTemplate(DataSource dataSource) {
        JdbcTemplate template = new JdbcTemplate(dataSource);
        template.setQueryTimeout(2);
        return template;
    }

    @Bean
    PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new JdbcTransactionManager(dataSource);
    }
}
