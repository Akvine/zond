package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.logical.ImpossibleCastRule;
import ru.akvine.zond.rules.logical.StopWatchStoppedTwiceRule;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * jr:543, jr:547: код, который компилируется, но при выполнении всегда падает - приведение к классу, которым
 * значение быть не может, и повторная остановка секундомера
 */
class RuntimeFailureRulesTest {
    private static Map<String, String> bots(String client) {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("TelegramBot.java", "public interface TelegramBot {\n}\n");
        files.put("AbstractBot.java", "public abstract class AbstractBot implements TelegramBot {\n}\n");
        files.put("TelegramDevBot.java", "public class TelegramDevBot extends TelegramLongPollingBot {\n}\n");
        files.put("TelegramProductionBot.java", "public class TelegramProductionBot extends TelegramWebhookBot {\n}\n");
        files.put("TestBot.java", "public class TestBot extends TelegramDevBot {\n}\n");
        files.put("FirstBot.java", "public class FirstBot extends AbstractBot {\n}\n");
        files.put("SecondBot.java", "public class SecondBot extends AbstractBot {\n}\n");
        files.put("Client.java", client);
        return files;
    }

    @Test
    void castToClassThatValueCanNeverBeIsFound() {
        List<Violation> violations = RuleTests.checkProject(new ImpossibleCastRule(), bots("""
                @Service
                @RequiredArgsConstructor
                public class Client {
                    private TelegramBot bot;
                    private final Sender sender;
                    @Autowired
                    public void setBot(@Lazy TelegramDevBot bot) {
                        this.bot = bot;
                    }
                    public void send(String chat, String text) {
                        if (type.equals("longpooling")) {
                            ((TelegramDevBot) bot).execute(chat, text);
                        } else {
                            ((TelegramProductionBot) bot).execute(chat, text);
                        }
                    }
                    public void local() {
                        AbstractBot first = new FirstBot();
                        SecondBot second = (SecondBot) first;
                        var third = new FirstBot();
                        TelegramBot alias = third;
                        ((SecondBot) alias).run();
                    }
                }
                """));
        assertThat(violations).extracting(Violation::line).containsExactly(14, 19, 22);
        assertThat(violations.get(0).message())
                .contains("'(TelegramProductionBot) bot'", "только TelegramDevBot", "ClassCastException");
        assertThat(violations).allMatch(violation -> violation.confidenceOrDefault() == Confidence.PROBABLE);
    }

    @Test
    void castToSameClassAncestorOrDescendantIsFine() {
        assertThat(RuleTests.checkProject(new ImpossibleCastRule(), bots("""
                @Service
                public class Client {
                    private TelegramBot bot = new TelegramDevBot();
                    private TelegramBot other;
                    public void configure(boolean test) {
                        if (test) {
                            this.bot = new TestBot();
                        }
                        other = new FirstBot();
                        other = new SecondBot();
                    }
                    public void send() {
                        ((TelegramDevBot) bot).execute();
                        // наследник: приведение удастся, когда в поле лежит он
                        ((TestBot) bot).execute();
                        ((AbstractBot) other).execute();
                        // одно из значений подходит
                        ((SecondBot) other).execute();
                        // интерфейс может реализовать и наследник
                        ((Runnable) bot).run();
                    }
                }
                """))).isEmpty();
    }

    @Test
    void castOfValueThatIsNotFullyKnownIsNotJudged() {
        assertThat(RuleTests.checkProject(new ImpossibleCastRule(), bots("""
                @Service
                public class Client {
                    private TelegramBot injected = new TelegramDevBot();
                    @Autowired
                    private TelegramBot autowired = new TelegramDevBot();
                    protected TelegramBot inherited = new TelegramDevBot();
                    private TelegramBot created = new TelegramDevBot();
                    private TelegramBot shared = new TelegramDevBot();
                    private TelegramBot checked = new TelegramDevBot();
                    public Client(TelegramBot bot, BotFactory factory) {
                        // значение приходит параметром, объявленным интерфейсом
                        this.injected = bot;
                        this.created = factory.create();
                    }
                    public void copy(Client another) {
                        another.shared = this.created;
                    }
                    public <TelegramProductionBot extends TelegramBot> void generic(TelegramBot parameter) {
                        TelegramProductionBot result = (TelegramProductionBot) checked;
                    }
                    public void send(TelegramBot parameter, List<TelegramBot> bots) {
                        ((TelegramProductionBot) injected).execute();
                        ((TelegramProductionBot) autowired).execute();
                        ((TelegramProductionBot) inherited).execute();
                        ((TelegramProductionBot) created).execute();
                        ((TelegramProductionBot) shared).execute();
                        ((TelegramProductionBot) parameter).execute();
                        if (checked instanceof TelegramProductionBot) {
                            ((TelegramProductionBot) checked).execute();
                        }
                        if (checked.getClass() == TelegramProductionBot.class) {
                            ((TelegramProductionBot) checked).execute();
                        }
                        for (TelegramBot each : bots) {
                            each = new TelegramDevBot();
                        }
                        for (TelegramBot each : bots) {
                            ((TelegramProductionBot) each).execute();
                        }
                    }
                }
                """))).isEmpty();
        // Поля объектов с данными заполняет и библиотека - в обход кода класса
        assertThat(RuleTests.checkProject(new ImpossibleCastRule(), bots("""
                @Data
                public class Client {
                    private TelegramBot bot = new TelegramDevBot();
                    public void send() {
                        ((TelegramProductionBot) bot).execute();
                    }
                }
                """))).isEmpty();
    }

