/**
 * Writes directly to System.out.
 * Triggers java:S106 — standard outputs should not be used directly.
 */
public class ConsolePrinter {

    private final String prefix;

    public ConsolePrinter(String prefix) {
        this.prefix = prefix;
    }

    public void print(String message) {
        System.out.println(prefix + ": " + message);
    }
}
