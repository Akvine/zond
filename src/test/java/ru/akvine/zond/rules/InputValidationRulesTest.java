package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.security.FileUploadWithoutValidationRule;
import ru.akvine.zond.rules.security.JndiInjectionRule;
import ru.akvine.zond.rules.security.LdapInjectionRule;
import ru.akvine.zond.rules.security.MassAssignmentRule;
import ru.akvine.zond.rules.security.XPathInjectionRule;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила jr:339 - jr:343: данные запроса в JNDI, LDAP и XPath, загрузка файлов и копирование свойств в сущность
 */
class InputValidationRulesTest {

    @Test
    void jndiNameMustNotComeFromRequest() {
        List<Violation> violations = RuleTests.check(new JndiInjectionRule(), """
                @RestController
                class Resources {
                    private JndiTemplate jndiTemplate;
                    @GetMapping("/resource")
                    Object find(@RequestParam String name) throws Exception {
                        Object first = new InitialContext().lookup(name);
                        Context context = new InitialContext();
                        Object second = context.lookup("java:comp/env/" + name);
                        Object fixed = context.lookup("java:comp/env/jdbc/main");
                        return jndiTemplate.lookup(name);
                    }
                    Object cached(@RequestParam String name) {
                        return cache.lookup(name);
                    }
                }
                """);

        // cache.lookup - не JNDI: объект с таким именем к контексту отношения не имеет
        assertThat(violations).extracting(Violation::line).containsExactly(6, 8, 10);
        assertThat(violations).extracting(Violation::confidence).containsOnly(Confidence.CONFIRMED);
    }

    @Test
    void ldapFilterMustBeEscaped() {
        List<Violation> violations = RuleTests.check(new LdapInjectionRule(), """
                @RestController
                class Users {
                    private LdapTemplate ldapTemplate;
                    private DirContext dirContext;
                    @GetMapping("/users")
                    List<String> find(@RequestParam String login) throws Exception {
                        ldapTemplate.search("ou=people", "(uid=" + login + ")", mapper);
                        dirContext.search("ou=people", "(uid=" + login + ")", controls);
                        ldapTemplate.search("ou=people", "(objectClass=person)", mapper);
                        return ldapTemplate.search(query().where("uid").is(login), mapper);
                    }
                    @GetMapping("/safe")
                    List<String> safe(@RequestParam String login) {
                        String escaped = LdapEncoder.filterEncode(login);
                        return ldapTemplate.search("ou=people", "(uid=" + escaped + ")", mapper);
                    }
                    @GetMapping("/filter")
                    List<String> byFilter(@RequestParam String login) {
                        return ldapTemplate.search("ou=people", new EqualsFilter("uid", login).encode(), mapper);
                    }
                }
                """);

        assertThat(violations).extracting(Violation::line).containsExactly(7, 8);
    }

    @Test
    void xpathExpressionMustNotBeBuiltFromRequest() {
        List<Violation> violations = RuleTests.check(new XPathInjectionRule(), """
                @RestController
                class Catalog {
                    @GetMapping("/items")
                    String find(@RequestParam String name, Document document) throws Exception {
                        XPath xpath = XPathFactory.newInstance().newXPath();
                        String first = xpath.evaluate("//item[@name='" + name + "']", document);
                        XPathExpression expression = xpath.compile("//item[@name='" + name + "']");
                        List<Node> nodes = element.selectNodes("//item[@name='" + name + "']");
                        String fixed = xpath.evaluate("//item[@active='true']", document);
                        return pattern.compile(name).toString();
                    }
                    @GetMapping("/safe")
                    String safe(@RequestParam String name, Document document) throws Exception {
                        XPath xpath = XPathFactory.newInstance().newXPath();
                        xpath.setXPathVariableResolver(variable -> name);
                        return xpath.evaluate("//item[@name=$name]" + name, document);
                    }
                }
                """);

        assertThat(violations).extracting(Violation::line).containsExactly(6, 7, 8);
    }

    @Test
    void uploadedFileTypeMustBeChecked() {
        assertThat(RuleTests.lines(new FileUploadWithoutValidationRule(), """
                @RestController
                class Uploads {
                    @PostMapping("/avatar")
                    void avatar(@RequestParam MultipartFile file) throws IOException {
                        file.transferTo(new File("/data/avatar"));
                    }
                    @PostMapping("/checked")
                    void checked(@RequestParam MultipartFile file) throws IOException {
                        if (!"image/png".equals(file.getContentType())) {
                            throw new IllegalArgumentException();
                        }
                        file.transferTo(new File("/data/avatar"));
                    }
                    @PostMapping("/delegated")
                    void delegated(@RequestParam MultipartFile file) throws IOException {
                        storage.save(file);
                    }
                    @PostMapping("/bytes")
                    void bytes(@RequestParam MultipartFile file) throws IOException {
                        byte[] content = file.getBytes();
                        Files.write(target, content);
                    }
                    @PostMapping("/name")
                    String name(@RequestParam MultipartFile file) {
                        return file.getName();
                    }
                }
                """)).containsExactly(5, 20);
    }

    @Test
    void requestMustNotBeCopiedIntoEntityAsIs() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("User.java", "@Entity class User { private Long id; private String name; private String role; }");
        files.put("UserView.java", "class UserView { private String name; }");
        files.put("UserController.java", """
                @RestController
                class UserController {
                    @PutMapping("/users/{id}")
                    void update(@PathVariable Long id, @RequestBody UserRequest request) {
                        User user = repository.findById(id).orElseThrow();
                        BeanUtils.copyProperties(request, user);
                        UserView view = new UserView();
                        BeanUtils.copyProperties(request, view);
                        User limited = new User();
                        BeanUtils.copyProperties(request, limited, "id", "role");
                    }
                    void copy(User from) {
                        User to = new User();
                        BeanUtils.copyProperties(from, to);
                    }
                }
                """);

        List<Violation> violations = RuleTests.checkProject(new MassAssignmentRule(), files);

        // В обычный объект копировать можно; с перечнем запрещенных свойств копирование ограничено
        assertThat(violations).extracting(Violation::line).containsExactly(6);
        assertThat(violations.get(0).message()).contains("'User'").contains("request");
    }

    @Test
    void apacheBeanUtilsTakesTargetFirst() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("User.java", "@Entity class User { private Long id; }");
        files.put("UserController.java", """
                import org.apache.commons.beanutils.BeanUtils;
                @RestController
                class UserController {
                    @PostMapping("/users")
                    void create(@RequestBody UserRequest request) throws Exception {
                        User user = new User();
                        BeanUtils.copyProperties(user, request);
                    }
                }
                """);

        assertThat(RuleTests.checkProject(new MassAssignmentRule(), files)).extracting(Violation::line).containsExactly(7);
    }
}
