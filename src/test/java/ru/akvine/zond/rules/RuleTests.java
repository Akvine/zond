package ru.akvine.zond.rules;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import lombok.experimental.UtilityClass;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@UtilityClass
class RuleTests {
    private final static Path DEFAULT_PATH = Path.of("Sample.java");

    List<Violation> check(Rule rule, String code) {
        return check(rule, DEFAULT_PATH, code);
    }

    List<Violation> check(Rule rule, Path path, String code) {
        return rule.check(parse(path, code));
    }

    /**
     * @param files имя файла -> его исходный код
     */
    List<Violation> checkProject(ProjectRule rule, Map<String, String> files) {
        return rule.checkProject(files.entrySet().stream()
                .map(file -> parse(Path.of(file.getKey()), file.getValue()))
                .toList());
    }

    /**
     * @return номера строк, на которых правило нашло нарушения
     */
    List<Integer> lines(Rule rule, String code) {
        return check(rule, code).stream().map(Violation::line).toList();
    }

    private SourceFile parse(Path path, String code) {
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        return new SourceFile(path, StaticJavaParser.parse(code));
    }
}
