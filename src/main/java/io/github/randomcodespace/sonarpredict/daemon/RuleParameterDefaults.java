package io.github.randomcodespace.sonarpredict.daemon;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.sonar.api.Plugin;
import org.sonar.api.SonarRuntime;
import org.sonar.api.server.rule.RulesDefinition;
import org.sonar.api.utils.Version;
import org.sonarsource.sonarlint.core.plugin.commons.LoadedPlugins;
import org.sonarsource.sonarlint.core.plugin.commons.sonarapi.SonarLintRuntimeImpl;

/**
 * The per-rule parameter defaults each analyzer registers in its
 * {@code RulesDefinition}, keyed by engine rule key ({@code <repo>:<sqKey>}).
 *
 * <p><b>Why this exists.</b> The SonarLint analysis engine injects a check
 * parameter <em>only</em> when the {@link org.sonarsource.sonarlint.core.analysis.api.ActiveRule
 * ActiveRule} handed to it carries that parameter; with an empty parameter map
 * the check keeps its Java field initializer. That initializer is the generic,
 * cross-language {@code @RuleProperty} default — which is wrong for some
 * languages. The loudest case is {@code go:S100} (function naming): the shared
 * SLANG check {@code BadFunctionNameCheck} initializes {@code format} to the
 * camelCase {@code ^[a-z][a-zA-Z0-9]*$}, so an analysis that activates
 * {@code go:S100} with no parameters flags every exported Go function and every
 * {@code TestXxx} — idiomatic Go. The analyzer's <em>real</em> per-language
 * default ({@code ^(_|[a-zA-Z0-9]+)$} for Go) lives in its {@code RulesDefinition},
 * registered through a {@code @PropertyDefaultValue(language=...)} annotation.
 *
 * <p>{@link SonarWayProfiles} reconstructs the active-rule set from the static
 * {@code Sonar_way_profile.json} resource, which lists only rule keys — no
 * parameters. This class fills that gap: it drives every loaded analyzer
 * plugin's {@code RulesDefinition} once and harvests the parameter defaults the
 * analyzer actually registers, so {@link AnalysisService} can attach them to
 * every {@code ActiveRule}.
 *
 * <p><b>Graceful degradation.</b> Extraction is best-effort and isolated
 * per plugin: an analyzer whose {@code RulesDefinition} cannot be driven
 * contributes no parameters and is recorded in {@link #failures()} — its rules
 * then run on the engine's field defaults, exactly as before this class
 * existed. One analyzer's failure never sinks the rest.
 */
public final class RuleParameterDefaults {

    /**
     * API versions for the {@link SonarRuntime} handed to each plugin's
     * {@code define()}. They gate feature availability while a plugin
     * registers its rules; values at or above what the vendored analyzers
     * expect make them register every rule and parameter.
     *
     * <p>These track the {@code sonar-plugin-api} and {@code sonarlint-core}
     * ({@code sonarlint.engine.version}) dependencies in the parent POM. They
     * are deliberately hand-pinned: if a dependency bump ever made them too
     * low, {@code RuleParameterDefaultsTest} — which asserts every analyzer
     * repository is harvested — would fail rather than the daemon silently
     * losing parameter defaults.
     */
    private static final Version SONAR_PLUGIN_API_VERSION = Version.create(12, 0);
    private static final Version SONARLINT_API_VERSION = Version.create(10, 24);

    /** {@code <repo>:<sqKey>} -> ({@code paramKey} -> {@code defaultValue}). */
    private final Map<String, Map<String, String>> paramsByRuleKey;

    /** Rule-repository keys whose {@code RulesDefinition} was harvested. */
    private final Set<String> harvestedRepositories;

    /** Plugin keys whose extraction failed, mapped to the error description. */
    private final Map<String, String> failures;

    private RuleParameterDefaults(
            Map<String, Map<String, String>> paramsByRuleKey,
            Set<String> harvestedRepositories,
            Map<String, String> failures) {
        this.paramsByRuleKey = paramsByRuleKey;
        this.harvestedRepositories = harvestedRepositories;
        this.failures = failures;
    }

    /**
     * Drives every loaded analyzer plugin's {@code RulesDefinition} and
     * harvests each rule's registered parameter defaults.
     *
     * @param loadedPlugins the analyzer plugins already loaded into the engine
     * @return the harvested parameter defaults
     */
    public static RuleParameterDefaults extract(LoadedPlugins loadedPlugins) {
        SonarRuntime runtime = new SonarLintRuntimeImpl(
                SONAR_PLUGIN_API_VERSION, SONARLINT_API_VERSION, ProcessHandle.current().pid());

        Map<String, Map<String, String>> paramsByRuleKey = new HashMap<>();
        Set<String> harvestedRepositories = new LinkedHashSet<>();
        Map<String, String> failures = new HashMap<>();

        for (Map.Entry<String, Plugin> entry
                : loadedPlugins.getAnalysisPluginInstancesByKeys().entrySet()) {
            try {
                RulesDefinition.Context rulesContext =
                        defineRules(entry.getValue(), runtime);
                for (RulesDefinition.Repository repository : rulesContext.repositories()) {
                    harvestedRepositories.add(repository.key());
                    for (RulesDefinition.Rule rule : repository.rules()) {
                        Map<String, String> params = paramDefaults(rule);
                        if (!params.isEmpty()) {
                            paramsByRuleKey.putIfAbsent(
                                    repository.key() + ":" + rule.key(), params);
                        }
                    }
                }
            } catch (Exception e) {
                // Per-plugin isolation boundary: harvesting parameter defaults
                // is best-effort, so a single analyzer's RulesDefinition
                // throwing must not abort extraction for the other analyzers.
                // The failure is recorded, not swallowed.
                failures.put(entry.getKey(), e.toString());
            }
        }
        return new RuleParameterDefaults(
                Map.copyOf(paramsByRuleKey),
                Set.copyOf(harvestedRepositories),
                Map.copyOf(failures));
    }

