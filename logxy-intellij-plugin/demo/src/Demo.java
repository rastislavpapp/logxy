import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Prints log lines, a stack trace now and then, and echoes what is typed into the console. */
public class Demo {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final String[] LEVELS = {"INFO ", "DEBUG", "INFO ", "WARN ", "ERROR"};

    public static void main(String[] args) throws Exception {
        Thread echo = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in))) {
                for (String line; (line = in.readLine()) != null; ) log("stdin", "INFO ", "typed: " + line);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, "stdin");
        echo.setDaemon(true);
        echo.start();
        for (int i = 0; i < 300; i++) {
            String level = LEVELS[i % LEVELS.length];
            log("main", level, "request " + i + " took " + (i * 7 % 300) + " ms");
            if (level.startsWith("ERROR")) new IllegalStateException("failure " + i).printStackTrace(System.out);
            Thread.sleep(300);
        }
    }

    private static void log(String thread, String level, String message) {
        System.out.println(LocalDateTime.now().format(TIME) + " [" + thread + "] " + level + " app.Demo - " + message);
    }
}
