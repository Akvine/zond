package ru.akvine.zond.rules.codesmell;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.DoubleLiteralExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LiteralStringValueExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithArguments;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class MagicNumberRule extends AbstractRule {
    private static final String HASH_CODE = "hashCode";

    private static final Pattern UNIT_CONSTANT = Pattern.compile("(TimeUnit|ChronoUnit|DataUnit)\\.[A-Z_]+");
    private static final Pattern UNIT_FACTORY = Pattern.compile("^(Duration|Period|DataSize)\\.of[A-Z]\\w*\\(");

    // Числа, смысл которых понятен без имени
    private static final Set<Double> ALLOWED = Set.of(0.0, 1.0, 2.0);

    @Override
    public String code() {
        return RuleCodes.MAGIC_NUMBER_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет числа без имени (магические числа) в выражениях";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<LiteralStringValueExpr> literals = new ArrayList<>();
        literals.addAll(sourceFile.unit().findAll(IntegerLiteralExpr.class));
        literals.addAll(sourceFile.unit().findAll(LongLiteralExpr.class));
        literals.addAll(sourceFile.unit().findAll(DoubleLiteralExpr.class));

        return literals.stream()
                .filter(literal -> !isAllowed(literal.getValue()))
                .filter(literal -> !isNamed(literal))
                .filter(literal -> !isInIgnoredContext(literal))
                .map(literal -> violation(sourceFile, literal,
                        "Магическое число " + literal.getValue() + ": без имени непонятно, что оно означает;"
                                + " вынесите его в константу"))
                .sorted((left, right) -> Integer.compare(left.line(), right.line()))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.INFO;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.CODE_SMELL;
    }

    private boolean isAllowed(String literal) {
        try {
            return ALLOWED.contains(Double.parseDouble(literal.replace("_", "").replaceAll("[lLfFdD]$", "")));
        } catch (NumberFormatException exception) {
            // 0xFF, 0b1010: битовые маски и флаги
            return true;
        }
    }

    // int timeout = 5000; - у числа уже есть имя, это имя переменной
    private boolean isNamed(Node literal) {
        Node parent = literal.getParentNode().orElse(null);
        while (parent instanceof UnaryExpr || parent instanceof EnclosedExpr) {
            parent = parent.getParentNode().orElse(null);
        }
        return parent instanceof VariableDeclarator;
    }

    // Константы и поля, аннотации, enum, hashCode и тесты: там числа либо уже названы, либо уместны как есть
    private boolean isInIgnoredContext(Node literal) {
        Node current = literal.getParentNode().orElse(null);
        while (current != null) {
            if (current instanceof FieldDeclaration
                    || current instanceof AnnotationExpr
                    || current instanceof EnumConstantDeclaration
                    || (current instanceof MethodDeclaration method && HASH_CODE.equals(method.getNameAsString()))) {
                return true;
            }
            current = current.getParentNode().orElse(null);
        }
        return TestClasses.isInside(literal) || hasUnit(literal);
    }

    // Duration.ofSeconds(30), poll(5, TimeUnit.SECONDS): смысл числа назван единицей измерения рядом
    private boolean hasUnit(Node literal) {
        Node parent = literal.getParentNode().orElse(null);
        while (parent instanceof UnaryExpr || parent instanceof EnclosedExpr) {
            parent = parent.getParentNode().orElse(null);
        }
        if (parent instanceof MethodCallExpr call && UNIT_FACTORY.matcher(call.toString()).find()) {
            return true;
        }
        return parent instanceof NodeWithArguments<?> call && call.getArguments().stream()
                .anyMatch(argument -> UNIT_CONSTANT.matcher(argument.toString()).matches());
    }
}
