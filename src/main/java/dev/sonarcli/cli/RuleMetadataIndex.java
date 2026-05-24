package dev.sonarcli.cli;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.sonarcli.protocol.dto.RuleMetadata;

/**
 * An in-memory index of {@link RuleMetadata} keyed by rule key, fetched once
 * from the daemon and shared with every reporter so issue output can carry the
 * rule's name, description, and fix guidance.
 *
 * <p>Lookups for an unknown (or {@code null}) rule key return {@code null};
 * reporters treat that as "no metadata" and degrade gracefully.
 */
public final class RuleMetadataIndex {

    private static final RuleMetadataIndex EMPTY = new RuleMetadataIndex(List.of());

    private final Map<String, RuleMetadata> byKey;

    /**
     * Builds an index over the given metadata.
     *
     * @param rules the rule metadata to index; later duplicates of a key win
     */
    public RuleMetadataIndex(List<RuleMetadata> rules) {
        Map<String, RuleMetadata> map = new HashMap<>();
        for (RuleMetadata rule : rules) {
            if (rule != null && rule.ruleKey() != null) {
                map.put(rule.ruleKey(), rule);
            }
        }
        this.byKey = Map.copyOf(map);
    }

    /** An index with no metadata — every lookup returns {@code null}. */
    public static RuleMetadataIndex empty() {
        return EMPTY;
    }

    /**
     * Looks up metadata for a rule key.
     *
     * @param ruleKey the rule key, may be {@code null}
     * @return the metadata, or {@code null} if the key is unknown or {@code null}
     */
    public RuleMetadata lookup(String ruleKey) {
        return ruleKey == null ? null : byKey.get(ruleKey);
    }

    /** All indexed metadata, sorted by rule key for a stable listing. */
    public List<RuleMetadata> all() {
        return byKey.values().stream()
                .sorted(java.util.Comparator.comparing(RuleMetadata::ruleKey))
                .toList();
    }

    /** Number of rules in the index. */
    public int size() {
        return byKey.size();
    }
}
