package in.opennest.common.internal.security;

import in.opennest.common.internal.web.ProblemResponses;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.savedrequest.NullRequestCache;

@Configuration(proxyBeanMethods = false)
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "opennest.identity.google-enabled", havingValue = "false", matchIfMissing = true)
class BaselineSecurityConfiguration {

    @Bean
    AuthenticationManager baselineAuthenticationManager() {
        return authentication -> {
            throw new BadCredentialsException("Authentication is not configured");
        };
    }

    @Bean
    SecurityFilterChain baselineSecurity(HttpSecurity http, ProblemResponses responses) throws Exception {
        return http.authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .anyRequest().denyAll())
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, failure) -> responses.write(response, 401))
                        .accessDeniedHandler((request, response, failure) -> responses.write(response, 403)))
                .build();
    }

    @Bean
    WebSecurityCustomizer rejectedRequests(ProblemResponses responses) {
        return web -> web.requestRejectedHandler((request, response, failure) -> responses.write(response, 400));
    }
}
