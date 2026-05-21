/**
 * A small, well-formed class that should raise no issues from the curated
 * Java rule set.
 */
public class Clean {

    private final int value;

    public Clean(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }

    public int plus(int other) {
        return value + other;
    }
}
