package io.github.randomcodespace.sonarpredict.daemon;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.sonar.api.batch.rule.ActiveRule;
import org.sonar.api.rule.RuleKey;

class SimpleActiveRuleTest {

    @Test
    void of_buildsActiveRuleWithRuleKeyAndLanguage() {
        SimpleActiveRule rule = SimpleActiveRule.of("java:S1234", "java", Map.of("foo", "bar"));

        assertThat(rule).isInstanceOf(ActiveRule.class);
        assertThat(rule.ruleKey()).isEqualTo(RuleKey.parse("java:S1234"));
        assertThat(rule.language()).isEqualTo("java");
        assertThat(rule.params()).containsExactlyEntriesOf(Map.of("foo", "bar"));
    }

    @Test
    void of_nullParams_givesEmptyMap() {
        SimpleActiveRule rule = SimpleActiveRule.of("java:S1234", "java", null);
        assertThat(rule.params()).isEmpty();
    }

    @Test
    void of_paramsAreDefensivelyCopied() {
        java.util.HashMap<String, String> mutable = new java.util.HashMap<>(Map.of("a", "1"));
        SimpleActiveRule rule = SimpleActiveRule.of("java:S1234", "java", mutable);
        mutable.put("b", "2");
        assertThat(rule.params()).containsOnlyKeys("a");
    }
}
