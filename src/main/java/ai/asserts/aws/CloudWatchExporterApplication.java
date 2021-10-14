/*
 * Copyright © 2021
 * Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@Slf4j
@SpringBootApplication
@ComponentScan(basePackages = {"ai.asserts"})
@ConfigurationPropertiesScan(basePackages = {"ai.asserts"})
@EnableScheduling
public class CloudWatchExporterApplication {
    public static void main(String[] args) {
        SpringApplication springApplication = new SpringApplication(CloudWatchExporterApplication.class);
        springApplication.addListeners(new BuildInfoEventListener());
        springApplication.run(args);
    }
}
