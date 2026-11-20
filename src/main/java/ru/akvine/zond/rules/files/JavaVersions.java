package ru.akvine.zond.rules.files;

import lombok.experimental.UtilityClass;
import ru.akvine.zond.models.TextFile;

import java.util.List;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Версия Java, под которую собирают проект, по файлам сборки. От нее зависит поведение самой платформы:
 * с Java 18 кодировка по умолчанию - всегда UTF-8, а не кодировка машины
 */
@UtilityClass
public class JavaVersions {
    // 17, 1.8, VERSION_17, VERSION_1_8
    private static final String NUMBER = "(?:VERSION_)?(?:1[._])?(\\d{1,2})";

    // <java.version>17</java.version>, <maven.compiler.release>21</maven.compiler.release>, <release>17</release>;
    // sourceCompatibility = JavaVersion.VERSION_17, targetCompatibility = '11', JavaLanguageVersion.of(21),
    // options.release = 17, jvmTarget = "17"
    private static final List<Pattern> DECLARATIONS = List.of(
            Pattern.compile("<(?:java\\.version|maven\\.compiler\\.(?:source|target|release)|release|source|target)>"
                    + "\\s*" + NUMBER + "\\s*<"),
            Pattern.compile("(?:sourceCompatibility|targetCompatibility)\\s*(?:=|\\()?\\s*['\"]?(?:JavaVersion\\.)?"
                    + NUMBER + "\\b"),
            Pattern.compile("JavaLanguageVersion\\.of\\(\\s*['\"]?" + NUMBER),
            Pattern.compile("(?:options\\.release|jvmTarget)\\s*(?:=|\\.set\\()\\s*['\"]?" + NUMBER + "\\b"));

    /**
     * @return самая младшая версия Java из названных в pom.xml и build.gradle: в многомодульном проекте часть
     * модулей может собираться под старую. Пусто, если версия в файлах сборки не названа
     */
    public OptionalInt lowest(List<TextFile> files) {
        int lowest = Integer.MAX_VALUE;
        for (TextFile file : files) {
            if (!TextFiles.isPom(file) && !TextFiles.isGradle(file)) {
                continue;
            }
            for (String line : file.lines()) {
                for (Pattern declaration : DECLARATIONS) {
                    Matcher matcher = declaration.matcher(line);
                    if (matcher.find()) {
                        lowest = Math.min(lowest, Integer.parseInt(matcher.group(1)));
                    }
                }
            }
        }
        return lowest == Integer.MAX_VALUE ? OptionalInt.empty() : OptionalInt.of(lowest);
    }
}
