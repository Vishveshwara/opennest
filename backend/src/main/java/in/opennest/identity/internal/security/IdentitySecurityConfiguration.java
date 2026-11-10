package in.opennest.identity.internal.security;

import java.time.Clock;
import java.util.List;
import in.opennest.common.HttpFailureResponse;
import in.opennest.identity.internal.configuration.IdentitySettings;
import in.opennest.identity.internal.telegram.TelegramSettings;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.cors.DefaultCorsProcessor;
import org.springframework.web.filter.CorsFilter;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpResponse;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "opennest.identity.telegram-enabled", havingValue = "true")
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 1800, redisNamespace = "${spring.session.redis.namespace}")
class IdentitySecurityConfiguration {
    @Bean Clock identityClock() { return Clock.systemUTC(); }
    @Bean HttpSessionSecurityContextRepository identityContexts() {
        var contexts = new HttpSessionSecurityContextRepository();
        contexts.setDisableUrlRewriting(true);
        return contexts;
    }
    @Bean CookieSerializer sessionCookies(Environment environment) {
        var cookies = new DefaultCookieSerializer();
        cookies.setCookieName(IdentitySettings.cookieName(environment));
        cookies.setCookiePath("/");
        cookies.setUseHttpOnlyCookie(true);
        cookies.setUseSecureCookie(IdentitySettings.production(environment)
                || IdentitySettings.read(environment).selected(environment).backendOrigin().startsWith("https://"));
        cookies.setSameSite("Lax");
        return cookies;
    }
    @Bean AuthenticationManager identityAuthenticationManager() {
        return authentication -> { throw new BadCredentialsException("Unsupported authentication"); };
    }
    @Bean WebSecurityCustomizer identityRejectedRequests(HttpFailureResponse errors) {
        return web -> web.requestRejectedHandler((request, response, failure) -> errors.write(response, 400));
    }
    @Bean({"identityCors", "applicationCors"}) CorsConfigurationSource identityCors(Environment environment) {
        var cors = new CorsConfiguration();
        cors.setAllowedOrigins(List.of(IdentitySettings.read(environment).selected(environment).frontendOrigin()));
        cors.setAllowCredentials(true);
        cors.setAllowedMethods(List.of("GET", "POST", "PATCH", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Content-Type", "X-CSRF-TOKEN"));
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/v1/**", cors);
        return source;
    }
    @Bean SecurityFilterChain identitySecurity(HttpSecurity http, Environment environment, HttpFailureResponse errors,
            Clock clock, CorsConfigurationSource identityCors, TelegramSettings telegram,
            HttpSessionSecurityContextRepository identityContexts) throws Exception {
        var corsFilter = new CorsFilter(identityCors);
        corsFilter.setCorsProcessor(new DefaultCorsProcessor() {
            @Override protected void rejectRequest(ServerHttpResponse response) throws java.io.IOException {
                errors.write(((ServletServerHttpResponse) response).getServletResponse(), 403);
            }
        });
        org.springframework.security.web.util.matcher.RequestMatcher webhook = request -> !telegram.polling()
                && "POST".equals(request.getMethod()) && "/api/v1/webhooks/telegram".equals(request.getServletPath());
        http.authorizeHttpRequests(authorize -> authorize
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/auth/csrf", "/api/v1/auth/telegram/challenges/*").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/v1/auth/telegram/challenges", "/api/v1/auth/telegram/challenges/*/complete").permitAll()
                .requestMatchers(webhook).permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/users/me", "/api/v1/listings/mine").authenticated()
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/listings/search", "/api/v1/listings/*").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/v1/listings", "/api/v1/listings/*/publish", "/api/v1/listings/*/pause",
                        "/api/v1/listings/*/images/presign", "/api/v1/listings/*/images/complete").authenticated()
                .requestMatchers(org.springframework.http.HttpMethod.PATCH, "/api/v1/listings/*/inventory").authenticated()
                .anyRequest().denyAll())
                .formLogin(AbstractHttpConfigurer::disable).httpBasic(AbstractHttpConfigurer::disable)
                .requestCache(cache -> cache.requestCache(new NullRequestCache())).cors(AbstractHttpConfigurer::disable)
                .csrf(csrf -> csrf.csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()).ignoringRequestMatchers(webhook))
                .securityContext(context -> context.securityContextRepository(identityContexts))
                .sessionManagement(sessions -> sessions.sessionFixation(fixation -> fixation.changeSessionId()))
                .addFilterAt(corsFilter, CorsFilter.class)
                .addFilterAfter(new SessionPolicyFilter(IdentitySettings.realm(environment), clock, errors), CorsFilter.class)
                .exceptionHandling(failures -> failures
                        .authenticationEntryPoint((request, response, failure) -> errors.write(response, 401))
                        .accessDeniedHandler((request, response, failure) -> errors.write(response, 403)))
                .logout(logout -> logout.logoutUrl("/api/v1/auth/logout")
                        .logoutSuccessHandler((request, response, authentication) -> {
                            response.setHeader("Cache-Control", "no-store");
                            response.setStatus(204);
                        }));
        return http.build();
    }
}
