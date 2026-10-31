package in.opennest.common.internal.web;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration(proxyBeanMethods = false)
class WebErrorConfiguration {

    @Bean
    FilterRegistrationBean<FailureBoundaryFilter> failureBoundary(ProblemResponses responses) {
        var registration = new FilterRegistrationBean<>(new FailureBoundaryFilter(responses));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
