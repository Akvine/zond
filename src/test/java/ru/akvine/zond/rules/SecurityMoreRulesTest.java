package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.rules.security.CheckExceptionMessageToClientRule;
import ru.akvine.zond.rules.security.CheckOpenRedirectRule;
import ru.akvine.zond.rules.security.CheckPathTraversalRule;
import ru.akvine.zond.rules.security.CheckSecretInToStringRule;
import ru.akvine.zond.rules.security.CheckSensitiveDataLoggingRule;
import ru.akvine.zond.rules.security.CheckWeakCipherRule;
import ru.akvine.zond.rules.security.CheckXxeRule;
import ru.akvine.zond.rules.security.CheckZipSlipRule;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила jr:149 - jr:156: безопасность
 */
class SecurityMoreRulesTest {

    @Test
    void weakCipher() {
        assertThat(RuleTests.lines(new CheckWeakCipherRule(), """
                class Sample {
                    void run(byte[] key) throws Exception {
                        Cipher a = Cipher.getInstance("DES");
                        Cipher b = Cipher.getInstance("AES/ECB/PKCS5Padding");
                        Cipher c = Cipher.getInstance("AES");
                        IvParameterSpec iv = new IvParameterSpec(new byte[16]);
                        Cipher ok = Cipher.getInstance("AES/GCM/NoPadding");
                        IvParameterSpec random = new IvParameterSpec(generated);
                    }
                }
                """)).containsExactly(3, 4, 5, 6);
    }

    @Test
    void xxe() {
        CheckXxeRule rule = new CheckXxeRule();

        assertThat(RuleTests.lines(rule, """
                class Sample {
                    void run() throws Exception {
                        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                        SAXBuilder builder = new SAXBuilder();
                    }
                }
                """)).containsExactly(3, 4);
        assertThat(RuleTests.lines(rule, """
                class Sample {
                    void run() throws Exception {
                        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                    }
                }
                """)).isEmpty();
    }

    @Test
    void pathTraversal() {
        assertThat(RuleTests.lines(new CheckPathTraversalRule(), """
                class Controller {
                    byte[] download(@RequestParam String name, MultipartFile upload) throws IOException {
                        File file = new File("/data/" + name);
                        Path target = Paths.get("/uploads", upload.getOriginalFilename());
                        File fixed = new File("/data/report.csv");
                        return Files.readAllBytes(base.resolve(name));
                    }
                    byte[] safe(@RequestParam String name) throws IOException {
                        Path path = base.resolve(name).normalize();
                        if (!path.startsWith(base)) {
                            throw new IllegalArgumentException();
                        }
                        return Files.readAllBytes(path);
                    }
                    void internal(String name) {
                        File file = new File("/data/" + name);
                    }
                }
                """)).containsExactly(3, 4, 6);
    }

    @Test
    void openRedirect() {
        assertThat(RuleTests.lines(new CheckOpenRedirectRule(), """
                class Controller {
                    String login(@RequestParam String returnUrl, HttpServletResponse response, Long id) throws IOException {
                        response.sendRedirect(returnUrl);
                        response.sendRedirect(request.getParameter("next"));
                        if (id == null) {
                            return "redirect:" + returnUrl;
                        }
                        response.sendRedirect("/home");
                        return "redirect:/orders/" + id;
                    }
                }
                """)).containsExactly(3, 4, 6);
    }

    @Test
    void exceptionMessageToClient() {
        assertThat(RuleTests.lines(new CheckExceptionMessageToClientRule(), """
                class Handler {
                    @ExceptionHandler(Exception.class)
                    ResponseEntity<String> handle(Exception e) {
                        log.error("Failed: {}", e.getMessage(), e);
                        return ResponseEntity.status(500).body(e.getMessage());
                    }
                    @ExceptionHandler(OrderNotFoundException.class)
                    ResponseEntity<String> notFound(OrderNotFoundException e) {
                        return ResponseEntity.status(404).body(e.getMessage());
                    }
                }
                """)).containsExactly(5);
    }

    @Test
    void secretInToString() {
        assertThat(RuleTests.lines(new CheckSecretInToStringRule(), """
                @Data
                class User {
                    private String name;
                    private String password;
                    @ToString.Exclude
                    private String apiKey;
                }
                class Account {
                    private String token;
                    private String secret;
                    public String toString() {
                        return "Account{token=" + token + "}";
                    }
                }
                """)).containsExactly(4, 9);
    }

    @Test
    void sensitiveDataLogging() {
        assertThat(RuleTests.lines(new CheckSensitiveDataLoggingRule(), """
                class Sample {
                    void run(String password, User user, String name) {
                        log.info("Login {} with {}", name, password);
                        log.debug("Token {}", user.getAccessToken());
                        log.info("Password changed for {}", name);
                        log.info("Using {}", passwordEncoder);
                    }
                }
                """)).containsExactly(3, 4);
    }

    @Test
    void zipSlip() {
        assertThat(RuleTests.lines(new CheckZipSlipRule(), """
                class Sample {
                    void unzip(ZipInputStream zip, Path target) throws IOException {
                        ZipEntry entry = zip.getNextEntry();
                        File file = new File(target.toFile(), entry.getName());
                        String label = entry.getName();
                    }
                    void safe(ZipInputStream zip, Path target) throws IOException {
                        ZipEntry entry = zip.getNextEntry();
                        Path resolved = target.resolve(entry.getName()).normalize();
                        if (!resolved.startsWith(target)) {
                            throw new IOException();
                        }
                    }
                }
                """)).containsExactly(4);
    }
}
