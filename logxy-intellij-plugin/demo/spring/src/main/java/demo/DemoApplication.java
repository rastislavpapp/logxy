package demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * A Spring Boot application run by Gradle's bootRun: the run starts with Gradle's task lines and Spring's banner,
 * and only then comes the log, which Spring formats in its own way.
 */
@SpringBootApplication
@EnableScheduling
public class DemoApplication {

    private static final Logger log = LoggerFactory.getLogger(DemoApplication.class);

    private int tick;

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }

    @Scheduled(fixedRate = 1000)
    void work() {
        tick++;
        if (tick % 10 == 0) {
            log.warn("Order {} is slow: {} ms", tick, 1500 + tick);
        } else if (tick % 25 == 0) {
            log.error("Payment for order {} failed", tick, new IllegalStateException("card declined"));
        } else {
            log.info("Order {} processed", tick);
        }
    }
}
