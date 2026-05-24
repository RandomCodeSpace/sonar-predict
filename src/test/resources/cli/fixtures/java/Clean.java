package fixtures.java;

/**
 * A small, well-formed class that should raise no issues from the default
 * "Sonar way" Java rule set. The named package keeps java:S1220 ("move this
 * file to a named package") silent — that rule is part of the Sonar way set.
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
