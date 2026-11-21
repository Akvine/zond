package ru.akvine.zond.services;

import com.github.javaparser.ast.body.TypeDeclaration;
import lombok.experimental.UtilityClass;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.support.ProjectUsages;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Находки в коде, который не выполняется: класс нигде не используется либо бин никто не вызывает. Ошибка поведения
 * в таком классе проявиться не может, пока его не начнут вызывать, поэтому она остается подозрением: главное
 * сообщение о классе - что он не нужен - дает jr:206. Секреты и замечания о качестве кода сюда не относятся:
 * пароль в неиспользуемом классе лежит в хранилище так же, как в используемом
 */
@UtilityClass
class DeadCode {
    private final static Set<ErrorType> BEHAVIOUR = EnumSet.of(
            ErrorType.LOGICAL, ErrorType.CONCURRENCY, ErrorType.PERFORMANCE, ErrorType.RESOURCE, ErrorType.EXCEPTION,
            ErrorType.STREAM, ErrorType.DATE_AND_TIME);
    // По одному файлу не понять, пользуются ли его классами остальные
    private final static int MIN_FILES = 2;

    /**
     * Строки класса, который не используется
     */
    private record Range(String type, int begin, int end) {
    }

    /**
     * @param parts независимые части проверяемой папки: используется ли класс, решается внутри его части
     * @return те же находки; у ошибок поведения в неиспользуемых классах уверенность снижена до подозрения
     */
    List<Violation> demote(List<Violation> violations, List<ScanContext> parts) {
        Map<Path, List<Range>> dead = new HashMap<>();
        for (ScanContext part : parts) {
            if (part.sources().size() < MIN_FILES) {
                continue;
            }
            ProjectUsages usages = ProjectUsages.of(part.sources());
            for (SourceFile source : part.sources()) {
                for (TypeDeclaration<?> type : source.unit().findAll(TypeDeclaration.class)) {
                    boolean unused = usages.isTypeDead(type) && !usages.isModuleApi(type) || usages.isIdleBean(type);
                    if (unused && type.getRange().isPresent()) {
                        int begin = type.getRange().get().begin.line;
                        int end = type.getRange().get().end.line;
                        dead.computeIfAbsent(source.path(), path -> new ArrayList<>())
                                .add(new Range(type.getNameAsString(), begin, end));
                    }
                }
            }
        }
        // Всегда новый список: вызывающий очищает свой и переносит в него результат
        return violations.stream().map(violation -> demote(violation, dead)).toList();
    }

    private Violation demote(Violation violation, Map<Path, List<Range>> dead) {
        if (!BEHAVIOUR.contains(violation.errorType())
                || !violation.confidenceOrDefault().isAtLeast(Confidence.PROBABLE)) {
            return violation;
        }
        for (Range range : dead.getOrDefault(violation.file(), List.of())) {
            if (violation.line() >= range.begin() && violation.line() <= range.end()) {
                return new Violation(
                        violation.errorLevel(), violation.errorType(), violation.ruleCode(), violation.ruleName(),
                        violation.file(), violation.line(),
                        violation.message() + " [класс '" + range.type() + "' в проекте не используется, поэтому"
                                + " ошибка пока проявиться не может: см. находку jr:206 на самом классе]",
                        Confidence.SUSPICION);
            }
        }
        return violation;
    }
}
