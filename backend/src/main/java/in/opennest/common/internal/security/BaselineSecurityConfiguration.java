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

    @Bean({"baselineCors", "applicationCors"})
    org.springframework.web.cors.CorsConfigurationSource baselineCors(org.springframework.core.env.Environment environment) {
        String mode = environment.getProperty("opennest.deployment.data-mode", "PRIMARY");
        String origin = environment.getProperty("opennest.identity." + (mode.equals("SANDBOX") ? "sandbox" : "primary") + ".frontend-origin", "http://localhost:3000");
        var cors = new org.springframework.web.cors.CorsConfiguration();
        try {
            var uri = java.net.URI.create(origin);
            boolean https = "https".equals(uri.getScheme());
            boolean local = "http".equals(uri.getScheme()) && java.util.Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost())
                    && !"PRODUCTION".equals(environment.getProperty("opennest.deployment.environment"));
            if ((https || local) && uri.getHost() != null && uri.getUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && (uri.getRawPath() == null || uri.getRawPath().isEmpty())) cors.setAllowedOrigins(java.util.List.of(origin));
        } catch (IllegalArgumentException ignored) { cors.setAllowedOrigins(java.util.List.of()); }
        cors.setAllowedMethods(java.util.List.of("GET", "OPTIONS"));
        cors.setAllowCredentials(true);
        var source = new org.springframework.web.cors.UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/v1/listings/**", cors);
        return source;
    }

    @Bean
    SecurityFilterChain baselineSecurity(HttpSecurity http, ProblemResponses responses,
            org.springframework.web.cors.CorsConfigurationSource baselineCors) throws Exception {
        var filter = new org.springframework.web.filter.CorsFilter(baselineCors);
        filter.setCorsProcessor(new org.springframework.web.cors.DefaultCorsProcessor() {
            @Override protected void rejectRequest(org.springframework.http.server.ServerHttpResponse response) throws java.io.IOException {
                responses.write(((org.springframework.http.server.ServletServerHttpResponse) response).getServletResponse(), 403);
            }
        });
        return http.authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/listings/mine").authenticated()
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/listings/search", "/api/v1/listings/*").permitAll()
                        .anyRequest().denyAll())
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .addFilterAt(filter, org.springframework.web.filter.CorsFilter.class)
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
