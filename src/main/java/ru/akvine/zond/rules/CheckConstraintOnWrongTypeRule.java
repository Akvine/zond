package ru.akvine.zond.rules;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.type.Type;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class CheckConstraintOnWrongTypeRule extends AbstractRule {
    private static final String NOT_NULL = "NotNull";
    private static final String ARRAY_SUFFIX = "[]";

    private static final Set<String> PRIMITIVE_TYPES =
            Set.of("int", "long", "short", "byte", "double", "float", "boolean", "char");
    private static final Set<String> NUMBER_TYPES = Set.of(
            "int", "long", "short", "byte", "double", "float",
            "Integer", "Long", "Short", "Byte", "Double", "Float", "BigDecimal", "BigInteger");
    private static final Set<String> TEXT_TYPES = Set.of("String", "CharSequence");
    private static final Set<String> CONTAINER_TYPES = Set.of("List", "Set", "Collection", "Map", "Iterable");
    private static final Set<String> OTHER_TYPES = Set.of(
            "boolean", "Boolean", "LocalDate", "LocalDateTime", "LocalTime", "Instant", "ZonedDateTime",
            "OffsetDateTime", "Date", "UUID");

    // Проверяют текст
    private static final Set<String> TEXT_CONSTRAINTS = Set.of("NotBlank", "Pattern", "Email");

    // Проверяют длину текста либо размер коллекции
    private static final Set<String> SIZE_CONSTRAINTS = Set.of("Size", "NotEmpty");

    // Сравнивают число; строку умеют проверять только @DecimalMin, @DecimalMax и @Digits
    private static final Set<String> NUMBER_CONSTRAINTS =
            Set.of("Min", "Max", "Positive", "PositiveOrZero", "Negative", "NegativeOrZero");

    @Override
    public String code() {
        return RuleCodes.CHECK_CONSTRAINT_ON_WRONG_TYPE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет ограничения валидации на типах, к которым они неприменимы";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (FieldDeclaration field : sourceFile.unit().findAll(FieldDeclaration.class)) {
            report(sourceFile, field, field.getVariable(0).getType(), field.getVariable(0).getNameAsString(), violations);
        }
        for (Parameter parameter : sourceFile.unit().findAll(Parameter.class)) {
            report(sourceFile, parameter, parameter.getType(), parameter.getNameAsString(), violations);
        }
        violations.sort(Comparator.comparingInt(Violation::line));
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }

    private <T extends Node & NodeWithAnnotations<?>> void report(
            SourceFile sourceFile, T node, Type declaredType, String name, List<Violation> violations) {
        String type = LocalTypes.typeName(declaredType);
        for (AnnotationExpr annotation : node.getAnnotations()) {
            String constraint = annotation.getName().getIdentifier();
            describeProblem(constraint, type).ifPresent(problem -> violations.add(violation(sourceFile, node,
                    "@" + constraint + " на '" + name + "' типа " + type + ": " + problem)));
        }
    }

    private Optional<String> describeProblem(String constraint, String type) {
        boolean isNumber = NUMBER_TYPES.contains(type);
        boolean isText = TEXT_TYPES.contains(type);
        boolean isContainer = CONTAINER_TYPES.contains(type) || type.endsWith(ARRAY_SUFFIX);
        boolean isOther = OTHER_TYPES.contains(type);

        if (NOT_NULL.equals(constraint) && PRIMITIVE_TYPES.contains(type)) {
            return Optional.of("примитив не бывает null - если значение не передано, в поле окажется 0 или false,"
                    + " и проверка пройдет; объявите тип оберткой");
        }
        String unsupported = "для этого типа проверки нет, при валидации будет UnexpectedTypeException; ";
        if (TEXT_CONSTRAINTS.contains(constraint) && (isNumber || isContainer || isOther)) {
            return Optional.of(unsupported + "ограничение проверяет только строки");
        }
        if (SIZE_CONSTRAINTS.contains(constraint) && (isNumber || isOther)) {
            return Optional.of(unsupported + "оно проверяет длину строки и размер коллекции, для чисел"
                    + " используйте @Min и @Max");
        }
        if (NUMBER_CONSTRAINTS.contains(constraint) && (isText || isContainer || isOther)) {
            return Optional.of(unsupported + "оно сравнивает числа, для длины строки или размера коллекции"
                    + " используйте @Size");
        }
        return Optional.empty();
    }
}
