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

@SpringBootTest(properties = {
        "DB_PASSWORD=fabricated-test-database-password",
        "spring.autoconfigure.exclude=org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration,org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration"
})
class OpenNestApplicationTests {

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
    void exposesOnlyTheFrameworkErrorHandlerWithoutDiagnosticControllers() {
        assertThat(handlerMapping.getHandlerMethods().values()).allSatisfy(handler ->
                assertThat(handler.getBeanType().getName()).isEqualTo("in.opennest.common.internal.web.SafeErrorController"));
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
