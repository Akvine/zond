package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.codesmell.LegacySynchronizedCollectionRule;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правило jr:426: Hashtable, Vector, Stack и обертки Collections.synchronized... в общих полях
 */
class LegacySynchronizedCollectionRuleTest {
    private final LegacySynchronizedCollectionRule rule = new LegacySynchronizedCollectionRule();

    @Test
    void legacyCollections() {
        String code = """
                class Registry {
                    private final Map<String, Client> clients = new Hashtable<>();
                    List<Order> orders() {
                        List<Order> result = new Vector<>();
                        Stack<Node> path = new Stack<>();
                        return result;
                    }
                    Properties settings() {
                        return new Properties();
                    }
                }
                """;

        List<Violation> violations = RuleTests.check(rule, code);
        assertThat(violations).extracting(Violation::line).containsExactly(2, 4, 5);
        assertThat(violations.get(0).message()).contains("new Hashtable", "NullPointerException", "ConcurrentHashMap");
        assertThat(violations.get(2).message()).contains("ArrayDeque");
    }

    @Test
    void legacyApiRequiresHashtableOrVector() {
        // JNDI принимает окружение только как Hashtable, модели Swing - Vector
        String code = """
                class Directory {
                    DirContext connect(String url) throws NamingException {
                        Hashtable<String, Object> env = new Hashtable<>();
                        env.put(Context.PROVIDER_URL, url);
                        return new InitialDirContext(env);
                    }
                    Context lookup() throws NamingException {
                        return new InitialContext(new Hashtable<>(Map.of()));
                    }
                    TableModel model(Vector<Vector<Object>> rows) {
                        Vector<String> columns = new Vector<>(List.of("id", "name"));
                        return new DefaultTableModel(rows, columns);
                    }
                }
                """;

        assertThat(RuleTests.lines(rule, code)).isEmpty();
    }

    @Test
    void synchronizedWrapperInSharedField() {
        String code = """
                @Service
                class SessionRegistry {
                    private final List<Session> sessions = Collections.synchronizedList(new ArrayList<>());
                    private final Map<String, Session> byId;
                    private final Map<String, Session> recent = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
                        protected boolean removeEldestEntry(Map.Entry<String, Session> eldest) {
                            return size() > 100;
                        }
                    });
                    private final Map<Object, Object> identity = Collections.synchronizedMap(new IdentityHashMap<>());
                    SessionRegistry() {
                        this.byId = Collections.synchronizedMap(new HashMap<>());
                    }
                    List<Session> snapshot() {
                        List<Session> copy = Collections.synchronizedList(new ArrayList<>(sessions));
                        return copy;
                    }
                }
                class Holder {
                    static final Set<String> NAMES = Collections.synchronizedSet(new HashSet<>());
                    private final Set<String> own = Collections.synchronizedSet(new HashSet<>());
                }
                """;

        // LRU на LinkedHashMap и IdentityHashMap замены в java.util.concurrent не имеют; локальная копия
        // и поле обычного объекта (не бина) общими не являются
        List<Violation> violations = RuleTests.check(rule, code);
        assertThat(violations).extracting(Violation::line).containsExactly(3, 12, 20);
        assertThat(violations.get(0).message()).contains("'sessions'", "CopyOnWriteArrayList");
        assertThat(violations.get(1).message()).contains("'byId'", "ConcurrentHashMap");
        assertThat(violations.get(2).message()).contains("'NAMES'", "ConcurrentHashMap.newKeySet()");
    }

    @Test
    void staticFieldOfEnum() {
        // Как FeatureCatalog в Apereo CAS: статическое поле перечисления общее для всех потоков
        String code = """
                interface CasFeatureModule {
                    enum FeatureCatalog {
                        X509, Reports;
                        private static final Set<String> PRESENT_FEATURES = Collections.synchronizedSet(new TreeSet<>());
                    }
                }
                """;

        assertThat(RuleTests.check(rule, code)).singleElement().satisfies(violation -> {
            assertThat(violation.line()).isEqualTo(4);
            assertThat(violation.message()).contains("ConcurrentSkipListSet");
        });
    }

    @Test
    void prototypeBeanAndTestsAreSkipped() {
        String code = """
                @Component
                @Scope("prototype")
                class Builder {
                    private final List<String> parts = Collections.synchronizedList(new ArrayList<>());
                }
                class RegistryTest {
                    @Test
                    void stores() {
                        Map<String, String> table = new Hashtable<>();
                    }
                }
                """;

        assertThat(RuleTests.lines(rule, code)).isEmpty();
    }
}
