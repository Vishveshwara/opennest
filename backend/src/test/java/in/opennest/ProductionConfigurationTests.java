package in.opennest;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration,org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
        "OPENNEST_ENVIRONMENT=PRODUCTION",
        "OPENNEST_DATA_MODE=PRIMARY",
        "OPENNEST_DEVELOPMENT_AUTH=false",
        "OPENNEST_PERSONA_SWITCHING=false",
        "OPENNEST_DEMO_RESET=false",
        "OPENNEST_DEMO_TELEMETRY=false",
        "DB_PASSWORD=fabricated-production-database-password",
        "REDIS_PASSWORD=fabricated-production-redis-password",
        "SANDBOX_DB_PASSWORD=",
        "SANDBOX_REDIS_PASSWORD="
})
class ProductionConfigurationTests {

    @Autowired
    private HikariDataSource dataSource;

    @Autowired
    private LettuceConnectionFactory redis;

    @Test
    void loadsProductionConfigurationWithoutConnecting() {
        assertThat(dataSource.getJdbcUrl()).isEqualTo("jdbc:postgresql://127.0.0.1:5432/opennest");
        assertThat(dataSource.getMaximumPoolSize()).isEqualTo(5);
        assertThat(redis.getPort()).isEqualTo(6379);
        assertThat(redis.getStandaloneConfiguration().getPassword().isPresent()).isTrue();
    }
}
