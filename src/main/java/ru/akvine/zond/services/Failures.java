package ru.akvine.zond.services;

import lombok.experimental.UtilityClass;

import java.util.Arrays;
import java.util.Optional;

/**
 * Описание ошибки для человека. У многих исключений нет текста (ConcurrentModificationException,
 * StackOverflowError, NullPointerException из requireNonNull), и строка «Ошибка: null» ничего не объясняет,
 * поэтому называются вид ошибки и место, где она случилась
 */
@UtilityClass
public class Failures {
    private static final String OWN_PACKAGE = "ru.akvine.zond.";

    /**
     * @return вид ошибки, ее текст, если он есть, и место: строка, где ошибка возникла, и ближайшая к ней
     * строка кода Zond, если ошибка возникла в библиотеке
     */
    public String describe(Throwable failure) {
        StringBuilder text = new StringBuilder(failure.getClass().getName());
        if (failure.getMessage() != null && !failure.getMessage().isBlank()) {
            text.append(": ").append(failure.getMessage());
        }
        StackTraceElement[] stack = failure.getStackTrace();
        if (stack.length > 0) {
            text.append(" (").append(stack[0]);
            Optional<StackTraceElement> own = Arrays.stream(stack)
                    .filter(frame -> frame.getClassName().startsWith(OWN_PACKAGE))
                    .findFirst();
            if (own.isPresent() && !own.get().equals(stack[0])) {
                text.append(", в коде Zond: ").append(own.get());
            }
            text.append(')');
        }
        return text.toString();
    }
}
