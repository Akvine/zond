package ru.akvine.zond.rules;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class CheckDuplicateStringLiteralRule extends AbstractRule {
    private static final int MIN_OCCURRENCES = 3;

    // Короткие строки вроде "", ", " и "id" повторяются естественно
    private static final int MIN_LENGTH = 5;

    @Override
    public String code() {
        return RuleCodes.CHECK_DUPLICATE_STRING_LITERAL_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет строковые литералы, повторенные в файле " + MIN_OCCURRENCES + " раза и больше";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        Map<String, List<StringLiteralExpr>> occurrences = new LinkedHashMap<>();
        for (StringLiteralExpr literal : sourceFile.unit().findAll(StringLiteralExpr.class)) {
            if (literal.asString().length() >= MIN_LENGTH && !isExcluded(literal)) {
                occurrences.computeIfAbsent(literal.asString(), key -> new ArrayList<>()).add(literal);
            }
        }

        return occurrences.entrySet().stream()
                .filter(entry -> entry.getValue().size() >= MIN_OCCURRENCES)
                .map(entry -> violation(sourceFile, entry.getValue().get(0),
                        "Строка \"" + entry.getKey() + "\" повторяется " + entry.getValue().size() + " раз(а):"
                                + " при изменении легко поправить не все места, а опечатку в одном из них"
                                + " компилятор не заметит; вынесите строку в константу"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.CODE_SMELL;
    }

    // Аннотации и сами объявления констант не считаем; в тестах повторы строк - обычное дело
    private boolean isExcluded(StringLiteralExpr literal) {
        Node current = literal.getParentNode().orElse(null);
        while (current != null) {
            if (current instanceof AnnotationExpr
                    || (current instanceof FieldDeclaration field && field.isStatic() && field.isFinal())) {
                return true;
            }
            current = current.getParentNode().orElse(null);
        }
        return TestClasses.isInside(literal);
    }
}
