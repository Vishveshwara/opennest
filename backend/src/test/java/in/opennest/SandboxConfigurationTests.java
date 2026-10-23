package in.opennest;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration,org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
        "OPENNEST_DATA_MODE=SANDBOX",
        "OPENNEST_ENVIRONMENT=DEVELOPMENT",
        "DB_PASSWORD=",
        "SANDBOX_DB_PASSWORD=fabricated-sandbox-database-password"
})
class SandboxConfigurationTests {

    @Autowired
    private HikariDataSource dataSource;

    @Autowired
    private LettuceConnectionFactory redis;

    @Test
    void constructsOnlySandboxClientTargets() {
        assertThat(dataSource.getJdbcUrl()).isEqualTo("jdbc:postgresql://127.0.0.1:5433/opennest_sandbox");
        assertThat(dataSource.getUsername()).isEqualTo("opennest_sandbox");
        assertThat(dataSource.getMaximumPoolSize()).isEqualTo(5);
        assertThat(dataSource.getMinimumIdle()).isEqualTo(1);
        assertThat(redis.getHostName()).isEqualTo("127.0.0.1");
        assertThat(redis.getPort()).isEqualTo(6380);
        assertThat(redis.getDatabase()).isZero();
    }
}
