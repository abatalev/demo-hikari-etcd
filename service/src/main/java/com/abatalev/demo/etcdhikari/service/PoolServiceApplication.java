package com.abatalev.demo.etcdhikari.service;

import com.abatalev.demo.etcdhikari.service.config.DbProperties;
import com.abatalev.demo.etcdhikari.service.config.EtcdProperties;
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
