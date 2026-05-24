package io.github.randomcodespace.sonarpredict.cli.coverage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Shared XXE-safe XML support for the coverage parsers.
 *
 * <p>Coverage XML formats (JaCoCo, Cobertura) ship a DOCTYPE referencing a DTD,
 * so unlike {@code QualityProfile} this helper cannot use
 * {@code disallow-doctype-decl}. Instead it keeps the DOCTYPE but neutralizes
 * the attack surface: external general and parameter entities are disabled, no
 * external DTD is loaded, XInclude is off, and a secure-processing limit caps
 * entity expansion — so a DTD with a billion-laughs payload cannot detonate.
 */
final class CoverageXml {

    private CoverageXml() {
    }

    /** Parses an XML coverage report into a DOM document, XXE-safely. */
    static Document parse(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            return newSecureBuilder().parse(in);
        } catch (SAXException e) {
            throw new CoverageException(
                    "malformed XML coverage report " + path + ": " + e.getMessage(), e);
        } catch (IOException e) {
            throw new CoverageException(
                    "could not read coverage report " + path + ": " + e.getMessage(), e);
        }
    }

    private static DocumentBuilder newSecureBuilder() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // Secure processing caps entity expansion (billion-laughs defense).
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            // No external entities, no external DTD fetch — XXE hardening.
            factory.setFeature(
                    "http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature(
                    "http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature(
                    "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setValidating(false);
            return factory.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            throw new CoverageException(
                    "could not configure the XML parser: " + e.getMessage(), e);
        }
    }

    /** Every descendant element with the given tag name, in document order. */
    static List<Element> elementsByTag(Element root, String tagName) {
        List<Element> matches = new ArrayList<>();
        NodeList nodes = root.getElementsByTagName(tagName);
        for (int i = 0; i < nodes.getLength(); i++) {
            matches.add((Element) nodes.item(i));
        }
        return matches;
    }

    /** Direct child elements of {@code parent} with the given tag name. */
    static List<Element> children(Element parent, String tagName) {
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

    /** Parses an integer attribute, defaulting to {@code 0} when absent or blank. */
    static int intAttr(Element element, String name) {
        String value = element.getAttribute(name);
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            throw new CoverageException(
                    "non-numeric '" + name + "' attribute: " + value);
        }
    }
}
