package io.github.randomcodespace.sonarpredict.daemon;

import java.util.Map;
import org.sonar.api.batch.rule.ActiveRule;
import org.sonar.api.rule.RuleKey;

/**
 * Adapter for engine 11.x: {@code AnalysisConfiguration.Builder.addActiveRules} now takes
 * the public {@link ActiveRule} interface; 10.x's internal {@code ActiveRule} class is gone
 * and no public concrete ships. This record is the minimal implementation that satisfies
 * the interface for our analyses (no severity / template / qProfile metadata needed offline).
 */
public record SimpleActiveRule(
        RuleKey ruleKey,
        String language,
        String severity,
        String internalKey,
        String templateRuleKey,
        Map<String, String> params,
        String qpKey) implements ActiveRule {

    @Override
    public String param(String key) {
        return params.get(key);
    }

    public static SimpleActiveRule of(String ruleKey, String language, Map<String, String> params) {
        return new SimpleActiveRule(
                RuleKey.parse(ruleKey),
                language,
                null,
                null,
                null,
                params == null ? Map.of() : Map.copyOf(params),
                null);
    }
}
