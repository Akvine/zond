package ru.akvine.zond.rules.security;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Данные банковской карты в хранимом объекте. Полный номер карты стандарт PCI DSS разрешает хранить только в
 * нечитаемом виде - зашифрованным, усеченным либо замененным на токен; проверочный код (CVV, CVC) после
 * проведения платежа хранить нельзя вовсе. Строковое поле {@code fullPan} рядом с {@code maskedPan} и без
 * конвертера означает, что номер лежит в базе открытым текстом - и попадает в резервные копии, выгрузки и,
 * через {@code toString()}, в журналы.
 */
@Component
public class CardDataInClearRule extends AbstractRule {
    private static final Set<String> STORED = Set.of("Entity", "Embeddable", "MappedSuperclass", "Document", "Table");
    private static final String STRING = "String";
    // Проверочный код карты: cvv, cvc2, cardSecurityCode, cardVerificationValue
    private static final Pattern VERIFICATION_CODE = Pattern.compile(
            "(?i)(card)?(cvv|cvc|cvn|cid)2?|card(Security|Verification)(Code|Value)");
    // Номер карты: pan, fullPan, cardPan, cardNumber, cardNo, primaryAccountNumber
    private static final Pattern CARD_NUMBER = Pattern.compile(
            "(?i)(full|card|plain|clear|raw|open)?_?pan(full|number)?|(full|plain|raw)?_?card_?(number|no|num)"
                    + "|primary_?account_?number");
    // Имя прямо говорит, что номер целый
    private static final Pattern FULL_NUMBER = Pattern.compile("(?i).*(full|plain|clear|raw|open).*");
    // Соседнее поле с маской: значит, в этом лежит номер без маски
    private static final Pattern MASKED_SIBLING =
            Pattern.compile("(?i).*mask.*(pan|card|number).*|.*(pan|card|number).*mask.*");
    // Поле шифрует или подменяет конвертер, преобразование колонки либо свой тип
    private static final Pattern PROTECTION =
            Pattern.compile("(?i).*(convert|crypt|cipher|transformer|token|mask|protect).*|Type");
    private static final Set<String> TO_STRING = Set.of("Data", "ToString", "Value");
    private static final String EXCLUDE = "ToString.Exclude";

    @Override
    public String code() {
        return RuleCodes.CARD_DATA_IN_CLEAR_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует хранимые классы (сущности JPA, документы) и ищет полный номер карты и проверочный код"
                + " (CVV) в открытых строковых полях";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (ClassOrInterfaceDeclaration type : sourceFile.findAll(ClassOrInterfaceDeclaration.class)) {
            if (!Annotations.hasAny(type, STORED) || TestClasses.isInside(type)) {
                continue;
            }
            boolean hasMask = type.getFields().stream()
                    .flatMap(field -> field.getVariables().stream())
                    .anyMatch(variable -> MASKED_SIBLING.matcher(variable.getNameAsString()).matches());
            for (FieldDeclaration field : type.getFields()) {
                if (field.isStatic() || isProtected(field)) {
                    continue;
                }
                for (VariableDeclarator variable : field.getVariables()) {
                    check(sourceFile, type, field, variable, hasMask, violations);
                }
            }
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.SECURITY;
    }

    private void check(SourceFile sourceFile, ClassOrInterfaceDeclaration type, FieldDeclaration field,
                       VariableDeclarator variable, boolean hasMask, List<Violation> violations) {
        String name = variable.getNameAsString();
        if (VERIFICATION_CODE.matcher(name).matches()) {
            violations.add(violation(sourceFile, variable,
                    "Проверочный код карты хранится в поле '" + name + "' класса '" + type.getNameAsString() + "':"
                            + " стандарт PCI DSS запрещает хранить CVV / CVC после проведения платежа - даже"
                            + " зашифрованным; код должен жить только на время одного запроса к платежному шлюзу"));
            return;
        }
        if (!STRING.equals(LocalTypes.typeName(variable.getType())) || !CARD_NUMBER.matcher(name).matches()) {
            return;
        }
        String exposure = isPrinted(type, field)
                ? " К тому же Lombok включает поле в toString(): первая запись объекта в лог раскроет номер."
                : "";
        Violation violation = violation(sourceFile, variable,
                "Номер карты хранится в поле '" + name + "' класса '" + type.getNameAsString() + "' строкой, без"
                        + " шифрования на стороне приложения: стандарт PCI DSS требует, чтобы номер в хранилище"
                        + " был нечитаем, - иначе он целиком попадает в резервные копии, выгрузки и к каждому, у"
                        + " кого есть доступ к базе. Храните маску и токен платежного шлюза, а если полный номер"
                        + " действительно нужен - шифруйте поле конвертером (@Convert)." + exposure);
        // Поле с маской рядом либо слово full в имени: в этом поле номер лежит целиком. Иначе в нем может
        // оказаться и маска, и токен - по имени этого не узнать
        boolean isFull = hasMask || FULL_NUMBER.matcher(name).matches();
        violations.add(isFull ? violation : violation.withConfidence(Confidence.SUSPICION));
    }

    // @Convert(converter = CardNumberEncryptor.class), @ColumnTransformer(read = "decrypt(...)"), @Encrypted
    private boolean isProtected(FieldDeclaration field) {
        return field.getAnnotations().stream()
                .map(AnnotationExpr::getNameAsString)
                .anyMatch(name -> PROTECTION.matcher(name).matches());
    }

    private boolean isPrinted(ClassOrInterfaceDeclaration type, FieldDeclaration field) {
        return Annotations.hasAny(type, TO_STRING) && field.getAnnotations().stream()
                .noneMatch(annotation -> EXCLUDE.equals(annotation.getNameAsString()));
    }
}
