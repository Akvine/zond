package ru.akvine.zond.rules;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import lombok.experimental.UtilityClass;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.nio.file.Path;
import java.util.List;

@UtilityClass
class RuleTests {

    List<Violation> check(Rule rule, String code) {
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        return rule.check(new SourceFile(Path.of("Sample.java"), StaticJavaParser.parse(code)));
    }

    /**
     * @return номера строк, на которых правило нашло нарушения
     */
    List<Integer> lines(Rule rule, String code) {
        return check(rule, code).stream().map(Violation::line).toList();
    }
}
