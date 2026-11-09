package in.opennest;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import com.zaxxer.hikari.HikariDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest(properties = {
        "DB_PASSWORD=fabricated-test-database-password",
        "spring.autoconfigure.exclude=org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration,org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration"
})
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
class OpenNestApplicationTests {

    @Autowired private org.springframework.test.web.servlet.MockMvc mvc;

    @Test void googleDisabledBrowsingHasExactOriginCorsAndNoAuthenticatedRoutes() throws Exception {
        for (var request : java.util.List.of(post("/api/v1/listings"),
                patch("/api/v1/listings/00000000-0000-0000-0000-000000000001/inventory"))) {
            mvc.perform(request.with(user("fabricated-test-principal")).with(csrf())
                            .contentType("application/json").content("{}"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.detail").value("Access is denied."));
        }
        mvc.perform(options("/api/v1/listings/search").header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"))
                .andExpect(header().string("Access-Control-Allow-Methods", "GET,OPTIONS"));
        mvc.perform(options("/api/v1/listings/search").header("Origin", "http://untrusted.example.invalid")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"))
                .andExpect(jsonPath("$.detail").value("Access is denied."));
    }

    @Autowired
    private HikariDataSource dataSource;

    @Autowired
    private LettuceConnectionFactory redis;

    @Autowired
    @Qualifier("applicationTaskExecutor")
    private AsyncTaskExecutor taskExecutor;

    @Test
    void contextLoads() {
    }

    @Autowired
    private org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping handlerMapping;

    @Test
    void exposesOnlyListingAndFrameworkErrorHandlersWithoutDiagnosticControllers() {
        assertThat(handlerMapping.getHandlerMethods().values()).allSatisfy(handler ->
                assertThat(handler.getBeanType().getName()).isIn("in.opennest.common.internal.web.SafeErrorController", "in.opennest.listing.internal.ListingController"));
    }

    @Test
    void configuresPrimaryClientsWithoutConnecting() {
        assertThat(dataSource.getJdbcUrl()).isEqualTo("jdbc:postgresql://127.0.0.1:5432/opennest");
        assertThat(dataSource.getMaximumPoolSize()).isEqualTo(5);
        assertThat(dataSource.getMinimumIdle()).isEqualTo(1);
        assertThat(redis.getHostName()).isEqualTo("127.0.0.1");
        assertThat(redis.getPort()).isEqualTo(6379);
        assertThat(redis.getDatabase()).isZero();
    }

    @Test
    void executesApplicationTasksOnVirtualThreads() throws Exception {
        assertThat(taskExecutor.submit(() -> Thread.currentThread().isVirtual()).get(5, TimeUnit.SECONDS)).isTrue();
    }
}
