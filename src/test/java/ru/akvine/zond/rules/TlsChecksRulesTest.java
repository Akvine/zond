package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.rules.security.InsecureRandomRule;
import ru.akvine.zond.rules.security.TrustAllSslRule;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * jr:140 и соседние правила: проверку сертификата и имени хоста отключают не только пустым методом и лямбдой в
 * аргументе - отказ проверки глотают, "доверять всем" прячут в константу, метод или флаг конструктора
 */
class TlsChecksRulesTest {

    @Test
    void trustManagerThatSwallowsRejectionIsFound() {
        List<Violation> violations = RuleTests.check(new TrustAllSslRule(), """
                class FallbackTrustManager implements X509TrustManager {
                    private final X509TrustManager own;
                    private final X509TrustManager system;
                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String type) {
                        try {
                            own.checkServerTrusted(chain, type);
                        } catch (CertificateException e) {
                            try {
                                system.checkServerTrusted(chain, type);
                            } catch (CertificateException second) {
                                log.error("Server check failed: {}", second.getMessage());
                            }
                        }
                    }
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String type) {
                        try {
                            for (X509Certificate certificate : chain) {
                                certificate.checkValidity();
                            }
                        } catch (Exception e) {
                            log.warn("ignored", e);
                        }
                    }
                }
                """);
        assertThat(violations).extracting(Violation::line).containsExactly(8, 22);
        assertThat(violations.get(0).message())
                .contains("checkServerTrusted(...) перехватывает CertificateException", "любой сертификат");
        assertThat(violations.get(0).errorLevel()).isEqualTo(ErrorLevel.CRITICAL);
    }

    @Test
    void trustManagerThatLetsRejectionOutIsFine() {
        assertThat(RuleTests.check(new TrustAllSslRule(), """
                class FallbackTrustManager implements X509TrustManager {
                    private final X509TrustManager own;
                    private final X509TrustManager system;
                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String type) throws CertificateException {
                        try {
                            own.checkServerTrusted(chain, type);
                        } catch (CertificateException e) {
                            // запасная проверка: ее отказ уходит вызывающему
                            system.checkServerTrusted(chain, type);
                        }
                    }
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String type) throws CertificateException {
                        try {
                            own.checkClientTrusted(chain, type);
                        } catch (CertificateException e) {
                            try {
                                system.checkClientTrusted(chain, type);
                            } catch (CertificateException second) {
                                throw new CertificateException("Client is not trusted", second);
                            }
                        }
                    }
                    void describe(X509Certificate[] chain) {
                        try {
                            log.debug("chain of {}", chain.length);
                        } catch (Exception e) {
                            log.debug("not printed");
                        }
                    }
                }
                class Pinned implements X509TrustManager {
                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String type) throws CertificateException {
                        try {
                            log.debug("Checking {}", chain[0].getSubjectX500Principal());
                        } catch (Exception e) {
                            log.debug("subject is not printed");
                        }
                        delegate.checkServerTrusted(chain, type);
                    }
                }
                """)).isEmpty();
    }

    @Test
    void hostnameVerifierThatAcceptsAnyHostIsFoundInAllForms() {
        assertThat(RuleTests.lines(new TrustAllSslRule(), """
                class BotConfig {
                    private static final HostnameVerifier ANY_HOST = (host, session) -> true;
                    private HostnameVerifier hostnameVerifier() {
                        return (host, session) -> true;
                    }
                    RestTemplate sender() {
                        return ClientBuilder.create(this::sslContext, this::hostnameVerifier).build();
                    }
                    void legacy(HttpsURLConnection connection) {
                        connection.setHostnameVerifier(ANY_HOST);
                        HostnameVerifier local = (host, session) -> {
                            return true;
                        };
                        HttpsURLConnection.setDefaultHostnameVerifier(local);
                        connection.setHostnameVerifier(new HostnameVerifier() {
                            @Override
                            public boolean verify(String hostname, SSLSession session) {
                                return true;
                            }
                        });
                    }
                }
                """)).containsExactly(4, 10, 14, 16);
    }

    @Test
    void realHostnameCheckIsFine() {
        assertThat(RuleTests.lines(new TrustAllSslRule(), """
                class Verifiers {
                    private static final HostnameVerifier DEFAULT = HttpsURLConnection.getDefaultHostnameVerifier();
                    private final HostnameVerifier internal = (host, session) -> host.endsWith(".corp.example");
                    HostnameVerifier hostnameVerifier() {
                        return (host, session) -> DEFAULT.verify(host, session);
                    }
                    void apply(HttpsURLConnection connection) {
                        connection.setHostnameVerifier(internal);
                        connection.setHostnameVerifier(new HostnameVerifier() {
                            @Override
                            public boolean verify(String hostname, SSLSession session) {
                                return hostname.equals(session.getPeerHost());
                            }
                        });
                    }
                    // не проверка имени хоста: метод с тем же именем у своего класса
                    boolean verify(String signature, Document document) {
                        return true;
                    }
                }
                """)).isEmpty();
    }

