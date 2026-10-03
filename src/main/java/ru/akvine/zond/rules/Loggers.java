package ru.akvine.zond.rules;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import lombok.experimental.UtilityClass;

import java.util.Set;
import java.util.regex.Pattern;

@UtilityClass
class Loggers {
    private final static Set<String> LEVELS = Set.of("trace", "debug", "info", "warn", "error");

    // Уровни, которые обычно включены в рабочей среде
    private final static Set<String> ENABLED_LEVELS = Set.of("info", "warn", "error");

    // Интерфейсы логгеров: slf4j, log4j, java.util.logging, commons-logging
    private final static Set<String> LOGGER_TYPES = Set.of("Logger", "Log");

    // Если тип неизвестен (поле log от @Slf4j появляется только при компиляции), логгер узнаем по имени:
    // log, logger, LOG, LOGGER, auditLogger
    private final static Pattern LOGGER_NAME = Pattern.compile("^(log|logger|LOG|LOGGER)$|.*(Logger|Log)$");

    /**
     * @return true для вызова вида log.info(...)
     */
    boolean isLogCall(MethodCallExpr call) {
        return LEVELS.contains(call.getNameAsString()) && call.getScope().filter(Loggers::isLogger).isPresent();
    }

    boolean isEnabledLevel(MethodCallExpr call) {
        return ENABLED_LEVELS.contains(call.getNameAsString());
    }

    private boolean isLogger(Expression scope) {
        return Types.matches(scope, LOGGER_TYPES::contains)
                .orElseGet(() -> LOGGER_NAME.matcher(MethodCalls.receiverName(scope)).matches());
    }
}
