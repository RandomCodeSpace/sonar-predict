package dev.sonarcli.daemon;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * A parsed SonarQube quality-profile XML export — the file the CLI passes via
 * {@code --config}. It carries the profile's declared name and language plus
 * its list of activated rules; each rule's engine key is
 * {@code repositoryKey:key} with any per-rule {@code <parameters>} captured as
 * a key→value map.
 *
 * <p>The XML shape (SonarQube's "Back up" / export format):
 * <pre>{@code
 * <profile>
 *   <name>...</name>
 *   <language>...</language>
 *   <rules>
 *     <rule>
 *       <repositoryKey>java</repositoryKey>
 *       <key>S1192</key>
 *       <parameters>
 *         <parameter><key>threshold</key><value>5</value></parameter>
 *       </parameters>
 *     </rule>
 *   </rules>
 * </profile>
 * }</pre>
 *
 * <p>Parsing is XXE-safe: DTDs and external entities are disabled.
 */
public final class QualityProfile {

    private final String name;
    private final String language;
    private final List<ProfileRule> rules;

    private QualityProfile(String name, String language, List<ProfileRule> rules) {
        this.name = name;
        this.language = language;
        this.rules = List.copyOf(rules);
    }

    /**
     * One activated rule from a quality profile.
     *
     * @param ruleKey    the engine rule key, {@code repositoryKey:key}
     * @param parameters per-rule parameters; empty when the rule has none
     */
    public record ProfileRule(String ruleKey, Map<String, String> parameters) {
        public ProfileRule {
            parameters = Map.copyOf(parameters);
        }
    }

    /**
     * Parses a SonarQube quality-profile XML file.
     *
     * @param xml path to the profile XML
     * @return the parsed profile
     * @throws QualityProfileException if the file is missing, unreadable, or
     *                                 not a well-formed quality profile
     */
    public static QualityProfile parse(Path xml) {
        if (xml == null) {
            throw new QualityProfileException("quality-profile path is null");
        }
        if (!Files.isRegularFile(xml)) {
            throw new QualityProfileException(
                    "quality-profile file not found: " + xml.toAbsolutePath());
        }

        Document document;
        try (InputStream in = Files.newInputStream(xml)) {
            document = newSecureBuilder().parse(in);
        } catch (SAXException e) {
            throw new QualityProfileException(
                    "malformed quality-profile XML in " + xml + ": " + e.getMessage(), e);
        } catch (IOException e) {
            throw new QualityProfileException(
                    "could not read quality-profile file " + xml + ": " + e.getMessage(), e);
        }

        Element root = document.getDocumentElement();
        if (root == null || !"profile".equals(root.getNodeName())) {
            throw new QualityProfileException(
                    "not a quality profile: " + xml + " (expected a <profile> root element)");
        }

        String name = childText(root, "name");
        String language = childText(root, "language");
        List<ProfileRule> rules = parseRules(root, xml);
        return new QualityProfile(name, language, rules);
    }

    private static List<ProfileRule> parseRules(Element root, Path xml) {
        List<ProfileRule> rules = new ArrayList<>();
        for (Element rulesElement : children(root, "rules")) {
            for (Element ruleElement : children(rulesElement, "rule")) {
                String repositoryKey = childText(ruleElement, "repositoryKey");
                String key = childText(ruleElement, "key");
                if (repositoryKey == null || repositoryKey.isBlank()
                        || key == null || key.isBlank()) {
                    throw new QualityProfileException(
                            "quality-profile rule is missing repositoryKey or key in " + xml);
                }
                rules.add(new ProfileRule(
                        repositoryKey + ":" + key, parseParameters(ruleElement)));
            }
        }
        return rules;
    }

    private static Map<String, String> parseParameters(Element ruleElement) {
        Map<String, String> params = new LinkedHashMap<>();
        for (Element parametersElement : children(ruleElement, "parameters")) {
            for (Element parameterElement : children(parametersElement, "parameter")) {
                String key = childText(parameterElement, "key");
                String value = childText(parameterElement, "value");
                if (key != null && !key.isBlank()) {
                    params.put(key, value == null ? "" : value);
                }
            }
        }
        return params;
    }

    private static DocumentBuilder newSecureBuilder() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // XXE hardening: no DTDs, no external entities.
            factory.setFeature(
                    "http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(
                    "http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature(
                    "http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            throw new QualityProfileException(
                    "could not configure the XML parser: " + e.getMessage(), e);
        }
    }

    /** Direct child elements of {@code parent} with the given tag name. */
    private static List<Element> children(Element parent, String tagName) {
        List<Element> matches = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE
                    && tagName.equals(node.getNodeName())) {
                matches.add((Element) node);
            }
        }
        return matches;
    }

    /** Trimmed text of the first direct child element named {@code tagName}, or null. */
    private static String childText(Element parent, String tagName) {
        List<Element> matches = children(parent, tagName);
        if (matches.isEmpty()) {
            return null;
        }
        String text = matches.get(0).getTextContent();
        return text == null ? null : text.strip();
    }

    /** The profile's declared name, or {@code null} if absent. */
    public String name() {
        return name;
    }

    /** The profile's declared language, or {@code null} if absent. */
    public String language() {
        return language;
    }

    /** Every activated rule, in document order. */
    public List<ProfileRule> rules() {
        return rules;
    }

    /** Just the engine rule keys ({@code repositoryKey:key}), in document order. */
    public List<String> ruleKeys() {
        return rules.stream().map(ProfileRule::ruleKey).toList();
    }
}
