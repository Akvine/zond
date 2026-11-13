package ru.akvine.zond.services;

import ru.akvine.zond.rules.Rule;

/**
 * Правило упало при проверке. В сообщении названы правило, вид ошибки и место: по исходной ошибке
 * не видно, какое правило работало, а текста у нее может не быть вовсе
 */
public class RuleFailedException extends IllegalStateException {

    public RuleFailedException(Rule rule, Throwable cause) {
        super("правило " + rule.name() + " (" + rule.code() + ") завершилось с ошибкой: " + Failures.describe(cause),
                cause);
    }
}
