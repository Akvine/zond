package ru.akvine.zond.parsers;

import lombok.experimental.UtilityClass;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Разбор XML потоковым парсером из JDK: только он сообщает номера строк, которые нужны для отчета
 */
@UtilityClass
public class XmlParser {
    private static final String LINE_SEPARATOR = "\n";
    private static final String EXTERNAL_DTD = "http://apache.org/xml/features/nonvalidating/load-external-dtd";
    private static final String EXTERNAL_GENERAL_ENTITIES = "http://xml.org/sax/features/external-general-entities";
    private static final String EXTERNAL_PARAMETER_ENTITIES = "http://xml.org/sax/features/external-parameter-entities";

    /**
     * @return корневой элемент либо пусто, если файл - не корректный XML
     */
    public Optional<XmlElement> parse(List<String> lines) {
        try {
            // Проверяемый файл - чужой: внешние DTD и сущности не загружаем
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setFeature(EXTERNAL_DTD, false);
            factory.setFeature(EXTERNAL_GENERAL_ENTITIES, false);
            factory.setFeature(EXTERNAL_PARAMETER_ENTITIES, false);

            TreeBuilder builder = new TreeBuilder();
            factory.newSAXParser().parse(new InputSource(new StringReader(String.join(LINE_SEPARATOR, lines))), builder);
            return Optional.ofNullable(builder.root);
        } catch (SAXException | ParserConfigurationException | IOException | RuntimeException exception) {
            return Optional.empty();
        }
    }

    /**
     * Элемент, который еще читается: текст и вложенные элементы дополняются по ходу разбора
     */
    private static final class Draft {
        private final String name;
        private final Map<String, String> attributes;
        private final int line;
        private final StringBuilder text = new StringBuilder();
        private final List<XmlElement> children = new ArrayList<>();

        private Draft(String name, Map<String, String> attributes, int line) {
            this.name = name;
            this.attributes = attributes;
            this.line = line;
        }
    }

    private static final class TreeBuilder extends DefaultHandler {
        private final Deque<Draft> open = new ArrayDeque<>();
        private Locator locator;
        private XmlElement root;

        @Override
        public void setDocumentLocator(Locator documentLocator) {
            this.locator = documentLocator;
        }

        @Override
        public InputSource resolveEntity(String publicId, String systemId) {
            return new InputSource(new StringReader(""));
        }

        @Override
        public void startElement(String uri, String localName, String qualifiedName, Attributes attributes) {
            Map<String, String> values = new LinkedHashMap<>();
            for (int index = 0; index < attributes.getLength(); index++) {
                values.put(withoutPrefix(attributes.getQName(index)), attributes.getValue(index));
            }
            open.push(new Draft(withoutPrefix(qualifiedName), values, locator == null ? 1 : locator.getLineNumber()));
        }

        @Override
        public void characters(char[] symbols, int start, int length) {
            if (!open.isEmpty()) {
                open.peek().text.append(symbols, start, length);
            }
        }

        @Override
        public void endElement(String uri, String localName, String qualifiedName) {
            Draft draft = open.pop();
            XmlElement element = new XmlElement(
                    draft.name, draft.attributes, draft.text.toString().trim(), draft.line, List.copyOf(draft.children));
            if (open.isEmpty()) {
                root = element;
            } else {
                open.peek().children.add(element);
            }
        }

        // xsi:schemaLocation -> schemaLocation
        private String withoutPrefix(String name) {
            return name.substring(name.indexOf(':') + 1);
        }
    }
}
