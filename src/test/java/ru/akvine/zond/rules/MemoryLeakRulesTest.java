package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.resources.SoftReferenceCacheRule;
import ru.akvine.zond.rules.resources.StringInternRule;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила jr:472 и jr:473: String.intern() на данных и самодельные кеши на мягких ссылках
 */
class MemoryLeakRulesTest {

    @Test
    void internOnDataGrowsStringPool() {
        List<Violation> violations = RuleTests.check(new StringInternRule(), """
                class Sample {
                    private static final String PREFIX = "order-";
                    private static final String KEY = ("a" + "b").intern();
                    String normalize(String raw) {
                        return raw.trim().intern();
                    }
                    void load(List<Row> rows) {
                        for (Row row : rows) {
                            names.add(row.getName().intern());
                        }
                    }
                    String constant() {
                        return PREFIX.intern() + "x".intern();
                    }
                    Symbol own(SymbolTable table, Symbol symbol) {
                        Interner<String> interner = Interners.newWeakInterner();
                        interner.intern("x");
                        return symbol.intern();
                    }
                }
                """);

        // Константы уже лежат в пуле, а intern() с аргументом и на своем типе - это не String.intern()
        assertThat(violations).extracting(Violation::line).containsExactly(5, 9);
        assertThat(violations.get(0).message()).contains("'raw.trim().intern()'", "пул строк", "Caffeine");
        assertThat(violations.get(1).message()).contains("'row.getName().intern()' в цикле");
    }

    @Test
    void softReferencesMakeCacheUnpredictable() {
        List<Violation> violations = RuleTests.check(new SoftReferenceCacheRule(), """
                class Sample {
                    private final Map<SoftReference<String>, Product> byKey = new HashMap<>();
                    private final Map<String, SoftReference<Product>> products = new ConcurrentHashMap<>();
                    private SoftReference<Settings> settings;
                    private final Map<String, Product> soft = new ConcurrentReferenceHashMap<>();
                    private final Map<String, Product> weak = new ConcurrentReferenceHashMap<>(16, ReferenceType.WEAK);
                    private final Cache<String, Product> guava = CacheBuilder.newBuilder().softValues().build();
                    private final Cache<String, Product> bounded = Caffeine.newBuilder().maximumSize(1000).build();
                    private final Map<String, WeakReference<Product>> listeners = new HashMap<>();
                    void local() {
                        SoftReference<byte[]> buffer = new SoftReference<>(new byte[1024]);
                        Map<String, Product> temporary = new ConcurrentReferenceHashMap<>();
                    }
                }
                """);

        // Локальная ссылка кешем не является; слабые ссылки и кеш с пределом размера - другой случай
        assertThat(violations).extracting(Violation::line).containsExactly(2, 3, 4, 5, 7);
        assertThat(violations.get(0).message()).contains("ключ карты 'byKey'", "equals и hashCode");
        assertThat(violations.get(0).errorLevel()).isEqualTo(ErrorLevel.MAJOR);
        assertThat(violations.get(1).message()).contains("Поле 'products'", "очищает все мягкие ссылки разом", "Caffeine");
        assertThat(violations.get(1).errorLevel()).isEqualTo(ErrorLevel.MINOR);
        assertThat(violations.get(3).message()).contains("ConcurrentReferenceHashMap");
        assertThat(violations.get(4).message()).contains("softValues()");
    }
}
