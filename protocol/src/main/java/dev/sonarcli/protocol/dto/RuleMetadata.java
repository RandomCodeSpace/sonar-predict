package dev.sonarcli.protocol.dto;

/**
 * Static metadata for one analyzer rule, used to enrich findings so an agent
 * can act on them.
 *
 * @param descriptionHtml the rule's full HTML description
 * @param howToFix        remediation guidance, or {@code null} if the analyzer
 *                        provides none for this rule
 */
public record RuleMetadata(
        String ruleKey,
        String name,
        String language,
        String severity,
        String type,
        String descriptionHtml,
        String howToFix
) {
}
