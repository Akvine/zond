package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.rules.concurrency.AtomicGetThenSetRule;
import ru.akvine.zond.rules.concurrency.SleepInLoopRule;
import ru.akvine.zond.rules.concurrency.SynchronizedCollectionIterationRule;
import ru.akvine.zond.rules.concurrency.SynchronizedMethodWithIoRule;
import ru.akvine.zond.rules.exceptions.FutureWithoutErrorHandlingRule;
import ru.akvine.zond.rules.performance.BoxingInLoopRule;
import ru.akvine.zond.rules.performance.KeySetWithGetRule;
import ru.akvine.zond.rules.performance.MapperPerCallRule;
import ru.akvine.zond.rules.performance.RegexInLoopRule;
import ru.akvine.zond.rules.performance.WholeUploadInMemoryRule;
import ru.akvine.zond.rules.security.InsecureCookieRule;
import ru.akvine.zond.rules.security.InsecureTempFileRule;
import ru.akvine.zond.rules.security.JwtWithoutSignatureCheckRule;
import ru.akvine.zond.rules.security.SecretComparisonRule;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила jr:239 - jr:252: производительность, параллелизм, безопасность
 */
class PerformanceConcurrencySecurityRulesTest {

    @Test
    void regexInLoop() {
        assertThat(RuleTests.lines(new RegexInLoopRule(), """
                class Sample {
                    void run(List<String> lines) {
                        for (String line : lines) {
                            line.replaceAll("[0-9]+", " ");
                            line.matches("[a-z]+");
                            line.split(",");
                            Pattern.compile("[0-9]+");
                        }
                        lines.get(0).replaceAll("[0-9]+", "");
                    }
                }
                """)).containsExactly(4, 5, 7);
    }

    @Test
    void mapperPerCall() {
        assertThat(RuleTests.lines(new MapperPerCallRule(), """
                class Sample {
                    private final ObjectMapper shared = new ObjectMapper();
                    String toJson(Object value) throws Exception {
                        return new ObjectMapper().writeValueAsString(value);
                    }
                    @Bean
                    ObjectMapper objectMapper() { return new ObjectMapper(); }
                }
                """)).containsExactly(4);
    }

    @Test
    void keySetWithGet() {
        assertThat(RuleTests.lines(new KeySetWithGetRule(), """
                class Sample {
                    void run(Map<String, Integer> prices) {
                        for (String name : prices.keySet()) {
                            use(name, prices.get(name));
                        }
                        for (String name : prices.keySet()) {
                            use(name);
                        }
                    }
                }
                """)).containsExactly(3);
    }

    @Test
    void wholeUploadInMemory() {
        assertThat(RuleTests.lines(new WholeUploadInMemoryRule(), """
                class Uploads {
                    void store(MultipartFile file, HttpServletRequest request) throws Exception {
                        byte[] content = file.getBytes();
                        byte[] body = request.getInputStream().readAllBytes();
                        byte[] text = "abc".getBytes();
                    }
                }
                """)).containsExactly(3, 4);
    }

    @Test
    void boxingInLoop() {
        assertThat(RuleTests.lines(new BoxingInLoopRule(), """
                class Sample {
                    long total(List<Long> values) {
                        Long sum = 0L;
                        long plain = 0;
                        for (Long value : values) {
                            sum += value;
                            plain += value;
                        }
                        return sum + plain;
                    }
                }
                """)).containsExactly(6);
    }

    @Test
    void futureWithoutErrorHandling() {
        assertThat(RuleTests.lines(new FutureWithoutErrorHandlingRule(), """
                class Sample {
                    void run() {
                        CompletableFuture.runAsync(this::work);
                        CompletableFuture.runAsync(this::work).exceptionally(this::report);
                        CompletableFuture<Void> future = CompletableFuture.runAsync(this::work);
                        CompletableFuture.supplyAsync(this::load).thenAccept(this::use);
                    }
                }
                """)).containsExactly(3, 6);
    }

    @Test
    void synchronizedCollectionIteration() {
        assertThat(RuleTests.lines(new SynchronizedCollectionIterationRule(), """
                class Sample {
                    private final List<String> names = Collections.synchronizedList(new ArrayList<>());
                    void print() {
                        for (String name : names) {
                            use(name);
                        }
                        synchronized (names) {
                            for (String name : names) {
                                use(name);
                            }
                        }
                    }
                }
                """)).containsExactly(4);
    }

    @Test
    void atomicGetThenSet() {
        assertThat(RuleTests.lines(new AtomicGetThenSetRule(), """
                class Sample {
                    private final AtomicInteger counter = new AtomicInteger();
                    void run() {
                        counter.set(counter.get() + 1);
                        counter.incrementAndGet();
                        counter.set(0);
                    }
                }
                """)).containsExactly(4);
    }

    @Test
    void sleepInLoop() {
        assertThat(RuleTests.lines(new SleepInLoopRule(), """
                class Sample {
                    void waitFor() throws Exception {
                        while (!ready()) {
                            Thread.sleep(100);
                        }
                        Thread.sleep(100);
                    }
                }
                """)).containsExactly(4);
    }

    @Test
    void synchronizedMethodWithIo() {
        assertThat(RuleTests.lines(new SynchronizedMethodWithIoRule(), """
                @Service
                class Counters {
                    synchronized void save(Counter counter) {
                        counterRepository.save(counter);
                    }
                    synchronized void increment() {
                        value++;
                    }
                }
                """)).containsExactly(3);
    }

    @Test
    void insecureCookie() {
        assertThat(RuleTests.lines(new InsecureCookieRule(), """
                class Sample {
                    void login(HttpServletResponse response) {
                        Cookie plain = new Cookie("session", "1");
                        response.addCookie(plain);
                        Cookie safe = new Cookie("session", "1");
                        safe.setHttpOnly(true);
                        safe.setSecure(true);
                        ResponseCookie.from("session", "1").httpOnly(true).build();
                        ResponseCookie.from("session", "1").httpOnly(true).secure(true).build();
                    }
                }
                """)).containsExactly(3, 8);
    }

    @Test
    void jwtWithoutSignatureCheck() {
        assertThat(RuleTests.lines(new JwtWithoutSignatureCheckRule(), """
                class Tokens {
                    void read(String token) {
                        parser.parseClaimsJwt(token);
                        parser.parseClaimsJws(token);
                        JWT.decode(token);
                    }
                }
                """)).containsExactly(3, 5);
    }

    @Test
    void secretComparison() {
        assertThat(RuleTests.lines(new SecretComparisonRule(), """
                class Auth {
                    boolean check(String token, String expectedToken, User user, String password, String name) {
                        boolean a = token.equals(expectedToken);
                        boolean b = user.getPassword().equals(password);
                        boolean c = "".equals(token);
                        return name.equals(user.getName());
                    }
                }
                """)).containsExactly(3, 4);
    }

    @Test
    void insecureTempFile() {
        assertThat(RuleTests.lines(new InsecureTempFileRule(), """
                class Sample {
                    void run() throws Exception {
                        File.createTempFile("report", ".tmp");
                        Files.createTempFile("report", ".tmp");
                    }
                }
                """)).containsExactly(3);
    }
}
