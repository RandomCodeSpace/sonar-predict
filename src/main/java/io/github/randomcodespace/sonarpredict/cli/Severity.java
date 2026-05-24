package io.github.randomcodespace.sonarpredict.cli;

/**
 * Issue severity, ordered from most to least severe. Used by the
 * {@code --severity} filter: an issue passes the filter when its severity is at
 * or above the configured floor.
 *
 * <p>Declaration order is the ranking — {@link Enum#ordinal()} 0 is the most
 * severe. An issue's string severity comes from the daemon ({@code BLOCKER},
 * {@code CRITICAL}, {@code MAJOR}, {@code MINOR}, {@code INFO}).
 */
public enum Severity {
    BLOCKER,
    CRITICAL,
    MAJOR,
    MINOR,
    INFO;

    /**
     * Whether an issue with {@code issueSeverity} meets this floor.
     *
     * @param issueSeverity the issue's severity string from the daemon
     * @return {@code true} if the issue is at or above this severity; an
     *         unrecognized string is treated as passing (never silently dropped)
     */
    public boolean accepts(String issueSeverity) {
        if (issueSeverity == null) {
            return true;
        }
        try {
            return Severity.valueOf(issueSeverity.trim().toUpperCase()).ordinal() <= ordinal();
        } catch (IllegalArgumentException unknown) {
            return true;
        }
    }
}
