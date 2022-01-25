/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws;

import ai.asserts.aws.controller.MetricStreamReceiverServlet;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@SuppressWarnings("unused")
public class BeanConfiguration {
    @Bean
    public ServletRegistrationBean<MetricStreamReceiverServlet> exampleServletBean(MetricStreamReceiverServlet metricStreamReceiverServlet) {
        ServletRegistrationBean<MetricStreamReceiverServlet> bean = new ServletRegistrationBean<>(
                metricStreamReceiverServlet, "/receive-cloudwatch-metrics");
        bean.setLoadOnStartup(1);
        return bean;
    }
}
