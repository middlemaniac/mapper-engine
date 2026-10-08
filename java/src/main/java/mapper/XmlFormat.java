package mapper;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class XmlFormat {
    private XmlFormat() {
    }

    public static Object read(java.nio.file.Path path) throws Exception {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        try (InputStream input = new FileInputStream(path.toFile())) {
            XMLStreamReader reader = factory.createXMLStreamReader(input);
            while (reader.hasNext() && reader.next() != XMLStreamConstants.START_ELEMENT) {
                // Advance to the document element.
            }
            if (reader.getEventType() != XMLStreamConstants.START_ELEMENT) {
                throw new XMLStreamException("XML document has no root element");
            }
            String rootName = localName(reader.getLocalName());
            Object rootValue = elementToValue(reader);
            reader.close();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put(rootName, rootValue);
            return result;
        }
    }

    public static String write(Object data, Map<String, Object> mapping) {
        String rootName = inferRoot(mapping);
        if (rootName == null) {
            throw new IllegalArgumentException("XML output requires a common target root or targetRoot");
        }
        if (!(data instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("XML output requires an object-shaped mapping result");
        }
        Map<String, Object> document = new LinkedHashMap<>();
        Map<?, ?> mapped = (Map<?, ?>) data;
        if (mapped.containsKey(rootName)) {
            for (Map.Entry<?, ?> entry : mapped.entrySet()) {
                document.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        } else {
            document.put(rootName, data);
        }
        StringBuilder output = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        appendElement(output, rootName, document.get(rootName), 0);
        return output.toString();
    }

    private static String inferRoot(Map<String, Object> mapping) {
        Object configuredRoot = mapping.get("targetRoot");
        if (configuredRoot instanceof String && !((String) configuredRoot).isEmpty()) {
            return (String) configuredRoot;
        }
        Object entriesValue = mapping.get("mappings");
        if (!(entriesValue instanceof List<?>) || ((List<?>) entriesValue).isEmpty()) {
            return null;
        }
        String common = null;
        for (Object item : (List<?>) entriesValue) {
            if (!(item instanceof Map<?, ?>)
                    || !(((Map<?, ?>) item).get("targetPath") instanceof String)) {
                return null;
            }
            String first = ((String) ((Map<?, ?>) item).get("targetPath")).split("\\.", -1)[0];
            if (common == null) {
                common = first;
            } else if (!common.equals(first)) {
                return null;
            }
        }
        return common;
    }

    private static String localName(String name) {
        int colon = name.lastIndexOf(':');
        return colon < 0 ? name : name.substring(colon + 1);
    }

    private static Object elementToValue(XMLStreamReader reader) throws XMLStreamException {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < reader.getAttributeCount(); i++) {
            result.put("@" + localName(reader.getAttributeLocalName(i)), reader.getAttributeValue(i));
        }

        StringBuilder text = new StringBuilder();
        boolean hasChildren = false;
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                hasChildren = true;
                addChild(result, localName(reader.getLocalName()), elementToValue(reader));
            } else if (event == XMLStreamConstants.CHARACTERS
                    || event == XMLStreamConstants.CDATA) {
                text.append(reader.getText());
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                break;
            }
        }
        String value = text.toString().trim();
        if (!hasChildren && result.isEmpty()) {
            return value;
        }
        if (!value.isEmpty()) {
            result.put("#text", value);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static void addChild(Map<String, Object> target, String name, Object value) {
        if (!target.containsKey(name)) {
            target.put(name, value);
        } else if (target.get(name) instanceof List<?>) {
            ((List<Object>) target.get(name)).add(value);
        } else {
            List<Object> values = new ArrayList<>();
            values.add(target.get(name));
            values.add(value);
            target.put(name, values);
        }
    }

    private static void appendElement(StringBuilder output, String name, Object value, int depth) {
        if (!name.matches("[A-Za-z_][A-Za-z0-9_.:-]*")) {
            throw new IllegalArgumentException("Invalid XML element name: " + name);
        }
        if (value instanceof List<?>) {
            for (Object item : (List<?>) value) {
                appendElement(output, name, item, depth);
            }
            return;
        }
        String indent = "  ".repeat(depth);
        if (!(value instanceof Map<?, ?>)) {
            output.append(indent).append('<').append(name).append('>')
                    .append(value == null ? "" : escape(String.valueOf(value), false))
                    .append("</").append(name).append(">\n");
            return;
        }

        Map<?, ?> object = (Map<?, ?>) value;
        List<Map.Entry<?, ?>> children = new ArrayList<>();
        String text = null;
        output.append(indent).append('<').append(name);
        for (Map.Entry<?, ?> entry : object.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if ("#text".equals(key)) {
                text = entry.getValue() == null ? null : String.valueOf(entry.getValue());
            } else if (key.startsWith("@")) {
                String attributeName = key.substring(1);
                if (!attributeName.matches("[A-Za-z_][A-Za-z0-9_.:-]*")) {
                    throw new IllegalArgumentException("Invalid XML attribute name: " + attributeName);
                }
                output.append(' ').append(attributeName).append("=\"")
                        .append(escape(String.valueOf(entry.getValue()), true)).append('"');
            } else {
                children.add(entry);
            }
        }
        if (children.isEmpty() && (text == null || text.isEmpty())) {
            output.append("/>\n");
            return;
        }
        if (children.isEmpty()) {
            output.append('>').append(escape(text == null ? "" : text, false))
                    .append("</").append(name).append(">\n");
            return;
        }
        output.append(">\n");
        if (text != null && !text.isEmpty()) {
            output.append("  ".repeat(depth + 1)).append(escape(text, false)).append('\n');
        }
        for (Map.Entry<?, ?> child : children) {
            appendElement(output, String.valueOf(child.getKey()), child.getValue(), depth + 1);
        }
        output.append(indent).append("</").append(name).append(">\n");
    }

    private static String escape(String value, boolean attribute) {
        String escaped = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        if (attribute) {
            escaped = escaped.replace("\"", "&quot;").replace("'", "&apos;");
        }
        return escaped;
    }
}