    @Test
    void castInPlainClassIsOnlySuspicion() {
        List<Violation> violations = RuleTests.checkProject(new ImpossibleCastRule(), bots("""
                public class Client {
                    private TelegramBot bot = new TelegramDevBot();
                    public void send() {
                        ((TelegramProductionBot) bot).execute();
                    }
                }
                """));
        assertThat(violations).extracting(Violation::line).containsExactly(4);
        assertThat(violations.get(0).confidenceOrDefault()).isEqualTo(Confidence.SUSPICION);
    }

    @Test
    void castIsNotJudgedWhenNameBelongsToLibraryClass() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("Message.java", "package ru.shop.model;\npublic class Message {\n}\n");
        files.put("Letter.java", "package ru.shop.model;\npublic class Letter {\n}\n");
        files.put("Client.java", """
                package ru.shop.client;
                import org.telegram.Message;
                import ru.shop.model.Letter;
                public class Client {
                    public void send() {
                        Object letter = new Letter();
                        // Message здесь - класс библиотеки, а не одноименный класс проекта
                        Message message = (Message) letter;
                    }
                }
                """);
        assertThat(RuleTests.checkProject(new ImpossibleCastRule(), files)).isEmpty();
    }

    private static String timed(String imports, String body) {
        return imports + """
                public class Benchmark {
                    public Object call(Supplier<Object> action) {
                        StopWatch timer = new StopWatch();
                        timer.start();
                %s
                    }
                }
                """.formatted(body.indent(8).stripTrailing());
    }

    private static final String SPRING = "import org.springframework.util.StopWatch;\n";

    @Test
    void stopwatchStoppedInTryAndInFinallyIsFound() {
        List<Violation> violations = RuleTests.check(new StopWatchStoppedTwiceRule(), SPRING + """
                public class Benchmark {
                    private final Stopwatch total = Stopwatch.createUnstarted();
                    public Object first(Supplier<Object> action) {
                        StopWatch timer = new StopWatch();
                        timer.start();
                        try {
                            Object result = action.get();
                            timer.stop();
                            return result;
                        } finally {
                            timer.stop();
                        }
                    }
                    public Object second(Supplier<Object> action) {
                        StopWatch timer = new StopWatch();
                        try {
                            timer.start("first");
                            action.get();
                            timer.stop();
                        } catch (RuntimeException exception) {
                            throw exception;
                        } finally {
                            timer.stop();
                            log.info("done in {}", timer.getTotalTimeMillis());
                        }
                        return null;
                    }
                }
                """);
        assertThat(violations).extracting(Violation::line).containsExactly(12);
        assertThat(violations.get(0).message())
                .contains("'timer.stop()'", "IllegalStateException", "на строках 24.");
    }

    @Test
    void stopwatchStoppedOnceOrRestartedIsFine() {
        StopWatchStoppedTwiceRule rule = new StopWatchStoppedTwiceRule();
        // Останавливают только в finally
        assertThat(RuleTests.check(rule, timed(SPRING, """
                try {
                    return action.get();
                } finally {
                    timer.stop();
                }
                """))).isEmpty();
        // После остановки запускают следующий этап
        assertThat(RuleTests.check(rule, timed(SPRING, """
                try {
                    action.get();
                    timer.stop();
                    timer.start("second");
                    return action.get();
                } finally {
                    timer.stop();
                }
                """))).isEmpty();
        // Этап запускают в самом finally
        assertThat(RuleTests.check(rule, timed(SPRING, """
                try {
                    Object result = action.get();
                    timer.stop();
                    return result;
                } finally {
                    timer.start("cleanup");
                    cleanup();
                    timer.stop();
                }
                """))).isEmpty();
        // Остановка под проверкой
        assertThat(RuleTests.check(rule, timed(SPRING, """
                try {
                    Object result = action.get();
                    timer.stop();
                    return result;
                } finally {
                    if (timer.isRunning()) {
                        timer.stop();
                    }
                }
                """))).isEmpty();
        // Остановка в try - только на одной из веток
        assertThat(RuleTests.check(rule, timed(SPRING, """
                try {
                    if (action == null) {
                        timer.stop();
                        return null;
                    }
                    return action.get();
                } finally {
                    stopQuietly(timer);
                }
                """))).isEmpty();
    }

    @Test
    void stopwatchOfUnknownLibraryIsNotJudged() {
        String body = """
                try {
                    Object result = action.get();
                    timer.stop();
                    return result;
                } finally {
                    timer.stop();
                }
                """;
        StopWatchStoppedTwiceRule rule = new StopWatchStoppedTwiceRule();
        assertThat(RuleTests.check(rule, timed(SPRING, body))).hasSize(1);
        assertThat(RuleTests.check(rule, timed("import org.apache.commons.lang3.time.*;\n", body))).hasSize(1);
        // У секундомера perf4j повторный stop() разрешен; свой класс проекта тоже неизвестен
        assertThat(RuleTests.check(rule, timed("import org.perf4j.StopWatch;\n", body))).isEmpty();
        assertThat(RuleTests.check(rule, timed("", body))).isEmpty();
    }
}
