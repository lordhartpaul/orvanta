package io.orvanta.core.format;

import io.orvanta.core.data.Rec;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;
import java.io.File;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generic ISO 20022 codec. Any MX message is read into a record tree without a per-message class:
 * elements become keys, repeated elements become lists, attributes become "@name" keys and the
 * text of an element that also has attributes is stored under "#text". The message type
 * (for example pain.001.001.09) is taken from the namespace of the Document element.
 */
public final class IsoXml {

    private static final Pattern ISO_NS = Pattern.compile("urn:iso:std:iso:20022:tech:xsd:([A-Za-z]{4}\\.\\d{3}\\.\\d{3}\\.\\d{2})");

    private IsoXml() {
    }

    public static Rec parse(String xml) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            // no DTDs and no external entities: inbound files are untrusted
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            javax.xml.parsers.DocumentBuilder builder = f.newDocumentBuilder();
            // the default handler prints to stderr; errors are reported through the exception instead
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
                @Override
                public void fatalError(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException {
                    throw e;
                }
            });
            Document doc = builder.parse(new InputSource(new StringReader(xml)));
            Element root = doc.getDocumentElement();
            Rec tree = new Rec();
            tree.put(root.getLocalName(), read(root));
            return tree;
        } catch (Exception e) {
            throw new IllegalArgumentException("not well-formed XML: " + e.getMessage(), e);
        }
    }

    /** Message type of a parsed tree, skipping the business application header (head.001). */
    public static String messageType(Rec tree) {
        List<String> found = new ArrayList<>();
        collectTypes(tree, found);
        for (String t : found) {
            if (!t.startsWith("head.")) {
                return t;
            }
        }
        return found.isEmpty() ? null : found.get(0);
    }

    private static void collectTypes(Object node, List<String> found) {
        if (node instanceof Map<?, ?> m) {
            Object ns = m.get("@xmlns");
            if (ns != null) {
                Matcher matcher = ISO_NS.matcher(String.valueOf(ns));
                if (matcher.matches()) {
                    found.add(matcher.group(1));
                }
            }
            for (Object v : m.values()) {
                collectTypes(v, found);
            }
        } else if (node instanceof List<?> l) {
            for (Object v : l) {
                collectTypes(v, found);
            }
        }
    }

    public static String namespaceOf(String messageType) {
        return "urn:iso:std:iso:20022:tech:xsd:" + messageType;
    }

    private static Object read(Element el) {
        Rec rec = new Rec();
        String ns = el.getNamespaceURI();
        Node parent = el.getParentNode();
        boolean nsChanges = !(parent instanceof Element p) || !String.valueOf(ns).equals(String.valueOf(p.getNamespaceURI()));
        if (ns != null && nsChanges) {
            rec.put("@xmlns", ns);
        }
        NamedNodeMap attrs = el.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Attr a = (Attr) attrs.item(i);
            if (a.getName().startsWith("xmlns") || XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI.equals(a.getNamespaceURI())) {
                continue;
            }
            rec.put("@" + a.getLocalName(), a.getValue());
        }
        StringBuilder text = new StringBuilder();
        boolean hasChildElements = false;
        for (Node n = el.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element child) {
                hasChildElements = true;
                Object value = read(child);
                String key = child.getLocalName();
                Object existing = rec.get(key);
                if (existing == null) {
                    rec.put(key, value);
                } else if (existing instanceof List<?>) {
                    rec.list(key).add(value);
                } else {
                    List<Object> list = new ArrayList<>();
                    list.add(existing);
                    list.add(value);
                    rec.put(key, list);
                }
            } else if (n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE) {
                text.append(n.getNodeValue());
            }
        }
        if (hasChildElements) {
            return rec;
        }
        String value = text.toString().trim();
        if (rec.isEmpty()) {
            return value;
        }
        rec.put("#text", value);
        return rec;
    }

    /** Writes a tree with a single root key back to XML. Key order is element order. */
    public static String write(Rec tree) {
        if (tree.size() != 1) {
            throw new IllegalArgumentException("an XML tree needs exactly one root element, found " + tree.keySet());
        }
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        Map.Entry<String, Object> root = tree.entrySet().iterator().next();
        writeElement(sb, root.getKey(), root.getValue(), 0);
        return sb.toString();
    }

    private static void writeElement(StringBuilder sb, String name, Object value, int depth) {
        if (value instanceof List<?> l) {
            for (Object item : l) {
                writeElement(sb, name, item, depth);
            }
            return;
        }
        if (value == null) {
            return;
        }
        String indent = "  ".repeat(depth);
        sb.append(indent).append('<').append(name);
        if (!(value instanceof Map<?, ?> m)) {
            sb.append('>').append(escape(io.orvanta.core.expr.Ops.str(value))).append("</").append(name).append(">\n");
            return;
        }
        boolean hasChildren = false;
        for (Map.Entry<?, ?> e : m.entrySet()) {
            String key = String.valueOf(e.getKey());
            if (key.startsWith("@")) {
                sb.append(' ').append(key.substring(1)).append("=\"").append(escape(io.orvanta.core.expr.Ops.str(e.getValue()))).append('"');
            } else if (!key.equals("#text")) {
                hasChildren = true;
            }
        }
        if (!hasChildren) {
            Object text = m.get("#text");
            sb.append('>').append(text == null ? "" : escape(io.orvanta.core.expr.Ops.str(text))).append("</").append(name).append(">\n");
            return;
        }
        sb.append(">\n");
        for (Map.Entry<?, ?> e : m.entrySet()) {
            String key = String.valueOf(e.getKey());
            if (!key.startsWith("@") && !key.equals("#text")) {
                writeElement(sb, key, e.getValue(), depth + 1);
            }
        }
        sb.append(indent).append("</").append(name).append(">\n");
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /**
     * Validates against {@code <schemaDir>/<messageType>.xsd} when that file exists.
     * Returns null when valid or when no schema is installed for the type, otherwise the problems found.
     */
    public static String validate(String xml, String messageType, File schemaDir) {
        // the type becomes part of a file name, so it must look like a message type and nothing else
        if (schemaDir == null || messageType == null || !messageType.matches("[A-Za-z]{4}\\.\\d{3}\\.\\d{3}\\.\\d{2}")) {
            return null;
        }
        File xsd = new File(schemaDir, messageType + ".xsd");
        if (!xsd.isFile()) {
            return null;
        }
        try {
            List<String> problems = validate(xml, compileSchema(java.nio.file.Files.readString(xsd.toPath())), 5);
            return problems.isEmpty() ? null : String.join("; ", problems);
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    /** The message type an XSD defines, from its target namespace; null when it is not an ISO 20022 message schema. */
    public static String schemaMessageType(String xsd) {
        try {
            Element root = dom(xsd).getDocumentElement();
            if (!XMLConstants.W3C_XML_SCHEMA_NS_URI.equals(root.getNamespaceURI()) || !"schema".equals(root.getLocalName())) {
                return null;
            }
            Matcher m = ISO_NS.matcher(root.getAttribute("targetNamespace"));
            return m.matches() ? m.group(1) : null;
        } catch (Exception e) {
            throw new IllegalArgumentException("not well-formed XML: " + e.getMessage(), e);
        }
    }

    /** Compiles an XSD. It may not load anything from outside itself: no imports from files or the network. */
    public static javax.xml.validation.Schema compileSchema(String xsd) {
        try {
            SchemaFactory sf = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            sf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            sf.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            sf.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            return sf.newSchema(new StreamSource(new StringReader(xsd)));
        } catch (Exception e) {
            throw new IllegalArgumentException("the schema cannot be used: " + e.getMessage(), e);
        }
    }

    /**
     * Problems of a message against a compiled schema, at most 'limit' of them. Each names the element
     * by its path in the message, for example Document/CstmrCdtTrfInitn/PmtInf[2]/CdtTrfTxInf[1]/Amt/InstdAmt.
     */
    public static List<String> validate(String xml, javax.xml.validation.Schema schema, int limit) {
        List<String> problems = new ArrayList<>();
        try {
            Document doc = dom(xml);
            javax.xml.validation.Validator v = schema.newValidator();
            v.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            v.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            v.setErrorHandler(new org.xml.sax.ErrorHandler() {
                private void add(org.xml.sax.SAXParseException e) {
                    if (problems.size() < limit) {
                        String where = null;
                        try {
                            // the validator of the JDK tells which element it is looking at
                            Object node = v.getProperty("http://apache.org/xml/properties/dom/current-element-node");
                            where = node instanceof Element el ? pathOf(el) : null;
                        } catch (org.xml.sax.SAXException ignored) {
                            // another validator: the message alone has to do
                        }
                        // the validator reports a wrong value twice, as the rule broken and as "the value is not valid": the first says it all
                        String prefix = where == null ? null : where + ": ";
                        if (prefix == null || problems.stream().noneMatch(p -> p.startsWith(prefix))) {
                            problems.add((prefix == null ? "" : prefix) + plain(e.getMessage()));
                        }
                    }
                }

                @Override
                public void warning(org.xml.sax.SAXParseException e) {
                }

                @Override
                public void error(org.xml.sax.SAXParseException e) {
                    add(e);
                }

                @Override
                public void fatalError(org.xml.sax.SAXParseException e) {
                    add(e);
                }
            });
            v.validate(new javax.xml.transform.dom.DOMSource(doc));
        } catch (Exception e) {
            if (problems.isEmpty()) {
                problems.add(String.valueOf(e.getMessage()));
            }
        }
        return problems;
    }

    /** Reads XML that came from outside: no DTD, no external entities, nothing fetched. */
    private static Document dom(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        javax.xml.parsers.DocumentBuilder builder = f.newDocumentBuilder();
        builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
            @Override
            public void fatalError(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException {
                throw e;
            }
        });
        return builder.parse(new InputSource(new StringReader(xml)));
    }

    /** Path of an element; an index is given where the parent has several children of that name. */
    private static String pathOf(Element element) {
        List<String> parts = new ArrayList<>();
        for (Node n = element; n instanceof Element e; n = n.getParentNode()) {
            String name = e.getLocalName() == null ? e.getNodeName() : e.getLocalName();
            int index = 0;
            int same = 0;
            if (e.getParentNode() != null) {
                for (Node c = e.getParentNode().getFirstChild(); c != null; c = c.getNextSibling()) {
                    if (c instanceof Element ce && name.equals(ce.getLocalName() == null ? ce.getNodeName() : ce.getLocalName())) {
                        same++;
                        if (c == e) {
                            index = same;
                        }
                    }
                }
            }
            parts.add(0, same > 1 ? name + "[" + index + "]" : name);
        }
        return String.join("/", parts);
    }

    /** The validator's messages start with a rule number and quote namespaces; both are noise for the reader. */
    private static String plain(String message) {
        return message == null ? "" : message.replaceFirst("^cvc-[A-Za-z0-9.\\-]+: ", "").replaceAll("\"urn:iso:std:iso:20022:tech:xsd:[^\"]*\":", "");
    }
}
