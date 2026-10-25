package ru.akvine.zond.rules.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Разбор регулярного выражения ровно настолько, чтобы найти повтор внутри повтора, который можно разбить
 * на части несколькими способами: (a+)+, (\w+\s?)*, (.*a)+. Такой шаблон на почти подходящей строке
 * перебирает варианты экспоненциально долго.
 * <p>
 * (\d+\.)+ сюда не попадает: обязательная точка однозначно отделяет одно повторение от другого. Притяжательные
 * квантификаторы (a++) и атомарные группы (?>...) возвратов не делают и тоже безопасны.
 */
final class RegexShape {
    // После повтора только закрываются внешние группы: ...)*, ...)+)
    private static final Pattern TRAILING = Pattern.compile("[)*+?]*");
    private final String regex;
    private int position;
    private boolean catastrophic;
    // После вложенного повтора есть что-то, на чем совпадение может сорваться: $, буква, другая часть
    private boolean requiredAfter;

    /**
     * Часть шаблона с ее повтором
     *
     * @param unbounded   может повторяться сколько угодно раз: *, +, {n,}
     * @param optional    может совпасть с пустой строкой
     * @param broad       совпадает почти с любым символом: точка, \S, [^...]
     * @param ambiguous   группа, которую можно разбить на повторения несколькими способами
     */
    private record Part(boolean unbounded, boolean optional, boolean broad, boolean ambiguous) {
    }

    private RegexShape(String regex) {
        this.regex = regex;
    }

    static boolean isCatastrophic(String regex) {
        return parse(regex).filter(shape -> shape.catastrophic).isPresent();
    }

    /**
     * Для поиска подстроки (replaceAll, replaceFirst, split): вложенный повтор в самом конце шаблона, без $,
     * жадно съедает что может, и совпадение сразу удается - перебора не бывает
     */
    static boolean isCatastrophicOnSearch(String regex) {
        return parse(regex).filter(shape -> shape.catastrophic && shape.requiredAfter).isPresent();
    }

    private static Optional<RegexShape> parse(String regex) {
        RegexShape shape = new RegexShape(regex);
        try {
            shape.parseAlternatives();
        } catch (IndexOutOfBoundsException exception) {
            // Незаконченный шаблон: о нем скажет сам Pattern при запуске
            return Optional.empty();
        }
        return Optional.of(shape);
    }

    // Последовательность вариантов через | до закрывающей скобки или конца шаблона
    private boolean parseAlternatives() {
        List<List<Part>> alternatives = new ArrayList<>();
        List<Part> current = new ArrayList<>();
        while (position < regex.length() && regex.charAt(position) != ')') {
            if (regex.charAt(position) == '|') {
                alternatives.add(current);
                current = new ArrayList<>();
                position++;
                continue;
            }
            current.add(parsePart());
        }
        alternatives.add(current);
        return alternatives.stream().anyMatch(this::isAmbiguous);
    }

    // В варианте есть свободный повтор, а все остальное в нем необязательно либо съедается тем же повтором.
    // (\s+[A-Za-z]+)* однозначен: каждому повторению нужны и пробелы, и буквы
    private boolean isAmbiguous(List<Part> sequence) {
        List<Part> repeating = sequence.stream().filter(part -> part.unbounded() || part.ambiguous()).toList();
        boolean aloneEnough = repeating.stream().anyMatch(candidate -> sequence.stream()
                .filter(part -> part != candidate)
                .allMatch(Part::optional));
        return aloneEnough || repeating.stream().anyMatch(Part::broad);
    }

    private Part parsePart() {
        char symbol = regex.charAt(position);
        boolean broad = false;
        boolean ambiguous = false;
        boolean atomic = false;
        boolean zeroWidth = false;
        if (symbol == '(') {
            position++;
            if (regex.startsWith("?>", position)) {
                atomic = true;
            }
            zeroWidth = regex.startsWith("?=", position) || regex.startsWith("?!", position)
                    || regex.startsWith("?<=", position) || regex.startsWith("?<!", position);
            skipGroupFlags();
            ambiguous = parseAlternatives() && !atomic;
            position++;
        } else if (symbol == '[') {
            broad = regex.startsWith("[^", position);
            skipCharacterClass();
        } else if (symbol == '\\') {
            char escaped = regex.charAt(position + 1);
            broad = escaped == 'S' || escaped == 'W' || escaped == 'D';
            position += 2;
        } else {
            broad = symbol == '.';
            zeroWidth = symbol == '^' || symbol == '$';
            position++;
        }
        return quantified(broad, ambiguous, zeroWidth);
    }

    private Part quantified(boolean broad, boolean ambiguous, boolean zeroWidth) {
        boolean unbounded = false;
        boolean optional = zeroWidth;
        if (position < regex.length()) {
            char quantifier = regex.charAt(position);
            if (quantifier == '*' || quantifier == '+' || quantifier == '?') {
                unbounded = quantifier != '?';
                optional = optional || quantifier != '+';
                position++;
            } else if (quantifier == '{' && regex.indexOf('}', position) > position) {
                String bounds = regex.substring(position + 1, regex.indexOf('}', position));
                unbounded = bounds.endsWith(",");
                optional = optional || bounds.startsWith("0");
                position = regex.indexOf('}', position) + 1;
            }
        }
        boolean possessive = position < regex.length() && regex.charAt(position) == '+';
        if (position < regex.length() && (regex.charAt(position) == '?' || possessive)) {
            position++;
        }
        if (unbounded && ambiguous && !possessive) {
            catastrophic = true;
            requiredAfter = requiredAfter || !TRAILING.matcher(regex.substring(position)).matches();
        }
        return new Part(unbounded && !possessive, optional, broad, ambiguous && !possessive);
    }

    // (?:...), (?i), (?<name>...), (?>...), (?=...)
    private void skipGroupFlags() {
        if (position >= regex.length() || regex.charAt(position) != '?') {
            return;
        }
        if (regex.startsWith("?<", position) && !regex.startsWith("?<=", position) && !regex.startsWith("?<!", position)) {
            position = regex.indexOf('>', position) + 1;
            return;
        }
        position++;
        while (position < regex.length() && ":>=!<-imsuxdU".indexOf(regex.charAt(position)) >= 0) {
            char flag = regex.charAt(position);
            position++;
            if (flag == ':' || flag == '>' || flag == '=' || flag == '!') {
                return;
            }
        }
    }

    // [...] с экранированием и ] сразу после открывающей скобки
    private void skipCharacterClass() {
        position++;
        if (position < regex.length() && regex.charAt(position) == '^') {
            position++;
        }
        if (position < regex.length() && regex.charAt(position) == ']') {
            position++;
        }
        while (regex.charAt(position) != ']') {
            position += regex.charAt(position) == '\\' ? 2 : 1;
        }
        position++;
    }
}
