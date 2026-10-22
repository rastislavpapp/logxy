import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Random;

/**
 * Prints the log of a busy web shop in Spring Boot 3's layout, or with the argument {@code custom} in a pipe-delimited
 * layout no built-in parser covers. It starts with ten minutes of backdated history, so the time histogram has a
 * shape at once, then logs live; an incident with retries and errors comes now and then.
 */
public class BusyApp {
    private static final DateTimeFormatter BOOT_TIME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter CUSTOM_TIME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());
    private static final long HISTORY_MILLIS = 10 * 60 * 1000;
    private static final long INCIDENT_EVERY_MILLIS = 90 * 1000;
    private static final long INCIDENT_MILLIS = 8 * 1000;

    private static final Random RANDOM = new Random(42);
    private static final long PID = ProcessHandle.current().pid();
    private static boolean custom;
    private static long time;

    public static void main(String[] args) throws Exception {
        custom = args.length > 0 && args[0].equals("custom");
        long now = System.currentTimeMillis();
        time = now - HISTORY_MILLIS;
        startup();
        while (time < now) {
            record();
            time += RANDOM.nextInt(incident() ? 60 : 400);
        }
        while (true) {
            Thread.sleep(RANDOM.nextInt(incident() ? 40 : 250));
            time = System.currentTimeMillis();
            record();
        }
    }

    private static boolean incident() {
        return time % INCIDENT_EVERY_MILLIS < INCIDENT_MILLIS;
    }

    private static void startup() {
        log("INFO", "main", "c.e.shop.ShopApplication", "Starting ShopApplication using Java 25 with PID " + PID);
        log("INFO", "main", "o.s.b.w.e.tomcat.TomcatWebServer", "Tomcat initialized with port 8080 (http)");
        log("INFO", "main", "c.e.shop.config.DataSourceConfig", "HikariPool-1 - Start completed with 10 connections");
        log("INFO", "main", "c.e.shop.ShopApplication", "Started ShopApplication in 3.412 seconds");
    }

    private static void record() {
        String http = "http-nio-8080-exec-" + (1 + RANDOM.nextInt(10));
        int order = 310000 + RANDOM.nextInt(9000);
        if (incident() && RANDOM.nextInt(3) == 0) {
            if (RANDOM.nextInt(3) == 0) {
                log("ERROR", http, "c.e.shop.inventory.InventoryClient",
                    "Request to inventory-service failed for order " + order + ": connect timed out");
                trace();
            } else {
                log("WARN", http, "c.e.shop.inventory.InventoryClient",
                    "Retrying call to inventory-service, attempt " + (1 + RANDOM.nextInt(3)) + " of 3");
            }
            return;
        }
        switch (RANDOM.nextInt(12)) {
            case 0, 1, 2 -> log("INFO", http, "c.e.shop.order.api.OrderController",
                "GET /api/v1/orders/" + order + " completed in " + millis(40) + " ms");
            case 3, 4 -> log("DEBUG", http, "c.e.shop.order.OrderRepository",
                "Fetched " + (1 + RANDOM.nextInt(50)) + " rows from orders in " + millis(8) + " ms");
            case 5 -> log("INFO", http, "c.e.shop.payment.PaymentService",
                "Payment " + (880000 + RANDOM.nextInt(9999)) + " authorized for " + (5 + RANDOM.nextInt(400)) + ".00 EUR");
            case 6 -> log("INFO", "kafka-consumer-" + (1 + RANDOM.nextInt(3)), "c.e.shop.shipping.ShipmentListener",
                "Consumed " + (1 + RANDOM.nextInt(200)) + " events from topic shipments, lag " + RANDOM.nextInt(40));
            case 7 -> log("DEBUG", "scheduling-1", "c.e.shop.cache.CacheMetrics",
                "Cache hit ratio " + (80 + RANDOM.nextInt(20)) + "% over the last " + (500 + RANDOM.nextInt(1500)) + " lookups");
            case 8 -> log("INFO", "scheduling-1", "c.e.shop.session.SessionCleanup",
                "Removed " + RANDOM.nextInt(120) + " expired sessions");
            case 9 -> log("INFO", http, "c.e.shop.auth.TokenService",
                "Issued access token for user " + (1000 + RANDOM.nextInt(9000)) + ", expires in 3600 s");
            case 10 -> {
                if (RANDOM.nextInt(4) == 0) {
                    log("WARN", http, "c.e.shop.billing.InvoiceRepository",
                        "Slow query on invoices took " + (800 + RANDOM.nextInt(2400)) + " ms");
                } else {
                    log("TRACE", http, "c.e.shop.order.api.OrderController", "Validated order " + order);
                }
            }
            default -> {
                if (RANDOM.nextInt(8) == 0) {
                    log("ERROR", http, "c.e.shop.payment.PaymentService",
                        "Payment declined for order " + order + ": insufficient funds");
                } else {
                    log("INFO", http, "c.e.shop.cart.CartService",
                        "Cart " + (50000 + RANDOM.nextInt(9999)) + " checked out with " + (1 + RANDOM.nextInt(9)) + " items");
                }
            }
        }
    }

    /** About {@code typical} milliseconds, now and then much more. */
    private static int millis(int typical) {
        return typical / 2 + RANDOM.nextInt(typical) + (RANDOM.nextInt(20) == 0 ? typical * 10 : 0);
    }

    private static void trace() {
        System.out.println("java.net.SocketTimeoutException: Connect timed out");
        System.out.println("\tat java.base/sun.nio.ch.NioSocketImpl.timedFinishConnect(NioSocketImpl.java:546)");
        System.out.println("\tat java.base/java.net.Socket.connect(Socket.java:751)");
        System.out.println("\tat java.net.http/jdk.internal.net.http.HttpClientImpl.send(HttpClientImpl.java:933)");
        System.out.println("\tat com.example.shop.inventory.InventoryClient.reserve(InventoryClient.java:58)");
        System.out.println("\tat com.example.shop.order.OrderService.place(OrderService.java:112)");
        System.out.println("\tat com.example.shop.order.api.OrderController.create(OrderController.java:41)");
    }

    private static void log(String level, String thread, String logger, String message) {
        Instant at = Instant.ofEpochMilli(time);
        if (custom) {
            String component = logger.substring(logger.lastIndexOf('.') + 1);
            System.out.printf("%s | %-5s | %s | %s | %s%n", CUSTOM_TIME.format(at), level, thread, component, message);
        } else {
            System.out.printf("%s %5s %d --- [%s] %-40.40s : %s%n", BOOT_TIME.format(at), level, PID, thread, logger, message);
        }
    }
}
