package ru.akvine.zond.rules.flow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Состояние в одной точке метода: что известно о каждой локальной переменной, параметре и поле своего
 * объекта. Поле хранится под именем this.имя.
 */
public final class FlowState {
    private final Map<String, FlowValue> values = new HashMap<>();
    private final List<Correlation> correlations = new ArrayList<>();
    private final List<Implication> implications = new ArrayList<>();
    // Текст выражения -> что о нем известно: после if (user.getName() != null) вызов user.getName() не null
    private final Map<String, Fact> facts = new HashMap<>();

    /**
     * Связь значения с условием: "если condition равно truth, то переменная имеет значение value".
     * Появляется после if, в одной ветке которого переменная получила значение, а в другой осталась null:
     * под тем же условием ниже по коду переменной можно пользоваться без проверки.
     *
     * @param names переменные, от которых зависит условие: после их изменения связь теряет силу
     */
    public record Correlation(String variable, String condition, boolean truth, FlowValue value, Set<String> names) {
        /**
         * @return true, если это связь той же переменной с тем же условием, пусть и с другим описанием значения
         */
        public boolean isSameLink(Correlation other) {
            return variable.equals(other.variable) && condition.equals(other.condition) && truth == other.truth;
        }
    }

    /**
     * Известное о выражении, которое не является переменной: вызове или обращении к полю
     *
     * @param names переменные в выражении: после их изменения факт теряет силу
     */
    public record Fact(FlowValue value, Set<String> names) {
    }

    /**
     * Связь: "если variable равна null (либо не равна), то target имеет значение value". Условием может быть
     * и выражение с булевым результатом (errors.isEmpty()): тогда variable - его текст с пометкой
     *
     * @param whenNull true - связь действует, когда variable равна null (выражение истинно); false - наоборот
     */
    public record Implication(String variable, boolean whenNull, String target, FlowValue value) {
        /**
         * @return true, если это следствие для той же пары переменных и того же случая
         */
        public boolean isSameLink(Implication other) {
            return variable.equals(other.variable) && whenNull == other.whenNull && target.equals(other.target);
        }
    }

    // Связей между переменными одного метода много не бывает; предел - защита от разрастания
    private static final int MAX_IMPLICATIONS = 64;
    // Пометка в Implication.variable: условие связи - не переменная, а выражение с известным значением
    private static final String FACT = "?";
    private static final String THIS = "this.";

    public FlowState copy() {
        FlowState copy = new FlowState();
        copy.values.putAll(values);
        copy.correlations.addAll(correlations);
        copy.facts.putAll(facts);
        copy.implications.addAll(implications);
        return copy;
    }

    /**
     * Принимает все, что известно в другом состоянии, оставаясь тем же объектом
     */
    public void adopt(FlowState other) {
        if (other == this) {
            return;
        }
        values.clear();
        values.putAll(other.values);
        correlations.clear();
        correlations.addAll(other.correlations);
        facts.clear();
        facts.putAll(other.facts);
        implications.clear();
        implications.addAll(other.implications);
    }

    /**
     * Перенимает то, что вызывающий метод знает о полях своего объекта: значения полей и факты, которые
     * зависят только от полей ({@code legs.isEmpty()}). Нужно, когда метод того же объекта разбирается
     * для конкретного места вызова: проверка, сделанная вызывающим, действует и внутри вызванного.
     *
     * @param hidden имена параметров вызванного метода: они перекрывают одноименные поля
     */
    public void adoptFields(FlowState caller, Set<String> hidden) {
        caller.values.forEach((name, value) -> {
            if (name.startsWith(THIS) && values.containsKey(name)) {
                values.put(name, value);
            }
        });
        caller.facts.forEach((expression, fact) -> {
            // Факт о локальной переменной вызывающего (она перекрывает поле) вызванному методу ничего не говорит
            boolean fieldsOnly = !fact.names().isEmpty() && fact.names().stream().allMatch(name -> {
                String bare = name.startsWith(THIS) ? name.substring(THIS.length()) : name;
                return !hidden.contains(bare) && !caller.values.containsKey(bare) && values.containsKey(THIS + bare);
            });
            if (fieldsOnly) {
                facts.put(expression, fact);
            }
        });
    }

    /**
     * @return значение переменной либо null, если такой локальной переменной нет
     */
    public FlowValue get(String name) {
        return values.get(name);
    }

    public boolean has(String name) {
        return values.containsKey(name);
    }

    public Set<String> names() {
        return values.keySet();
    }

    /**
     * Переменной присвоено новое значение: все, что было связано с прежним, больше не действует
     */
    public void assign(String name, FlowValue value) {
        values.put(name, value);
        correlations.removeIf(correlation -> correlation.variable().equals(name) || correlation.names().contains(name));
        facts.values().removeIf(fact -> fact.names().contains(name));
        implications.removeIf(implication -> implication.variable().equals(name) || implication.target().equals(name));
        forgetFactsOf(name);
        if (name.startsWith(THIS)) {
            forgetFactsOf(name.substring(THIS.length()));
        }
    }

