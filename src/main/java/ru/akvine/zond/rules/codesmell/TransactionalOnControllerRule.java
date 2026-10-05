package ru.akvine.zond.rules.codesmell;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.Rule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.TransactionalAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public class TransactionalOnControllerRule implements Rule {
    private static final Set<String> CONTROLLER_ANNOTATIONS = Set.of("Controller", "RestController");

    @Override
    public String name() {
        return getClass().getSimpleName();
    }

    @Override
    public String code() {
        return RuleCodes.TRANSACTIONAL_ON_CONTROLLER_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет @Transactional над контроллерами и их методами";
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (ClassOrInterfaceDeclaration type : sourceFile.unit().findAll(ClassOrInterfaceDeclaration.class)) {
            if (!isController(type)) {
                continue;
            }

            if (TransactionalAnnotations.isPresent(type)) {
                violations.add(violation(sourceFile, type,
                        "@Transactional над контроллером '" + type.getNameAsString() + "'"));
            }

            // Приватные не учитываем: на них @Transactional не работает в принципе, это ловит отдельное правило
            type.getMethods().stream()
                    .filter(method -> !method.isPrivate())
                    .filter(TransactionalAnnotations::isPresent)
                    .forEach(method -> violations.add(violation(sourceFile, method,
                            "@Transactional над методом '" + method.getNameAsString()
                                    + "' контроллера '" + type.getNameAsString() + "'")));
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.CODE_SMELL;
    }

    // Сравниваем по простому имени, чтобы поймать и короткую, и полную запись аннотации
    private boolean isController(ClassOrInterfaceDeclaration type) {
        return type.getAnnotations().stream()
                .anyMatch(annotation -> CONTROLLER_ANNOTATIONS.contains(annotation.getName().getIdentifier()));
    }

    private Violation violation(SourceFile sourceFile, Node node, String subject) {
        return new Violation(
                errorLevel(),
                errorType(),
                code(),
                name(),
                sourceFile.path(),
                node.getBegin().map(position -> position.line).orElse(0),
                subject + ": границы транзакции должны задаваться в сервисном слое, иначе транзакция"
                        + " охватывает разбор запроса и формирование ответа");
    }
}
