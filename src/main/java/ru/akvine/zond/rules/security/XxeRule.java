package ru.akvine.zond.rules.security;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.MethodCalls;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class XxeRule extends AbstractRule {
    private static final Set<String> XML_FACTORIES = Set.of(
            "DocumentBuilderFactory", "SAXParserFactory", "XMLInputFactory", "TransformerFactory", "SchemaFactory");
    private static final Set<String> FACTORY_METHODS = Set.of("newInstance", "newFactory", "newDefaultInstance");
    private static final Set<String> XML_READERS = Set.of("SAXBuilder", "SAXReader");

    // Признаки того, что разбор настроен безопасно: запрет DOCTYPE и внешних сущностей
    private static final List<String> PROTECTION_MARKERS = List.of(
            "disallow-doctype-decl", "external-general-entities", "external-parameter-entities",
            "ACCESS_EXTERNAL_DTD", "ACCESS_EXTERNAL_SCHEMA", "SUPPORT_DTD", "IS_SUPPORTING_EXTERNAL_ENTITIES",
            "FEATURE_SECURE_PROCESSING", "setExpandEntityReferences");

    @Override
    public String code() {
        return RuleCodes.XXE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет разбор XML без запрета внешних сущностей (XXE)";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        // Настройку парсера ищем в том же файле: обычно она стоит рядом с его созданием
        String text = sourceFile.unit().toString();
        if (PROTECTION_MARKERS.stream().anyMatch(text::contains)) {
            return List.of();
        }

        return sourceFile.unit().findAll(Expression.class).stream()
                .flatMap(expression -> describeParser(expression)
                        .map(parser -> violation(sourceFile, expression,
                                "XML-парсер '" + parser + "' создается без запрета внешних сущностей: документ с"
                                        + " DOCTYPE сможет прочитать файлы сервера или обратиться во внутреннюю"
                                        + " сеть (XXE); отключите DOCTYPE: setFeature(\"http://apache.org/xml/"
                                        + "features/disallow-doctype-decl\", true)"))
                        .stream())
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.SECURITY;
    }

    // DocumentBuilderFactory.newInstance(), new SAXBuilder()
    private Optional<String> describeParser(Node node) {
        if (node instanceof MethodCallExpr call && FACTORY_METHODS.contains(call.getNameAsString())) {
            return call.getScope()
                    .map(MethodCalls::receiverName)
                    .filter(XML_FACTORIES::contains)
                    .map(factory -> factory + "." + call.getNameAsString() + "()");
        }
        if (node instanceof Expression expression && expression.isObjectCreationExpr()) {
            String type = expression.asObjectCreationExpr().getType().getNameAsString();
            return XML_READERS.contains(type) ? Optional.of("new " + type + "()") : Optional.empty();
        }
        return Optional.empty();
    }
}