    /**
     * @return известное о выражении с таким текстом либо null
     */
    public FlowValue fact(String expression) {
        Fact fact = facts.get(expression);
        return fact == null ? null : fact.value();
    }

    public void learn(String expression, FlowValue value, Set<String> names) {
        facts.put(expression, new Fact(value, names));
        if (!value.isConstant(0) && !value.isConstant(1)) {
            return;
        }
        // Стало известно, истинно ли выражение (errors.isEmpty()): применяем все, что из этого следует
        for (Implication implication : implications) {
            if (implication.variable().equals(FACT + expression) && implication.whenNull() == value.isConstant(1)
                    && values.containsKey(implication.target())) {
                values.put(implication.target(), implication.value());
            }
        }
    }

    /**
     * Объект изменили либо отдали в другой метод: все, что было известно о его методах, больше не действует
     *
     * @param receiver как объект записан в коде: errors, this.items
     */
    public void forgetFactsOf(String receiver) {
        String prefix = receiver + ".";
        facts.keySet().removeIf(expression -> expression.startsWith(prefix));
        implications.removeIf(implication -> implication.variable().startsWith(FACT + prefix));
    }

    /**
     * О значении переменной стало известно больше, само значение не менялось
     */
    public void refine(String name, FlowValue value) {
        values.put(name, value);
        if (!value.isNull() && !value.isNotNull()) {
            return;
        }
        // Стало известно, null переменная или нет: применяем все, что из этого следует для других
        for (Implication implication : implications) {
            if (implication.variable().equals(name) && implication.whenNull() == value.isNull()
                    && values.containsKey(implication.target()) && !isDefinite(values.get(implication.target()))) {
                values.put(implication.target(), implication.value());
            }
        }
    }

    public void correlate(Correlation correlation) {
        correlations.add(correlation);
    }

    /**
     * Условие с таким текстом оказалось истинным либо ложным: применяем связанные с ним значения
     */
    public void applyCondition(String condition, boolean truth) {
        for (Correlation correlation : correlations) {
            if (correlation.truth() == truth && correlation.condition().equals(condition)
                    && values.containsKey(correlation.variable())) {
                values.put(correlation.variable(), correlation.value());
            }
        }
    }

    /**
     * Переменные могли измениться неизвестно сколько раз (в цикле, в блоке try): забываем о них все.
     *
     * @param keepNull   true - переменная, равная null, остается "возможно null": присваивание могло
     *                   и не выполниться (исключение в try до него)
     * @param primitives переменные примитивных типов: они и после изменения остаются числами
     */
    public void forget(Set<String> names, boolean keepNull, Set<String> primitives) {
        for (String name : names) {
            FlowValue value = values.get(name);
            if (value == null) {
                continue;
            }
            FlowValue forgotten = primitives.contains(name) ? FlowValue.anyNumber() : FlowValue.unknown();
            assign(name, keepNull && value.isNullish() ? value.join(forgotten) : forgotten);
        }
    }

    /**
     * @return true, если о переменных известно то же самое; причины и места в коде не сравниваются
     */
    public boolean sameAs(FlowState other) {
        // Известное о выражениях тоже часть состояния: без него условие в теле цикла вычислится иначе
        if (!values.keySet().equals(other.values.keySet()) || !facts.keySet().equals(other.facts.keySet())) {
            return false;
        }
        for (Map.Entry<String, FlowValue> entry : values.entrySet()) {
            FlowValue mine = entry.getValue();
            FlowValue theirs = other.values.get(entry.getKey());
            boolean isSame = mine.nullness() == theirs.nullness() && mine.numeric() == theirs.numeric()
                    && mine.min() == theirs.min() && mine.max() == theirs.max()
                    && mine.length() == theirs.length() && mine.param() == theirs.param();
            if (!isSame) {
                return false;
            }
        }
        return true;
    }

    /**
     * Состояние в начале цикла после очередного прохода: слияние прежнего с новым. Граница числа, которая
     * сдвинулась, сразу отодвигается до предела - иначе счетчик цикла рос бы по единице без конца
     */
    public FlowState widenedBy(FlowState next) {
        FlowState joined = join(copy(), next).copy();
        for (Map.Entry<String, FlowValue> entry : joined.values.entrySet()) {
            FlowValue before = values.get(entry.getKey());
            FlowValue after = entry.getValue();
            if (before != null && before.numeric() && after.numeric()) {
                entry.setValue(after.withRange(
                        after.min() < before.min() ? FlowValue.MIN : after.min(),
                        after.max() > before.max() ? FlowValue.MAX : after.max()));
            }
        }
        return joined;
    }

