/**
 * A utility class with only static members but a public (implicit) constructor.
 * Triggers java:S1118 — utility classes should not have public constructors.
 */
public class UtilityClass {

    public static int doubleIt(int value) {
        return value * 2;
    }

    public static int tripleIt(int value) {
        return value * 3;
    }
}
