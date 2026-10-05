package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.performance.InMemoryWorkbookRule;
import ru.akvine.zond.rules.security.CommandInjectionRule;
import ru.akvine.zond.rules.security.DisabledSecurityRule;
import ru.akvine.zond.rules.security.HeaderInjectionRule;
import ru.akvine.zond.rules.security.InsecureRandomRule;
import ru.akvine.zond.rules.security.RequestBodyWithoutValidRule;
import ru.akvine.zond.rules.security.SeededSecureRandomRule;
import ru.akvine.zond.rules.security.TrustAllSslRule;
import ru.akvine.zond.rules.security.UnboundedRequestCollectionRule;
import ru.akvine.zond.rules.security.UnsafeDeserializationRule;
import ru.akvine.zond.rules.security.WeakHashRule;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила jr:106 - jr:111, jr:135 и jr:137 - jr:140: безопасность
 */
class SecurityRulesTest {

    @Test
    void weakHash() {
        assertThat(RuleTests.lines(new WeakHashRule(), """
                class Sample {
                    void run(String input) throws Exception {
                        MessageDigest.getInstance("MD5");
                        hash(input, "SHA-1");
                        DigestUtils.md5Hex(input);
                        MessageDigest.getInstance("SHA-256");
                        String name = "MD5";
                        Set<String> names = Set.of("md5", "sha1");
                    }
                }
                """)).containsExactly(3, 4, 5);
    }

    @Test
    void disabledSecurity() {
        List<Violation> violations = RuleTests.check(new DisabledSecurityRule(), """
                class Config {
                    void configure(HttpSecurity http, CorsRegistry registry) throws Exception {
                        http.csrf(AbstractHttpConfigurer::disable);
                        http.csrf(csrf -> csrf.disable());
                        http.csrf().disable();
                        http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
                        registry.addMapping("/**").allowedOrigins("*");
                        http.authorizeHttpRequests(auth -> auth.requestMatchers("/health").permitAll().anyRequest().authenticated());
                        http.csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"));
                        registry.addMapping("/**").allowedOrigins("https://example.com");
                    }
                }
                @CrossOrigin
                class OpenController {
                }
                @CrossOrigin(origins = "https://example.com")
                class ClosedController {
                }
                """);

        assertThat(violations).extracting(Violation::line).containsExactly(3, 4, 5, 6, 7, 13);
        assertThat(violations.get(0).message()).contains("CSRF");
        assertThat(violations.get(3).message()).contains("permitAll");
        assertThat(violations.get(4).message()).contains("CORS");
    }

    @Test
    void headerInjection() {
        List<Violation> violations = RuleTests.check(new HeaderInjectionRule(), """
                class Sample {
                    ResponseEntity<byte[]> run(String fileName, HttpServletResponse response) {
                        response.setHeader("Content-Disposition", "attachment; filename=" + fileName + ";");
                        response.setHeader("X-Name", "v=" + URLEncoder.encode(fileName, UTF_8));
                        response.setHeader("X-Type", "text/" + SUBTYPE);
                        response.setHeader("X-Raw", fileName);
                        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + fileName).body(null);
                    }
                }
                """);

        assertThat(violations).extracting(Violation::line).containsExactly(3, 7);
        assertThat(violations.get(0).message()).contains("'fileName'");
    }

    @Test
    void seededSecureRandom() {
        assertThat(RuleTests.lines(new SeededSecureRandomRule(), """
                class Sample {
                    void run(byte[] seed, Config config) throws Exception {
                        SecureRandom random = SecureRandom.getInstance("SHA1PRNG");
                        random.setSeed(seed);
                        SecureRandom other = new SecureRandom(seed);
                        SecureRandom safe = new SecureRandom();
                        config.setSeed("x");
                        Random plain = new Random(42);
                        plain.setSeed(1);
                    }
                }
                """)).containsExactly(4, 5);
    }

    @Test
    void inMemoryWorkbook() {
        assertThat(RuleTests.lines(new InMemoryWorkbookRule(), """
                class Sample {
                    void run(InputStream in) throws Exception {
                        Workbook a = new XSSFWorkbook();
                        Workbook b = new XSSFWorkbook(in);
                        Workbook c = new SXSSFWorkbook();
                    }
                }
                """)).containsExactly(3);
    }

    @Test
    void unboundedRequestCollection() {
        assertThat(RuleTests.lines(new UnboundedRequestCollectionRule(), """
                class GenerateRequest {
                    private List<Column> columns;
                    @Size(max = 100)
                    private List<String> names;
                    private String title;
                }
                class ValidatedDto {
                    @NotBlank
                    private String name;
                    private Map<String, String> attributes;
                }
                class OrderResponse {
                    private List<Item> items;
                }
                """)).containsExactly(2, 10);
    }

    @Test
    void requestBodyWithoutValid() {
        assertThat(RuleTests.lines(new RequestBodyWithoutValidRule(), """
                class Controller {
                    void create(@RequestBody OrderDto order) {}
                    void update(@Valid @RequestBody OrderDto order) {}
                    void patch(@RequestBody @Validated OrderDto order) {}
                    void raw(@RequestBody String body) {}
                }
                """)).containsExactly(2);
    }

    @Test
    void insecureRandom() {
        assertThat(RuleTests.lines(new InsecureRandomRule(), """
                class TokenService {
                    String next() {
                        return String.valueOf(new Random().nextInt());
                    }
                }
                class Dice {
                    int roll() {
                        int value = new Random().nextInt(6);
                        String sessionToken = "t" + Math.random();
                        return value + new SecureRandom().nextInt();
                    }
                }
                """)).containsExactly(3, 9);
    }

    @Test
    void commandInjection() {
        assertThat(RuleTests.lines(new CommandInjectionRule(), """
                class Sample {
                    void run(String host) throws Exception {
                        Runtime.getRuntime().exec("ping " + host);
                        new ProcessBuilder("sh", "-c", "ping " + host).start();
                        Runtime.getRuntime().exec("ls -la");
                        new ProcessBuilder("ping", host).start();
                    }
                }
                """)).containsExactly(3, 4);
    }

    @Test
    void unsafeDeserialization() {
        assertThat(RuleTests.lines(new UnsafeDeserializationRule(), """
                class Sample {
                    Object run(InputStream in) throws Exception {
                        return new ObjectInputStream(in).readObject();
                    }
                }
                """)).containsExactly(3);
    }

    @Test
    void trustAllSsl() {
        assertThat(RuleTests.lines(new TrustAllSslRule(), """
                class Sample {
                    void run(HttpsURLConnection connection) {
                        connection.setHostnameVerifier((host, session) -> true);
                        HostnameVerifier verifier = NoopHostnameVerifier.INSTANCE;
                        connection.setHostnameVerifier((host, session) -> host.equals("example.com"));
                    }
                    class TrustAll implements X509TrustManager {
                        public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                        public void checkClientTrusted(X509Certificate[] chain, String authType) { verify(chain); }
                    }
                }
                """)).containsExactly(3, 4, 8);
    }
}
