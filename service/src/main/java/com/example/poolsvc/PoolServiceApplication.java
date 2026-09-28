package com.example.poolsvc;

import com.example.poolsvc.config.DbProperties;
import com.example.poolsvc.config.EtcdProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({DbProperties.class, EtcdProperties.class})
public class PoolServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PoolServiceApplication.class, args);
    }
}
