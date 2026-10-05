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
    }

    /**
     * Известное о выражении, которое не является переменной: вызове или обращении к полю
     *
     * @param names переменные в выражении: после их изменения факт теряет силу
     */
    public record Fact(FlowValue value, Set<String> names) {
    }

    /**
     * Связь двух переменных: "если variable равна null (либо не равна), то target имеет значение value"
     *
     * @param whenNull true - связь действует, когда variable равна null; false - когда не равна
     */
    public record Implication(String variable, boolean whenNull, String target, FlowValue value) {
    }

    // Связей между переменными одного метода много не бывает; предел - защита от разрастания
    private static final int MAX_IMPLICATIONS = 64;

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
                    && values.containsKey(implication.target())) {
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
        if (!values.keySet().equals(other.values.keySet())) {
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
            if (second.correlations.contains(correlation)) {
                joined.correlations.add(correlation);
            }
        }
        for (Implication implication : first.implications) {
            if (second.implications.contains(implication)) {
                joined.implications.add(implication);
            }
        }
        relate(first, second, joined);
        return joined;
    }

    // Два пути сошлись, и на одном переменная точно null, а на другом точно нет. Значит, проверив ее ниже,
    // мы узнаем, каким путем пришли, - а с ним и то, что на этом пути было известно об остальных переменных.
    // Так понимается код: if (a == null && b == null) return; if (a == null) b.use();
    private static void relate(FlowState first, FlowState second, FlowState joined) {
        for (Map.Entry<String, FlowValue> entry : first.values.entrySet()) {
            String variable = entry.getKey();
            FlowValue inFirst = entry.getValue();
            FlowValue inSecond = second.values.get(variable);
            boolean distinguishes = inSecond != null
                    && (inFirst.isNull() && inSecond.isNotNull() || inFirst.isNotNull() && inSecond.isNull());
            if (!distinguishes) {
                continue;
            }
            for (Map.Entry<String, FlowValue> other : first.values.entrySet()) {
                String target = other.getKey();
                FlowValue targetInSecond = second.values.get(target);
                boolean differs = targetInSecond != null && other.getValue().nullness() != targetInSecond.nullness();
                if (target.equals(variable) || !differs || joined.implications.size() >= MAX_IMPLICATIONS) {
                    continue;
                }
                joined.imply(new Implication(variable, inFirst.isNull(), target, other.getValue()));
                joined.imply(new Implication(variable, inSecond.isNull(), target, targetInSecond));
            }
        }
    }

    private void imply(Implication implication) {
        // Прежнее следствие для той же пары и того же случая заменяется новым
        implications.removeIf(known -> known.variable().equals(implication.variable())
                && known.whenNull() == implication.whenNull() && known.target().equals(implication.target()));
        implications.add(implication);
    }
}
