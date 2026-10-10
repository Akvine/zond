package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ConfigFile;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractContextRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Queries;
import ru.akvine.zond.rules.support.StringLiterals;

import java.util.ArrayList;
import java.util.List;

/**
 * Режим подтверждения NONE у слушателя RabbitMQ: сообщение считается доставленным в момент отправки
 * слушателю. Режим задают и настройкой, и в коде - поэтому правилу нужны и файлы настроек, и исходники.
 */
@Component
public class RabbitAcknowledgeModeNoneRule extends AbstractContextRule {
    private static final List<String> PROPERTIES = List.of(
            "spring.rabbitmq.listener.simple.acknowledge-mode", "spring.rabbitmq.listener.direct.acknowledge-mode");
    private static final String NONE = "NONE";
    private static final String SET_ACKNOWLEDGE_MODE = "setAcknowledgeMode";
    private static final String RABBIT_LISTENER = "RabbitListener";
    private static final String ACK_MODE = "ackMode";
    private static final String MESSAGE = "режим подтверждения NONE: брокер удаляет сообщение из очереди в тот момент,"
            + " когда отдает его слушателю, не дожидаясь обработки, - если слушатель упадет или приложение"
            + " остановится, сообщение потеряно без следа; используйте AUTO (режим по умолчанию: подтверждение"
            + " после успешной обработки) или MANUAL";

    @Override
    public String code() {
        return RuleCodes.RABBIT_ACKNOWLEDGE_MODE_NONE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует настройки и код и ищет слушателей RabbitMQ с режимом подтверждения NONE";
    }

    // Режим записан в настройке или в коде как есть: гадать тут не о чем
    @Override
    public Confidence confidence() {
        return Confidence.CONFIRMED;
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        List<Violation> violations = new ArrayList<>();
        // Настройки тестов и разработки в рабочую среду не попадают
        context.configFiles().stream()
                .filter(file -> !file.isNonProduction())
                .forEach(file -> checkSettings(file, violations));
        context.sources().forEach(sourceFile -> checkCode(sourceFile, violations));
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

    private void checkSettings(ConfigFile file, List<Violation> violations) {
        for (String key : PROPERTIES) {
            file.find(key)
                    .filter(property -> property.value() != null && NONE.equalsIgnoreCase(property.value().trim()))
                    .ifPresent(property -> violations.add(violation(file.path(), property.line(),
                            property.key() + "=" + property.value().trim() + " - " + MESSAGE)));
        }
    }

    private void checkCode(SourceFile sourceFile, List<Violation> violations) {
        // factory.setAcknowledgeMode(AcknowledgeMode.NONE)
        sourceFile.unit().findAll(MethodCallExpr.class).stream()
                .filter(call -> SET_ACKNOWLEDGE_MODE.equals(call.getNameAsString()) && call.getArguments().size() == 1)
                .filter(call -> call.getArgument(0).toString().endsWith(NONE))
                .forEach(call -> violations.add(violation(sourceFile, call.getName(), "У слушателей задан " + MESSAGE)));
        // @RabbitListener(queues = "orders", ackMode = "NONE")
        sourceFile.unit().findAll(AnnotationExpr.class).stream()
                .filter(annotation -> RABBIT_LISTENER.equals(annotation.getName().getIdentifier()))
                .filter(annotation -> Queries.member(annotation, ACK_MODE)
                        .flatMap(StringLiterals::textOf)
                        .filter(NONE::equalsIgnoreCase)
                        .isPresent())
                .forEach(annotation -> violations.add(violation(sourceFile, annotation, "У слушателя задан " + MESSAGE)));
    }
}
