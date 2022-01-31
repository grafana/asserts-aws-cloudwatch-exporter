/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws;

import ai.asserts.aws.cloudwatch.MetricStreamServlet;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@SuppressWarnings("unused")
public class BeanConfiguration {
    @Bean
    public ServletRegistrationBean<MetricStreamServlet> exampleServletBean(MetricStreamServlet metricStreamServlet) {
        ServletRegistrationBean<MetricStreamServlet> bean = new ServletRegistrationBean<>(
                metricStreamServlet, "/receive-cloudwatch-metrics");
        bean.setLoadOnStartup(1);
        return bean;
    }
}
