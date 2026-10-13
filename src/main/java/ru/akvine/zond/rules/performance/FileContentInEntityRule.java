package ru.akvine.zond.rules.performance;

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
import ru.akvine.zond.rules.support.JpaEntities;
import ru.akvine.zond.rules.support.Queries;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Содержимое файла лежит прямо в поле сущности. Такое поле загружается вместе с сущностью при каждом
 * ее чтении: список из ста записей - это сто файлов в памяти, даже если нужны были только названия.
 */
@Component
public class FileContentInEntityRule extends AbstractRule {
    private static final String LOB = "Lob";
    private static final String BASIC = "Basic";
    private static final String COLUMN = "Column";
    private static final String FETCH = "fetch";
    private static final String LAZY = "LAZY";
    private static final String LENGTH = "length";
    private static final String TRANSIENT = "Transient";
    // Колонка такой длины файл не вместит
    private static final int SMALL_COLUMN_LENGTH = 4096;

    private static final Set<String> BYTE_TYPES = Set.of("byte[]", "Byte[]");
    private static final Set<String> TEXT_TYPES = Set.of("String", "char[]");

    private static final Pattern CONTENT_NAME = Pattern.compile(
            "(?i)(file|content|document|attachment|image|photo|picture|scan|pdf|report|payload|blob|binary"
                    + "|base64|dump|archive|data$)");
    // Двоичные значения, которые файлом не бывают
    private static final Pattern SMALL_NAME = Pattern.compile(
            "(?i)(hash|salt|signature|checksum|digest|key|nonce|token|uuid|guid|fingerprint|thumb|icon|avatar)");

    @Override
    public String code() {
        return RuleCodes.FILE_CONTENT_IN_ENTITY_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет сущности, в поле которых хранится содержимое файла (@Lob, byte[])";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (ClassOrInterfaceDeclaration entity : JpaEntities.findEntities(sourceFile.unit())) {
            for (FieldDeclaration field : entity.getFields()) {
                if (field.isStatic() || field.isTransient() || Annotations.has(field, TRANSIENT) || isLazy(field)) {
                    continue;
                }
                for (VariableDeclarator variable : field.getVariables()) {
                    confidence(field, variable).ifPresent(confidence -> violations.add(
                            violation(sourceFile, variable, message(entity, variable)).withConfidence(confidence)));
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
        return ErrorType.PERFORMANCE;
    }

    private Optional<Confidence> confidence(FieldDeclaration field, VariableDeclarator variable) {
        String type = variable.getType().asString();
        String name = variable.getNameAsString();
        boolean lob = Annotations.has(field, LOB);
        if (SMALL_NAME.matcher(name).find() || isSmallColumn(field)) {
            return Optional.empty();
        }
        if (BYTE_TYPES.contains(type)) {
            // byte[] без @Lob - это и короткое двоичное значение тоже: без подсказки в названии не судим
            if (lob) {
                return Optional.of(Confidence.PROBABLE);
            }
            return CONTENT_NAME.matcher(name).find() ? Optional.of(Confidence.SUSPICION) : Optional.empty();
        }
        // @Lob String - чаще длинный текст (описание, JSON), чем файл
        boolean textFile = lob && TEXT_TYPES.contains(type) && CONTENT_NAME.matcher(name).find();
        return textFile ? Optional.of(Confidence.SUSPICION) : Optional.empty();
    }

    // @Basic(fetch = FetchType.LAZY): содержимое загружается только при обращении к полю
    private boolean isLazy(FieldDeclaration field) {
        return Annotations.find(field, BASIC)
                .flatMap(basic -> Queries.member(basic, FETCH))
                .filter(fetch -> fetch.toString().endsWith(LAZY))
                .isPresent();
    }

    private boolean isSmallColumn(FieldDeclaration field) {
        Optional<AnnotationExpr> column = Annotations.find(field, COLUMN);
        return column.flatMap(annotation -> Queries.member(annotation, LENGTH))
                .filter(length -> length.isIntegerLiteralExpr()
                        && length.asIntegerLiteralExpr().asNumber().intValue() <= SMALL_COLUMN_LENGTH)
                .isPresent();
    }

    private static String message(ClassOrInterfaceDeclaration entity, VariableDeclarator variable) {
        return "Поле '" + variable.getNameAsString() + "' хранит содержимое файла прямо в сущности '"
                + entity.getNameAsString() + "': оно загружается при каждом ее чтении, и список из ста записей -"
                + " это сто файлов в памяти; вынесите содержимое в отдельную сущность с ленивой связью"
                + " (@OneToOne(fetch = LAZY)) либо читайте его отдельным запросом, а списки стройте проекцией"
                + " без этого поля";
    }
}
