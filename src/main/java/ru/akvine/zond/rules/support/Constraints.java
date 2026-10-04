package ru.akvine.zond.rules.support;

import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import lombok.experimental.UtilityClass;

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

    /**
     * @return true, если на узле есть хотя бы одно ограничение
     */
    public boolean hasAny(NodeWithAnnotations<?> node) {
        return Annotations.hasAny(node, CONSTRAINT_ANNOTATIONS);
    }
}
