/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
@SuppressWarnings("unused")
public class BeanConfiguration {
//    @Bean
//    public ServletRegistrationBean<MetricStreamServlet> exampleServletBean(MetricStreamServlet metricStreamServlet) {
//        ServletRegistrationBean<MetricStreamServlet> bean = new ServletRegistrationBean<>(
//                metricStreamServlet, "/receive-cloudwatch-metrics");
//        bean.setLoadOnStartup(1);
//        return bean;
//    }
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
