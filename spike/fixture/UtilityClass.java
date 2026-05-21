package fixture;

/**
 * Fixture for the embedding spike.
 *
 * This class has only static members and an implicit PUBLIC default
 * constructor. SonarSource rule java:S1118 ("Utility classes should not
 * have public constructors") flags exactly this. Adding a private
 * constructor would silence the rule -- so do NOT add one.
 */
public class UtilityClass {

    public static final String GREETING = "hello";

    public static int add(int a, int b) {
        return a + b;
    }

    public static int square(int n) {
        return n * n;
    }
}
