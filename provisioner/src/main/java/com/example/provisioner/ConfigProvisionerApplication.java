package com.example.provisioner;

import com.example.provisioner.config.ProvisionerProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(ProvisionerProperties.class)
public class ConfigProvisionerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConfigProvisionerApplication.class, args);
    }
}