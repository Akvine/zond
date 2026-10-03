package ru.akvine.zond.rules;

import com.github.javaparser.StaticJavaParser;
import org.junit.jupiter.api.Test;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CheckMutableStateInSingletonBeanRuleTest {
    private final CheckMutableStateInSingletonBeanRule rule = new CheckMutableStateInSingletonBeanRule();

    @Test
    void findsFieldsModifiedInMethods() {
        List<Violation> violations = rule.check(parse("""
                @Service
                class OrderService {
                    private int counter;
                    private User currentUser;
                    private String first, second;

                    public void handle(User user) {
                        counter++;
                        this.currentUser = user;
                        items.forEach(item -> second = item);
                    }

                    public void reset() {
                        counter = 0;
                    }
                }

                @org.springframework.stereotype.Component
                @Scope("singleton")
                class Cache {
                    private long total;

                    void add(long value) {
                        total += value;
                    }
                }
                """));

        assertThat(violations).extracting(Violation::line).containsExactly(3, 4, 5, 21);
        assertThat(violations).allMatch(violation -> violation.ruleCode().equals("jr:7"));
        assertThat(violations).allMatch(violation -> violation.errorLevel() == ErrorLevel.CRITICAL);
        assertThat(violations).allMatch(violation -> violation.errorType() == ErrorType.CONCURRENCY);
        assertThat(violations.get(0).message()).contains("'counter'", "'OrderService'", "'handle', 'reset'");
        assertThat(violations.get(2).message()).contains("'second'").doesNotContain("'first'");
    }

    @Test
    void ignoresFieldsThatAreNotSharedMutableState() {
        List<Violation> violations = rule.check(parse("""
                @Service
                class OrderService {
                    private final Repository repository;
                    private static int instances;
                    private volatile boolean stopped;
                    private int neverChanged;
                    private int shadowed;
                    private int underLock;
                    private int inSynchronizedMethod;
                    private Clock clock;
                    private Map<String, String> cache;
                    @Value("${timeout}")
                    private int timeout;

                    OrderService(Repository repository) {
                        this.repository = repository;
                        this.neverChanged = 1;
                    }

                    @Autowired
                    void setClock(Clock clock) {
                        this.clock = clock;
                    }

                    @PostConstruct
                    void init() {
                        cache = new HashMap<>();
                    }

                    void work(int shadowed) {
                        shadowed = 5;
                        instances++;
                        stopped = true;
                        timeout = 1;
                        int local = 0;
                        local++;
                        other.value = 2;
                        synchronized (this) {
                            underLock++;
                        }
                    }

                    synchronized void locked() {
                        inSynchronizedMethod++;
                    }
                }
                """));

        assertThat(violations).isEmpty();
    }

    @Test
    void ignoresClassesThatAreNotSingletonBeans() {
        List<Violation> violations = rule.check(parse("""
                class Plain {
                    private int counter;
                    void inc() { counter++; }
                }

                @Component
                @Scope("prototype")
                class Prototype {
                    private int counter;
                    void inc() { counter++; }
                }

                @Component
                @Scope(value = WebApplicationContext.SCOPE_REQUEST, proxyMode = ScopedProxyMode.TARGET_CLASS)
                class RequestBean {
                    private int counter;
                    void inc() { counter++; }
                }

                @Component
                @RequestScope
                class RequestScoped {
                    private int counter;
                    void inc() { counter++; }
                }

                @Component
                @ConfigurationProperties("app")
                class Properties {
                    private int size;
                    public void setSize(int value) { this.size = value; }
                }
                """));

        assertThat(violations).isEmpty();
    }

    private SourceFile parse(String code) {
        return new SourceFile(Path.of("OrderService.java"), StaticJavaParser.parse(code));
    }
}