    private static final String CONNECTIONS = """
            public class SecureConnections {
                private static final HostnameVerifier NO_VERIFY = (host, session) -> true;
                private final boolean verifyHostname;
                public SecureConnections(Resource keyStore, String password, boolean verifyHostname) {
                    this.verifyHostname = verifyHostname;
                    load(keyStore, password);
                }
                private void load(Resource keyStore, String password) {
                    if (!verifyHostname) {
                        HttpsURLConnection.setDefaultHostnameVerifier(NO_VERIFY);
                    }
                }
            }
            """;

    @Test
    void checkSwitchedOffByConstructorArgumentIsFoundWhereObjectIsCreated(@TempDir Path project) {
        List<Violation> violations = new ProjectFixture(project)
                .source("SecureConnections.java", CONNECTIONS)
                .source("Clients.java", """
                        @Configuration
                        public class Clients {
                            @Bean
                            public SecureConnections bank(Resource keyStore) {
                                return new SecureConnections(keyStore, "changeit", true);
                            }
                            @Bean
                            public SecureConnections sms(Resource keyStore) {
                                return new SecureConnections(keyStore, "changeit", false);
                            }
                            @Bean
                            public SecureConnections mail(Resource keyStore, MailProperties properties) {
                                return new SecureConnections(keyStore, "changeit", properties.isVerifyHostname());
                            }
                        }
                        """)
                .check(new TrustAllSslRule());
        assertThat(violations).extracting(violation -> violation.file().getFileName() + ":" + violation.line())
                .containsExactly("Clients.java:9");
        assertThat(violations.get(0).message())
                .contains("new SecureConnections(...) с verifyHostname=false", "SecureConnections.java:10");
    }
    @Test
    void codeFromCommonsLangGeneratorIsFound() {
        List<Violation> violations = RuleTests.check(new InsecureRandomRule(), """
                import org.apache.commons.lang.RandomStringUtils;
                class AuthCodeService {
                    String next(int length) {
                        return RandomStringUtils.randomAlphanumeric(length);
                    }
                }
                class Coupons {
                    String label() {
                        return RandomStringUtils.randomAlphabetic(4);
                    }
                    void issue(Invite invite) {
                        String value = RandomStringUtils.randomNumeric(6);
                        invite.setInviteCode(value);
                    }
                }
                """);
        assertThat(violations).extracting(Violation::line).containsExactly(4, 12);
        // Имя класса вокруг - догадка; значение, которое ушло в setInviteCode, - путь, видный по коду
        assertThat(violations.get(0).confidenceOrDefault()).isNotEqualTo(Confidence.CONFIRMED);
        assertThat(violations.get(1).confidenceOrDefault()).isEqualTo(Confidence.PROBABLE);
        assertThat(violations.get(1).message()).contains("setInviteCode", "SecureRandom");
    }

    @Test
    void codeFromOwnWrapperIsFoundWhereItIsUsed() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("Codes.java", """
                import org.apache.commons.lang.RandomStringUtils;
                public class Codes {
                    public static String newCode(int length) {
                        return RandomStringUtils.randomAlphanumeric(length);
                    }
                }
                """);
        files.put("BotLinkService.java", """
                public class BotLinkService {
                    public Link link(Client client) {
                        String value = Codes.newCode(8);
                        Link link = new Link();
                        link.setAuthCode(value);
                        return link;
                    }
                    public String fileName() {
                        return Codes.newCode(8) + ".tmp";
                    }
                }
                """);
        List<Violation> violations = RuleTests.checkProject(new InsecureRandomRule(), files);
        assertThat(violations).extracting(violation -> violation.file().getFileName() + ":" + violation.line())
                .containsExactly("BotLinkService.java:3");
        assertThat(violations.get(0).message()).contains("Codes.newCode(8)", "setAuthCode", "обычного генератора");
        assertThat(violations.get(0).confidenceOrDefault()).isEqualTo(Confidence.PROBABLE);
    }

    @Test
    void secureOrUnknownCommonsGeneratorIsNotCalledPredictableForSure() {
        List<Violation> violations = RuleTests.check(new InsecureRandomRule(), """
                import org.apache.commons.lang3.RandomStringUtils;
                class Invites {
                    void issue(Invite invite) {
                        invite.setInviteCode(RandomStringUtils.secure().nextAlphanumeric(12));
                        String strong = RandomStringUtils.secureStrong().nextNumeric(6);
                        invite.setPinCode(strong);
                        String legacy = RandomStringUtils.randomNumeric(6);
                        invite.setSmsCode(legacy);
                        String weak = RandomStringUtils.insecure().nextNumeric(6);
                        invite.setResetCode(weak);
                    }
                }
                """);
        // secure() и secureStrong() - не находка; статический метод третьей версии - только подозрение
        assertThat(violations).extracting(Violation::line).containsExactly(7, 9);
        assertThat(violations.get(0).confidenceOrDefault()).isNotEqualTo(Confidence.PROBABLE);
        assertThat(violations.get(0).message()).contains("до версии 3.15");
        assertThat(violations.get(1).confidenceOrDefault()).isEqualTo(Confidence.PROBABLE);
    }
}
