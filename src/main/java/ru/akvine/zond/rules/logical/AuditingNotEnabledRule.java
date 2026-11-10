package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.ProjectClasses;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Поля аудита, которые никто не заполняет. @CreatedDate, @LastModifiedDate, @CreatedBy и @LastModifiedBy сами
 * ничего не делают: в JPA их заполняет AuditingEntityListener, подключенный к сущности, и только если аудит
 * включен через @EnableJpaAuditing. Без любого из двух условий поля молча остаются пустыми.
 */
@Component
public class AuditingNotEnabledRule extends AbstractRule implements ProjectRule {
    private static final Set<String> AUDIT_FIELDS = Set.of("CreatedDate", "LastModifiedDate", "CreatedBy", "LastModifiedBy");
    private static final Set<String> AUTHOR_FIELDS = Set.of("CreatedBy", "LastModifiedBy");
    private static final Pattern ENABLE_AUDITING = Pattern.compile("^Enable\\w*Auditing$");
    private static final Set<String> JPA_CLASSES = Set.of("Entity", "MappedSuperclass");
    private static final String ENTITY_LISTENERS = "EntityListeners";
    private static final String AUDITING_LISTENER = "AuditingEntityListener";
    private static final String AUDITOR_AWARE = "AuditorAware";
    // Настройка приложения попала в проверку - значит, отсутствие @Enable... не случайность выборки файлов
    private static final Set<String> CONFIGURATION_ANNOTATIONS = Set.of("SpringBootApplication", "Configuration");

    @Override
    public String code() {
        return RuleCodes.AUDITING_NOT_ENABLED_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует проект и ищет поля @CreatedDate, @LastModifiedDate, @CreatedBy, которые останутся пустыми:"
                + " нет @EnableJpaAuditing, AuditingEntityListener у сущности либо бина AuditorAware";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        ProjectClasses classes = ProjectClasses.of(sourceFiles);
        Set<String> typeAnnotations = new HashSet<>();
        boolean auditorKnown = false;
        for (SourceFile sourceFile : sourceFiles) {
            for (TypeDeclaration<?> type : sourceFile.unit().findAll(TypeDeclaration.class)) {
                type.getAnnotations().forEach(annotation -> typeAnnotations.add(annotation.getName().getIdentifier()));
            }
            auditorKnown |= sourceFile.unit().toString().contains(AUDITOR_AWARE);
        }
        List<Violation> violations = new ArrayList<>();
        if (typeAnnotations.stream().noneMatch(CONFIGURATION_ANNOTATIONS::contains)) {
            return violations;
        }
        boolean enabled = typeAnnotations.stream().anyMatch(annotation -> ENABLE_AUDITING.matcher(annotation).matches());

        // О том, что аудит не включен вовсе и что некому назвать автора, сообщаем один раз - на первом поле
        boolean enableReported = false;
        boolean auditorReported = false;
        for (SourceFile sourceFile : sourceFiles) {
            for (ClassOrInterfaceDeclaration type : sourceFile.unit().findAll(ClassOrInterfaceDeclaration.class)) {
                Optional<FieldDeclaration> audited = firstField(type, AUDIT_FIELDS);
                if (audited.isEmpty()) {
                    continue;
                }
                if (!enabled && !enableReported) {
                    enableReported = true;
                    violations.add(violation(sourceFile, audited.get(),
                            "@" + annotationOf(audited.get()) + " в классе '" + type.getNameAsString() + "' не работает:"
                                    + " в проекте нет @EnableJpaAuditing (для MongoDB - @EnableMongoAuditing), и"
                                    + " Spring Data поля аудита не заполняет - они молча остаются пустыми; добавьте"
                                    + " аннотацию на класс настроек"));
                } else if (enabled && Annotations.hasAny(type, JPA_CLASSES) && !hasListener(type, classes)) {
                    violations.add(violation(sourceFile, audited.get(),
                            "@" + annotationOf(audited.get()) + " в сущности '" + type.getNameAsString() + "' не"
                                    + " работает: у сущности и ее предков нет"
                                    + " @EntityListeners(AuditingEntityListener.class), а без этого слушателя JPA поля"
                                    + " аудита не заполняет - они молча остаются пустыми"));
                }
                Optional<FieldDeclaration> author = firstField(type, AUTHOR_FIELDS);
                if (enabled && author.isPresent() && !auditorKnown && !auditorReported) {
                    auditorReported = true;
                    violations.add(authorViolation(sourceFile, type, author.get()));
                }
            }
        }
        return violations;
    }

    private Violation authorViolation(SourceFile sourceFile, ClassOrInterfaceDeclaration type, FieldDeclaration author) {
        return violation(sourceFile, author,
                "Поле автора в классе '" + type.getNameAsString() + "' останется пустым: в проекте нет"
                        + " бина AuditorAware, и Spring Data неоткуда узнать, кто делает изменение;"
                        + " объявите бин AuditorAware<...>, который возвращает текущего пользователя")
                .withLevel(ErrorLevel.MINOR);
    }

    private Optional<FieldDeclaration> firstField(ClassOrInterfaceDeclaration type, Set<String> annotations) {
        return type.getFields().stream().filter(field -> Annotations.hasAny(field, annotations)).findFirst();
    }

    private String annotationOf(FieldDeclaration field) {
        return Annotations.find(field, AUDIT_FIELDS).map(found -> found.getName().getIdentifier()).orElse("");
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }

    // Слушатель может стоять на самой сущности либо на предке; о предке вне проекта судить нельзя
    private boolean hasListener(ClassOrInterfaceDeclaration entity, ProjectClasses classes) {
        if (classes.hasUnknownAncestor(entity)) {
            return true;
        }
        Optional<ClassOrInterfaceDeclaration> current = Optional.of(entity);
        while (current.isPresent()) {
            boolean listens = Annotations.find(current.get(), ENTITY_LISTENERS)
                    .filter(annotation -> annotation.toString().contains(AUDITING_LISTENER))
                    .isPresent();
            if (listens) {
                return true;
            }
            current = classes.parent(current.get());
        }
        return false;
    }
}