    /** An empty instance — a test seam and a graceful no-parameters fallback. */
    public static RuleParameterDefaults empty() {
        return new RuleParameterDefaults(Map.of(), Set.of(), Map.of());
    }

    /**
     * The parameter defaults for an engine rule key ({@code <repo>:<sqKey>}).
     *
     * @param ruleKey the engine rule key, may be {@code null}
     * @return an immutable {@code paramKey -> defaultValue} map; empty when the
     *         rule has no parameters or the key is unknown or {@code null}
     */
    public Map<String, String> paramsFor(String ruleKey) {
        return ruleKey == null ? Map.of() : paramsByRuleKey.getOrDefault(ruleKey, Map.of());
    }

    /** Rule-repository keys whose {@code RulesDefinition} was harvested. */
    Set<String> harvestedRepositories() {
        return harvestedRepositories;
    }

    /** Plugin keys whose extraction failed, mapped to the error description. */
    Map<String, String> failures() {
        return failures;
    }

    /**
     * Drives one plugin's {@code define()} and runs every {@code RulesDefinition}
     * extension it contributes into a fresh rules context.
     */
    private static RulesDefinition.Context defineRules(Plugin plugin, SonarRuntime runtime) {
        Plugin.Context pluginContext = new Plugin.Context(runtime);
        plugin.define(pluginContext);

        RulesDefinition.Context rulesContext = new RulesDefinition.Context();
        for (Object extension : pluginContext.getExtensions()) {
            RulesDefinition rulesDefinition = asRulesDefinition(extension, runtime);
            if (rulesDefinition != null) {
                rulesDefinition.define(rulesContext);
            }
        }
        return rulesContext;
    }

    /**
     * A {@code RulesDefinition} extension — registered either as a ready
     * instance or, more commonly, as a {@code Class} the container instantiates.
     *
     * @return the {@code RulesDefinition}, or {@code null} if the extension is
     *         not one
     */
    private static RulesDefinition asRulesDefinition(Object extension, SonarRuntime runtime) {
        if (extension instanceof RulesDefinition rulesDefinition) {
            return rulesDefinition;
        }
        if (extension instanceof Class<?> type
                && RulesDefinition.class.isAssignableFrom(type)) {
            return instantiate(type.asSubclass(RulesDefinition.class), runtime);
        }
        return null;
    }

    /**
     * Instantiates a {@code RulesDefinition} class the way SonarLint's
     * dependency-injection container would. The SonarSource analyzers declare a
     * single public constructor whose parameters are the {@link SonarRuntime}
     * and, for some analyzers, plugin extension-point arrays (the Kotlin
     * analyzer's constructor, for instance, also takes a
     * {@code KotlinPluginExtensionsProvider[]}). This picks the public
     * constructor whose every parameter can be supplied — the runtime for a
     * {@code SonarRuntime} parameter, an empty array for an array parameter,
     * since no extension providers are registered in this standalone harvest.
     */
    private static RulesDefinition instantiate(
            Class<? extends RulesDefinition> type, SonarRuntime runtime) {
        Constructor<?> chosen = null;
        for (Constructor<?> candidate : type.getConstructors()) {
            if (canSatisfy(candidate, runtime)
                    && (chosen == null
                            || candidate.getParameterCount() > chosen.getParameterCount())) {
                chosen = candidate;
            }
        }
        if (chosen == null) {
            throw new IllegalStateException(
                    "no injectable constructor for RulesDefinition " + type.getName());
        }
        try {
            return (RulesDefinition) chosen.newInstance(argumentsFor(chosen, runtime));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "cannot instantiate RulesDefinition " + type.getName(), e);
        }
    }

    /** Whether every parameter of {@code constructor} is a runtime or an array. */
    private static boolean canSatisfy(Constructor<?> constructor, SonarRuntime runtime) {
        for (Class<?> parameterType : constructor.getParameterTypes()) {
            if (!parameterType.isInstance(runtime) && !parameterType.isArray()) {
                return false;
            }
        }
        return true;
    }

    /**
     * The argument list for {@code constructor}: the {@code runtime} for a
     * {@link SonarRuntime} parameter, an empty array for an array parameter.
     */
    private static Object[] argumentsFor(Constructor<?> constructor, SonarRuntime runtime) {
        Class<?>[] parameterTypes = constructor.getParameterTypes();
        Object[] arguments = new Object[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            arguments[i] = parameterTypes[i].isInstance(runtime)
                    ? runtime
                    : Array.newInstance(parameterTypes[i].getComponentType(), 0);
        }
        return arguments;
    }

    /** The rule's parameters as an immutable {@code key -> defaultValue} map. */
    private static Map<String, String> paramDefaults(RulesDefinition.Rule rule) {
        Map<String, String> params = new HashMap<>();
        for (RulesDefinition.Param param : rule.params()) {
            String defaultValue = param.defaultValue();
            if (defaultValue != null) {
                params.put(param.key(), defaultValue);
            }
        }
        return Map.copyOf(params);
    }
}
