package ru.akvine.zond.rules;

import java.util.Map;
import java.util.Set;

/**
 * Сводка метода: то, что нужно знать в месте его вызова, чтобы не разбирать тело заново
 *
 * @param returned            что известно о возвращаемом значении
 * @param dereferencedParams  номера параметров, к которым метод обращается без проверки на null
 * @param nonNullAfterCall    номера параметров, которые точно не null, если метод вернул управление:
 *                            он проверил параметр и бросил исключение либо обратился к нему
 * @param neverReturns        метод всегда завершается исключением
 * @param mutatesFields       метод меняет поля своего объекта либо вызывает метод, который их меняет:
 *                            после вызова о полях ничего не известно
 * @param resultForNullParam  для методов-проверок: что вернет метод, если параметр равен null.
 *                            isBlank(null) == true означает, что после !isBlank(x) значение x не null
 */
record FlowSummary(
        FlowValue returned,
        Set<Integer> dereferencedParams,
        Set<Integer> nonNullAfterCall,
        boolean neverReturns,
        boolean mutatesFields,
        Map<Integer, Boolean> resultForNullParam) {

    /**
     * Сводка метода, о котором ничего не известно: рекурсивного либо без тела
     */
    static FlowSummary unknown() {
        return new FlowSummary(FlowValue.unknown(), Set.of(), Set.of(), false, true, Map.of());
    }

    FlowSummary withReturned(FlowValue value) {
        return new FlowSummary(value, dereferencedParams, nonNullAfterCall, neverReturns, mutatesFields, resultForNullParam);
    }

    FlowSummary withResultForNullParam(Map<Integer, Boolean> results) {
        return new FlowSummary(returned, dereferencedParams, nonNullAfterCall, neverReturns, mutatesFields, results);
    }
}
