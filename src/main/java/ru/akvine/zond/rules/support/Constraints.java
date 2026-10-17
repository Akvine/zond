package ru.akvine.zond.rules.support;

import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import lombok.experimental.UtilityClass;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Ограничения Bean Validation: @NotNull, @Size и подобные
 */
@UtilityClass
public class Constraints {
    public static final String VALID = "Valid";
    public static final String VALIDATED = "Validated";

    private static final Set<String> CONSTRAINT_ANNOTATIONS = Set.of(
            "NotNull", "NotBlank", "NotEmpty", "Null", "Size", "Min", "Max", "DecimalMin", "DecimalMax", "Digits",
            "Positive", "PositiveOrZero", "Negative", "NegativeOrZero", "Pattern", "Email", "Past", "PastOrPresent",
            "Future", "FutureOrPresent", "AssertTrue", "AssertFalse", "Length", "Range", "URL");

    private static final List<String> VALIDATION_PACKAGES =
            List.of("jakarta.validation.", "javax.validation.", "org.hibernate.validator.");

    /**
     * @return true, если на узле есть хотя бы одно ограничение
     */
    public boolean hasAny(NodeWithAnnotations<?> node) {
        return node.getAnnotations().stream()
                .anyMatch(annotation -> CONSTRAINT_ANNOTATIONS.contains(annotation.getName().getIdentifier())
                        && isBeanValidation(annotation));
    }

    /**
     * Аннотация из Bean Validation, а не одноименная подсказка для IDE и анализаторов:
     * {@code org.jetbrains.annotations.NotNull}, {@code lombok.NonNull}, {@code javax.annotation.Nonnull}
     * ничего не проверяют во время работы. Если откуда аннотация, не видно (без импорта), считаем ее ограничением.
     */
    public boolean isBeanValidation(AnnotationExpr annotation) {
        if (annotation.getName().getQualifier().isPresent()) {
            return isValidationPackage(annotation.getNameAsString());
        }
        String name = annotation.getName().getIdentifier();
        Optional<ImportDeclaration> declared = annotation.findCompilationUnit()
                .flatMap(unit -> unit.getImports().stream()
                        .filter(declaration -> !declaration.isAsterisk() && !declaration.isStatic())
                        .filter(declaration -> declaration.getName().getIdentifier().equals(name))
                        .findFirst());
        return declared.map(declaration -> isValidationPackage(declaration.getNameAsString())).orElse(true);
    }

    private boolean isValidationPackage(String qualifiedName) {
        return VALIDATION_PACKAGES.stream().anyMatch(qualifiedName::startsWith);
    }
}
