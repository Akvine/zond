package ru.akvine.zond.rules;

import com.github.javaparser.ast.Node;

/**
 * Что известно о значении переменной или выражения в конкретной точке метода
 *
 * @param nullness может ли значение быть null
 * @param numeric  true для целых чисел и булевых значений: для них известен диапазон min..max
 *                 (false и true записываются как 0 и 1)
 * @param param    номер параметра метода, из которого значение пришло без изменений, либо -1
 * @param length   длина массива, если она известна, либо -1
 * @param reason   почему значение считается null либо не null - для текста находки
 * @param origin   место в коде, где это стало известно
 */
record FlowValue(Nullness nullness, boolean numeric, long min, long max, int param, long length, String reason, Node origin) {
    static final long MIN = Integer.MIN_VALUE;
    static final long MAX = Integer.MAX_VALUE;
    static final int NO_PARAM = -1;
    static final long NO_LENGTH = -1;

    private static final FlowValue UNKNOWN =
            new FlowValue(Nullness.UNKNOWN, false, MIN, MAX, NO_PARAM, NO_LENGTH, "", null);

    enum Nullness {
        /** Точно null */
        NULL,
        /** Точно не null */
        NOT_NULL,
        /** Есть путь выполнения, на котором значение равно null */
        MAYBE_NULL,
        /** Ничего не известно: о таком значении находок нет */
        UNKNOWN
    }

    static FlowValue unknown() {
        return UNKNOWN;
    }

    static FlowValue parameter(int index) {
        return new FlowValue(Nullness.UNKNOWN, false, MIN, MAX, index, NO_LENGTH, "", null);
    }

    static FlowValue nullValue(String reason, Node origin) {
        return new FlowValue(Nullness.NULL, false, MIN, MAX, NO_PARAM, NO_LENGTH, reason, origin);
    }

    static FlowValue maybeNull(String reason, Node origin) {
        return new FlowValue(Nullness.MAYBE_NULL, false, MIN, MAX, NO_PARAM, NO_LENGTH, reason, origin);
    }

    static FlowValue notNull(String reason, Node origin) {
        return new FlowValue(Nullness.NOT_NULL, false, MIN, MAX, NO_PARAM, NO_LENGTH, reason, origin);
    }

    static FlowValue array(long length, Node origin) {
        return new FlowValue(Nullness.NOT_NULL, false, MIN, MAX, NO_PARAM, length, "массив создан", origin);
    }

    static FlowValue number(long min, long max) {
        // Выход за границы int означает переполнение: о результате ничего сказать нельзя
        if (min < MIN || max > MAX || min > max) {
            return anyNumber();
        }
        return new FlowValue(Nullness.NOT_NULL, true, min, max, NO_PARAM, NO_LENGTH, "", null);
    }

    static FlowValue anyNumber() {
        return new FlowValue(Nullness.NOT_NULL, true, MIN, MAX, NO_PARAM, NO_LENGTH, "", null);
    }

    static FlowValue bool() {
        return number(0, 1);
    }

    /**
     * @return то же значение, о котором стало известно больше: после проверки или обращения
     */
    FlowValue withNullness(Nullness refined, String newReason, Node newOrigin) {
        return new FlowValue(refined, numeric, min, max, param, length, newReason, newOrigin);
    }

    FlowValue withRange(long newMin, long newMax) {
        return new FlowValue(nullness, true, newMin, newMax, param, length, reason, origin);
    }

    FlowValue withReason(String newReason, Node newOrigin) {
        return new FlowValue(nullness, numeric, min, max, param, length, newReason, newOrigin);
    }

    boolean isNull() {
        return nullness == Nullness.NULL;
    }

    boolean isNotNull() {
        return nullness == Nullness.NOT_NULL;
    }

    /**
     * @return true, если значение точно или возможно равно null
     */
    boolean isNullish() {
        return nullness == Nullness.NULL || nullness == Nullness.MAYBE_NULL;
    }

    boolean isConstant() {
        return numeric && min == max;
    }

    boolean isConstant(long value) {
        return isConstant() && min == value;
    }

    /**
     * @return true, если о числе известно хоть что-то: диапазон уже полного
     */
    boolean isBounded() {
        return numeric && (min > MIN || max < MAX);
    }

    int line() {
        return origin == null ? 0 : origin.getBegin().map(position -> position.line).orElse(0);
    }

    /**
     * Значение после слияния двух путей выполнения: известно только то, что верно на обоих
     */
    FlowValue join(FlowValue other) {
        if (this.equals(other)) {
            return this;
        }
        Nullness joined = join(nullness, other.nullness);
        // Причину берем у той стороны, с которой пришел null: ее и нужно показать в находке
        FlowValue source = other.isNullish() && !isNullish() ? other : this;
        boolean bothNumeric = numeric && other.numeric;
        return new FlowValue(
                joined,
                bothNumeric,
                bothNumeric ? Math.min(min, other.min) : MIN,
                bothNumeric ? Math.max(max, other.max) : MAX,
                param == other.param ? param : NO_PARAM,
                length == other.length ? length : NO_LENGTH,
                source.reason,
                source.origin);
    }

    private static Nullness join(Nullness first, Nullness second) {
        if (first == second) {
            return first;
        }
        if (first == Nullness.MAYBE_NULL || second == Nullness.MAYBE_NULL
                || first == Nullness.NULL || second == Nullness.NULL) {
            return Nullness.MAYBE_NULL;
        }
        // NOT_NULL и UNKNOWN
        return Nullness.UNKNOWN;
    }
}
