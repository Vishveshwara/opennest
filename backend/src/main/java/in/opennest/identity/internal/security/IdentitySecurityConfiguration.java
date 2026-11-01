package in.opennest.identity.internal.security;

import java.time.Clock;
import java.util.List;
import in.opennest.common.HttpFailureResponse;
import in.opennest.identity.internal.configuration.IdentitySettings;
import in.opennest.identity.internal.persistence.AccountStore;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
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
@ConditionalOnProperty(name = "opennest.identity.google-enabled", havingValue = "true")
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 1800, redisNamespace = "${spring.session.redis.namespace}")
class IdentitySecurityConfiguration {

    @Bean Clock identityClock() { return Clock.systemUTC(); }

    @Bean ClientRegistrationRepository googleClients(Environment environment) {
        var google = IdentitySettings.read(environment).selected(environment);
        return new InMemoryClientRegistrationRepository(CommonOAuth2Provider.GOOGLE.getBuilder("google")
                .clientId(google.clientId()).clientSecret(google.clientSecret()).scope("openid", "email", "profile")
                .redirectUri(google.backendOrigin() + "/login/oauth2/code/google").build());
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

    @Bean CorsConfigurationSource identityCors(Environment environment) {
        var cors = new CorsConfiguration();
        cors.setAllowedOrigins(List.of(IdentitySettings.read(environment).selected(environment).frontendOrigin()));
        cors.setAllowCredentials(true);
        cors.setAllowedMethods(List.of("GET", "POST", "PATCH", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Content-Type", "X-CSRF-TOKEN"));
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/v1/**", cors);
        return source;
    }

    @Bean SecurityFilterChain identitySecurity(HttpSecurity http, Environment environment, AccountStore accounts,
            HttpFailureResponse errors, ClientRegistrationRepository clients, Clock clock,
            CorsConfigurationSource identityCors) throws Exception {
        var google = IdentitySettings.read(environment).selected(environment);
        var resolver = new DefaultOAuth2AuthorizationRequestResolver(clients, "/oauth2/authorization");
        resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
        var contexts = new HttpSessionSecurityContextRepository();
        contexts.setDisableUrlRewriting(true);
        var users = new OidcUserService();
        users.setRetrieveUserInfo(request -> false);
        var corsFilter = new CorsFilter(identityCors);
        corsFilter.setCorsProcessor(new DefaultCorsProcessor() {
            @Override protected void rejectRequest(ServerHttpResponse response) throws java.io.IOException {
                errors.write(((ServletServerHttpResponse) response).getServletResponse(), 403);
            }
        });
        http.authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.GET,
                                "/api/v1/auth/csrf", "/oauth2/authorization/google", "/login/oauth2/code/google").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/users/me").authenticated()
                        .anyRequest().denyAll())
                .formLogin(AbstractHttpConfigurer::disable).httpBasic(AbstractHttpConfigurer::disable)
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .cors(AbstractHttpConfigurer::disable)
                .csrf(csrf -> csrf.csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .securityContext(context -> context.securityContextRepository(contexts))
                .sessionManagement(sessions -> sessions.sessionFixation(fixation -> fixation.newSession()))
                .addFilterAt(corsFilter, CorsFilter.class)
                .addFilterAfter(new SessionPolicyFilter(IdentitySettings.realm(environment), clock, errors), CorsFilter.class)
                .exceptionHandling(failures -> failures
                        .authenticationEntryPoint((request, response, failure) -> errors.write(response, 401))
                        .accessDeniedHandler((request, response, failure) -> errors.write(response, 403)))
                .logout(logout -> logout.logoutUrl("/api/v1/auth/logout")
                        .logoutSuccessHandler((request, response, authentication) -> {
                            response.setHeader("Cache-Control", "no-store");
                            response.setStatus(204);
                        }))
                .oauth2Login(login -> login.loginPage(google.frontendOrigin() + "/").clientRegistrationRepository(clients)
                        .authorizedClientRepository(new DiscardingClients())
                        .authorizationEndpoint(endpoint -> endpoint.authorizationRequestResolver(resolver))
                        .userInfoEndpoint(endpoint -> endpoint.oidcUserService(users))
                        .successHandler((request, response, authentication) -> {
                            try {
                                var user = (OidcUser) authentication.getPrincipal();
                                var id = accounts.login(user.getIssuer().toString(), user.getSubject(), user.getFullName(),
                                        user.getEmail(), Boolean.TRUE.equals(user.getEmailVerified()));
                                var principal = new SessionPrincipal(id, IdentitySettings.realm(environment), clock.instant());
                                var local = UsernamePasswordAuthenticationToken.authenticated(principal, null,
                                        List.of(new SimpleGrantedAuthority("ROLE_USER")));
                                var context = SecurityContextHolder.createEmptyContext();
                                context.setAuthentication(local);
                                SecurityContextHolder.setContext(context);
                                contexts.saveContext(context, request, response);
                                response.sendRedirect(google.frontendOrigin() + "/");
                            } catch (RuntimeException failure) {
                                new SecurityContextLogoutHandler().logout(request, response, authentication);
                                throw failure;
                            }
                        })
                        .failureHandler((request, response, failure) -> {
                            response.sendRedirect(google.frontendOrigin() + "/?login=failed");
                        }));
        return http.build();
    }

    private static final class DiscardingClients implements OAuth2AuthorizedClientRepository {
        @Override public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(String id, Authentication principal, HttpServletRequest request) { return null; }
        @Override public void saveAuthorizedClient(OAuth2AuthorizedClient client, Authentication principal, HttpServletRequest request, HttpServletResponse response) { }
        @Override public void removeAuthorizedClient(String id, Authentication principal, HttpServletRequest request, HttpServletResponse response) { }
    }
}
