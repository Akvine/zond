package ru.akvine.zond.rules;

import com.github.javaparser.ast.expr.MethodCallExpr;
import lombok.experimental.UtilityClass;

import java.util.Set;
import java.util.regex.Pattern;

@UtilityClass
class Loggers {
    private final static Set<String> LEVELS = Set.of("trace", "debug", "info", "warn", "error");

    // Уровни, которые обычно включены в рабочей среде
    private final static Set<String> ENABLED_LEVELS = Set.of("info", "warn", "error");

    // Типы не разрешаем, поэтому логгер узнаем по имени: log, logger, LOG, LOGGER, auditLogger
    private final static Pattern LOGGER_NAME = Pattern.compile("^(log|logger|LOG|LOGGER)$|.*(Logger|Log)$");

    /**
     * @return true для вызова вида log.info(...)
     */
    boolean isLogCall(MethodCallExpr call) {
        return LEVELS.contains(call.getNameAsString())
                && call.getScope()
                .filter(scope -> LOGGER_NAME.matcher(MethodCalls.receiverName(scope)).matches())
                .isPresent();
    }

    boolean isEnabledLevel(MethodCallExpr call) {
        return ENABLED_LEVELS.contains(call.getNameAsString());
    }
}
