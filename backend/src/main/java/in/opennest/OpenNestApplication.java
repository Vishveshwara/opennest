package in.opennest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;

@Modulithic(systemName = "OpenNest")
@SpringBootApplication
public class OpenNestApplication {

    public static void main(String[] args) {
        SpringApplication.run(OpenNestApplication.class, args);
    }
}
