package ru.akvine.zond.models;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Пути, которые не нужно сканировать. Шаблон сверяется с путем файла от корня сканирования:
 * <pre>
 *   generated                  - каталог или файл с таким именем на любой глубине, со всем содержимым
 *   src/main/java/legacy       - этот каталог от корня сканирования, со всем содержимым
 *   *Dto.java                  - файлы по имени, в любом каталоге
 *   **&#47;generated/**&#47;*.java   - путь по шаблону: * - часть имени, ** - любые каталоги, ? - один символ
 * </pre>
 *
 * @param patterns шаблоны в том виде, как их задал пользователь
 */
public record PathExclusions(List<String> patterns) {
    private static final String SEPARATOR = "[,;]";
    private static final String SLASH = "/";
    private static final String WILDCARDS = ".*[*?].*";

    public static PathExclusions none() {
        return new PathExclusions(List.of());
    }

    /**
     * @param patterns шаблоны через запятую или точку с запятой; может быть пустой строкой
     */
    public static PathExclusions parse(String patterns) {
        return new PathExclusions(Arrays.stream(patterns.split(SEPARATOR))
                .map(String::trim)
                .filter(pattern -> !pattern.isEmpty())
                .toList());
    }

    public boolean isEmpty() {
        return patterns.isEmpty();
    }

    /**
     * @param root с чего начато сканирование
     * @return true, если файл подходит хотя бы под один шаблон
     */
    public boolean matches(Path root, Path file) {
        if (patterns.isEmpty()) {
            return false;
        }
        // На Windows разделитель - обратная косая черта; шаблоны всегда пишутся через прямую
        String relative = root.toAbsolutePath().normalize()
                .relativize(file.toAbsolutePath().normalize())
                .toString()
                .replace('\\', '/');
        return patterns.stream().anyMatch(pattern -> matches(normalize(pattern), relative));
    }

    private boolean matches(String pattern, String path) {
        if (pattern.isEmpty()) {
            return false;
        }
        if (!pattern.matches(WILDCARDS)) {
            // Обычное имя или путь: сам элемент либо все, что внутри него, на любой глубине
            String padded = SLASH + path + SLASH;
            return padded.contains(SLASH + pattern + SLASH);
        }
        Pattern regex = toRegex(pattern);
        if (regex.matcher(path).matches()) {
            return true;
        }
        // Шаблон без каталогов (*Dto.java) относится к имени файла
        return !pattern.contains(SLASH) && regex.matcher(path.substring(path.lastIndexOf('/') + 1)).matches();
    }

    // ./src\gen/ -> src/gen
    private String normalize(String pattern) {
        String normalized = pattern.replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        while (normalized.endsWith(SLASH)) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.startsWith(SLASH) ? normalized.substring(1) : normalized;
    }

    private Pattern toRegex(String glob) {
        StringBuilder regex = new StringBuilder();
        List<String> literal = new ArrayList<>();
        for (int index = 0; index < glob.length(); index++) {
            char symbol = glob.charAt(index);
            if (symbol != '*' && symbol != '?') {
                literal.add(String.valueOf(symbol));
                continue;
            }
            regex.append(quote(literal));
            literal.clear();
            if (symbol == '?') {
                regex.append("[^/]");
            } else if (index + 1 < glob.length() && glob.charAt(index + 1) == '*') {
                // **/ - любое число каталогов, в том числе ни одного
                boolean wholeSegment = index + 2 < glob.length() && glob.charAt(index + 2) == '/';
                regex.append(wholeSegment ? "(?:.*/)?" : ".*");
                index += wholeSegment ? 2 : 1;
            } else {
                regex.append("[^/]*");
            }
        }
        regex.append(quote(literal));
        return Pattern.compile(regex.toString());
    }

    private String quote(List<String> symbols) {
        return symbols.isEmpty() ? "" : Pattern.quote(String.join("", symbols));
    }
}