    /**
     * @return состояние после слияния двух путей; null означает путь, по которому выполнение не идет
     */
    public static FlowState join(FlowState first, FlowState second) {
        if (first == null) {
            return second;
        }
        if (second == null || first == second) {
            return first;
        }
        FlowState joined = new FlowState();
        first.values.forEach((name, value) -> {
            FlowValue other = second.values.get(name);
            joined.values.put(name, other == null ? value : value.join(other));
        });
        second.values.forEach(joined.values::putIfAbsent);
        first.facts.forEach((expression, fact) -> {
            if (fact.equals(second.facts.get(expression))) {
                joined.facts.put(expression, fact);
            }
        });
        for (Correlation correlation : first.correlations) {
            // Та же связь на обоих путях, пусть и найденная в разных местах кода, остается связью
            second.correlations.stream()
                    .filter(correlation::isSameLink)
                    .findFirst()
                    .ifPresent(other -> joined.keep(correlation.equals(other) ? correlation : new Correlation(
                            correlation.variable(), correlation.condition(), correlation.truth(),
                            correlation.value().join(other.value()), correlation.names())));
        }
        for (Implication implication : first.implications) {
            // То же следствие на обоих путях остается следствием, даже если значение описано по-разному
            second.implications.stream()
                    .filter(implication::isSameLink)
                    .findFirst()
                    .ifPresent(other -> joined.implications.add(implication.equals(other) ? implication : new Implication(
                            implication.variable(), implication.whenNull(), implication.target(),
                            implication.value().join(other.value()))));
        }
        keepSettled(first, second, joined);
        keepSettled(second, first, joined);
        relate(first, second, joined);
        relateFlags(first, second, joined);
        relateFlags(second, first, joined);
        return joined;
    }

    // На одном пути флаг равен false и переменная null, на другом переменная получила значение. Значит, там,
    // где флаг истинен, пришли вторым путем и переменная не null: inLiteral = true; buffer = new StringBuilder()
    // в одной ветке switch и нетронутые false и null в остальных
    private static void relateFlags(FlowState unset, FlowState set, FlowState joined) {
        for (Map.Entry<String, FlowValue> entry : unset.values.entrySet()) {
            String flag = entry.getKey();
            FlowValue here = entry.getValue();
            FlowValue there = set.values.get(flag);
            boolean isFlag = (here.isConstant(0) || here.isConstant(1)) && there != null && there.numeric()
                    && there.min() >= 0 && there.max() <= 1 && !there.isConstant(here.min());
            if (!isFlag) {
                continue;
            }
            for (Map.Entry<String, FlowValue> other : unset.values.entrySet()) {
                FlowValue assigned = set.values.get(other.getKey());
                if (other.getValue().isNull() && assigned != null && !assigned.isNullish() && !assigned.numeric()) {
                    joined.keep(new Correlation(other.getKey(), flag, here.isConstant(0), assigned, Set.of(flag)));
                }
            }
        }
    }

    // Связь известна только на одном пути, но другому она не противоречит: там переменная и так не null
    // либо условие связи заведомо не выполняется. Без этого связь, найденная в теле цикла, пропадала бы при
    // слиянии с состоянием перед циклом: head = null; tail = null; while (...) { if (head == null) head = node;
    // else tail.next = node; tail = node; } - во второй итерации tail уже не null, раз не null head
    private static void keepSettled(FlowState source, FlowState other, FlowState joined) {
        for (Correlation correlation : source.correlations) {
            FlowValue there = other.values.get(correlation.variable());
            if (there == null || other.correlations.stream().anyMatch(correlation::isSameLink)) {
                continue;
            }
            if (!there.isNullish()) {
                joined.keep(new Correlation(correlation.variable(), correlation.condition(),
                        correlation.truth(), correlation.value().join(there), correlation.names()));
            } else if (isFlagOpposite(correlation, other)) {
                joined.keep(correlation);
            }
        }
        for (Implication implication : source.implications) {
            FlowValue target = other.values.get(implication.target());
            if (target == null || other.implications.stream().anyMatch(implication::isSameLink)
                    || joined.implications.size() >= MAX_IMPLICATIONS) {
                continue;
            }
            Boolean subject = implication.variable().startsWith(FACT)
                    ? definiteTruth(other.fact(implication.variable().substring(FACT.length())))
                    : definiteSubject(other.values.get(implication.variable()));
            Boolean expected = definiteNull(implication.value());
            if (subject != null && subject != implication.whenNull()) {
                joined.imply(implication);
            } else if (expected != null && expected.equals(definiteNull(target))) {
                joined.imply(new Implication(implication.variable(), implication.whenNull(), implication.target(),
                        implication.value().join(target)));
            }
        }
    }

