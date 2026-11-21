package ru.akvine.zond.services;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import org.junit.jupiter.api.Test;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DeadCodeTest {
    private static final Path HELPER = Path.of("src/main/java/app/TransactionHelper.java");
    private static final Path SERVICE = Path.of("src/main/java/app/OrderService.java");

    private final ScanContext project = new ScanContext(Path.of("."), List.of(
            parse(Path.of("src/main/java/app/Application.java"), """
                    @SpringBootApplication
                    public class Application {
                        private final OrderService orderService;
                        public static void main(String[] args) {
                            SpringApplication.run(Application.class, args);
                        }
                    }
                    """),
            parse(HELPER, """
                    @Component
                    public class TransactionHelper {
                        @SneakyThrows
                        @Transactional
                        public <T> T execute(Callable<T> action) {
                            return action.call();
                        }
                    }
                    """),
            parse(SERVICE, """
                    @Service
                    public class OrderService {
                        @Transactional
                        public void create() {
                        }
                    }
                    """)), List.of(), List.of());

    @Test
    void behaviourFindingInUnusedClassBecomesSuspicion() {
        Violation inIdleBean = finding(ErrorType.LOGICAL, HELPER, 5);
        Violation secretInIdleBean = finding(ErrorType.SECURITY, HELPER, 5);
        Violation styleInIdleBean = finding(ErrorType.CODE_SMELL, HELPER, 2);
        Violation inUsedClass = finding(ErrorType.LOGICAL, SERVICE, 4);

        List<Violation> judged = DeadCode.demote(
                List.of(inIdleBean, secretInIdleBean, styleInIdleBean, inUsedClass), List.of(project));

        assertThat(judged.get(0).confidence()).isEqualTo(Confidence.SUSPICION);
        assertThat(judged.get(0).message()).startsWith("Сообщение правила").contains("'TransactionHelper'", "jr:206");
        assertThat(judged.get(0).errorLevel()).isEqualTo(ErrorLevel.CRITICAL);
        // Секрет в неиспользуемом классе лежит в хранилище так же, как в используемом; о стиле судят как обычно
        assertThat(judged.subList(1, 4)).containsExactly(secretInIdleBean, styleInIdleBean, inUsedClass);
    }

    @Test
    void findingKeepsItsConfidenceWhenNothingIsUnused() {
        ScanContext single = new ScanContext(Path.of("."), List.of(project.sources().get(2)), List.of(), List.of());
        Violation finding = finding(ErrorType.LOGICAL, SERVICE, 4);
        assertThat(DeadCode.demote(List.of(finding), List.of(single))).containsExactly(finding);
    }

    private static Violation finding(ErrorType type, Path file, int line) {
        return new Violation(
                ErrorLevel.CRITICAL, type, "jr:3", "SomeRule", file, line, "Сообщение правила", Confidence.PROBABLE);
    }

    private static SourceFile parse(Path path, String code) {
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        return new SourceFile(path, StaticJavaParser.parse(code));
    }
}