    private void keep(Correlation correlation) {
        correlations.removeIf(correlation::isSameLink);
        correlations.add(correlation);
    }

    // Условие связи - флаг, и на этом пути он имеет обратное значение: связь здесь ни к чему не обязывает
    private static boolean isFlagOpposite(Correlation correlation, FlowState state) {
        FlowValue flag = state.values.get(correlation.condition());
        return flag != null && (flag.isConstant(0) || flag.isConstant(1))
                && flag.isConstant(1) != correlation.truth();
    }

    private static Boolean definiteSubject(FlowValue value) {
        return value == null ? null : definiteNull(value);
    }

    // Два пути сошлись, и на одном переменная точно null, а на другом точно нет. Значит, проверив ее ниже,
    // мы узнаем, каким путем пришли, - а с ним и то, что на этом пути было известно об остальных переменных.
    // Так понимается код: if (a == null && b == null) return; if (a == null) b.use();
    private static void relate(FlowState first, FlowState second, FlowState joined) {
        // Условие связи - переменная: на одном пути она точно null либо точно не null
        for (Map.Entry<String, FlowValue> entry : first.values.entrySet()) {
            FlowValue inSecond = second.values.get(entry.getKey());
            if (inSecond != null) {
                relate(entry.getKey(), definiteNull(entry.getValue()), definiteNull(inSecond), first, second, joined);
            }
        }
        // Условие связи - выражение: на одном пути errors.isEmpty() точно истинно либо точно ложно
        Set<String> expressions = new java.util.HashSet<>(first.facts.keySet());
        expressions.addAll(second.facts.keySet());
        for (String expression : expressions) {
            relate(FACT + expression, definiteTruth(first.fact(expression)), definiteTruth(second.fact(expression)),
                    first, second, joined);
        }
    }

    /**
     * @param inFirst  что известно о условии на первом пути: TRUE (null / истинно), FALSE либо null - неизвестно
     * @param inSecond то же на втором пути
     */
    private static void relate(
            String subject, Boolean inFirst, Boolean inSecond, FlowState first, FlowState second, FlowState joined) {
        boolean distinguishes = inFirst != null && inSecond != null && !inFirst.equals(inSecond);
        boolean onlyFirstKnown = inFirst != null && inSecond == null;
        boolean onlySecondKnown = inFirst == null && inSecond != null;
        if (!distinguishes && !onlyFirstKnown && !onlySecondKnown) {
            return;
        }
        for (Map.Entry<String, FlowValue> other : first.values.entrySet()) {
            String target = other.getKey();
            FlowValue targetInSecond = second.values.get(target);
            boolean differs = targetInSecond != null && other.getValue().nullness() != targetInSecond.nullness();
            if (target.equals(subject) || !differs || joined.implications.size() >= MAX_IMPLICATIONS) {
                continue;
            }
            if (distinguishes) {
                joined.imply(new Implication(subject, inFirst, target, other.getValue()));
                joined.imply(new Implication(subject, inSecond, target, targetInSecond));
            } else if (onlyFirstKnown) {
                // Условие приняло другое значение, чем на первом пути, - значит, пришли вторым
                joined.imply(new Implication(subject, !inFirst, target, targetInSecond));
            } else {
                joined.imply(new Implication(subject, !inSecond, target, other.getValue()));
            }
        }
    }

    // Прямая проверка значения важнее вывода из связи: связь строится по путям, а проверка - факт
    private static boolean isDefinite(FlowValue value) {
        return definiteNull(value) != null;
    }

    private static Boolean definiteNull(FlowValue value) {
        if (value.isNull()) {
            return Boolean.TRUE;
        }
        return value.isNotNull() ? Boolean.FALSE : null;
    }

    private static Boolean definiteTruth(FlowValue value) {
        if (value == null) {
            return null;
        }
        if (value.isConstant(1)) {
            return Boolean.TRUE;
        }
        return value.isConstant(0) ? Boolean.FALSE : null;
    }

    /**
     * x = cond ? null : value: когда станет известно, истинно ли условие (выражение с булевым результатом),
     * станет известно и значение x
     *
     * @param truth при каком значении условия x получает value
     */
    public void implyByCondition(String condition, boolean truth, String target, FlowValue value) {
        imply(new Implication(FACT + condition, truth, target, value));
    }

    private void imply(Implication implication) {
        // Прежнее следствие для той же пары и того же случая заменяется новым. Но точное следствие (значение
        // null либо не null), верное на обоих путях, не уступает догадке "может быть null", собранной из их слияния
        boolean isWeaker = !isDefinite(implication.value()) && implications.stream()
                .anyMatch(known -> known.isSameLink(implication) && isDefinite(known.value()));
        if (isWeaker) {
            return;
        }
        implications.removeIf(implication::isSameLink);
        implications.add(implication);
    }
}
