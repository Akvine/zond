package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.loaders.FileSystemConfigLoader;
import ru.akvine.zond.loaders.FileSystemSourceLoader;
import ru.akvine.zond.loaders.FileSystemTextFileLoader;
import ru.akvine.zond.models.GeneratedSources;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.rules.exceptions.BroadCatchRule;
import ru.akvine.zond.rules.logical.CaseWithoutLocaleRule;
import ru.akvine.zond.rules.logical.CastWithoutInstanceofRule;
import ru.akvine.zond.rules.logical.DuplicateEndpointRule;
import ru.akvine.zond.rules.logical.EqualsOnPossibleNullRule;
import ru.akvine.zond.rules.logical.EntityFieldWithoutColumnRule;
import ru.akvine.zond.rules.logical.ConstantConditionRule;
import ru.akvine.zond.rules.logical.IgnoredBooleanResultRule;
import ru.akvine.zond.rules.logical.IndexOutOfBoundsRule;
import ru.akvine.zond.rules.logical.IndexWithoutLengthCheckRule;
import ru.akvine.zond.rules.logical.ListGetFirstWithoutCheckRule;
import ru.akvine.zond.rules.logical.NarrowingCastRule;
import ru.akvine.zond.rules.logical.NullUnboxingRule;
import ru.akvine.zond.rules.logical.NullableDereferenceRule;
import ru.akvine.zond.rules.logical.SwitchWithoutDefaultRule;
import ru.akvine.zond.rules.performance.BlockingCallInReactiveRule;
import ru.akvine.zond.rules.performance.MapDoubleLookupRule;
import ru.akvine.zond.rules.security.PathTraversalRule;
import ru.akvine.zond.rules.streams.ToMapWithoutMergeRule;
import ru.akvine.zond.rules.security.UnboundedRequestCollectionRule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ложные срабатывания, найденные прогоном по открытым проектам (zaproxy, traccar, guava, commons-lang,
 * halo, conductor, mall, eladmin и другим) и по рабочим проектам. В каждом тесте рядом с кодом, о котором
 * правило теперь молчит, стоит код, о котором оно по-прежнему обязано сообщить
 */
class OpenSourceFalsePositivesTest {
    private static final String APPLICATION = """
            package %s;
            @SpringBootApplication
            public class Application {
            }
            """;

    @TempDir
    Path dir;

    @Test
    void substringFromStartAndHandledIndexFailures() {
        String code = """
                class Sample {
                    String cut(String body, String name, int limit) {
                        String shown = body.substring(0, Math.min(body.length(), 100));
                        String head = name.substring(0, limit);
                        return shown + head + name.substring(0, name.length() - 1) + body.substring(0, 10);
                    }
                    String login(String header) {
                        try {
                            String[] auth = header.split(" ");
                            return auth[1];
                        } catch (Exception e) {
                            return null;
                        }
                    }
                    String date(String time) {
                        try {
                            String[] parts = time.split(" ");
                            return parts[1];
                        } catch (IllegalStateException e) {
                            return null;
                        }
                    }
                }
                """;

        // Начало строки есть всегда, а граница Math.min(length, N) за строку не выходит; length() - 1 и
        // литерал 10 в короткой строке не поместятся: это две находки в одной строке, у name и у body.
        // Часть после split в try с catch (Exception) уже обработана, а catch другого исключения от выхода
        // за массив не спасает
        assertThat(RuleTests.lines(new IndexWithoutLengthCheckRule(), code)).containsExactly(5, 5, 18);
    }

    @Test
    void settingsClassIsNotRequestDto() {
        Map<String, String> files = Map.of(
                "KafkaProperties.java", """
                        @ConfigurationProperties("kafka")
                        public class KafkaProperties {
                            @NotEmpty
                            private List<String> bootstrapServers;
                            public static class Ssl {
                                @NotEmpty
                                private List<String> protocols;
                            }
                        }
                        """,
                "RetrySettings.java", """
                        public class RetrySettings {
                            @NotEmpty
                            private List<Integer> codes;
                        }
                        """,
                "ImportForm.java", """
                        public class ImportForm {
                            @NotEmpty
                            private List<String> rows;
                        }
                        """);

        // Настройки читаются из файла приложения; форму с теми же аннотациями присылает клиент
        assertThat(RuleTests.checkProject(new UnboundedRequestCollectionRule(), files))
                .extracting(violation -> violation.file().getFileName() + ":" + violation.line())
                .containsExactly("ImportForm.java:2");
    }

    @Test
    void intendedTruncation() {
        String code = """
                class Sample {
                    private long bits;
                    @Override
                    public int hashCode() {
                        long mixed = bits ^ (bits >>> 32);
                        return (int) mixed;
                    }
                    void encode(ByteBuf buffer, long time, long total) {
                        buffer.writeIntLE((int) time);
                        int rows = (int) total;
                    }
                }
                """;

        // В hashCode и в поле двоичного формата старшие разряды отбрасывают намеренно
        assertThat(RuleTests.lines(new NarrowingCastRule(), code)).containsExactly(10);
    }

    @Test
    void switchWithEveryConstantOrFallback() {
        String code = """
                class Sample {
                    enum State { NEW, DONE }
                    enum Kind { A, B, C }
                    int covered(State state) {
                        switch (state) {
                            case NEW: return 1;
                            case DONE: return 2;
                        }
                        return 0;
                    }
                    void each(State state) {
                        switch (state) {
                            case NEW:
                                run();
                                break;
                            case DONE:
                                stop();
                                break;
                        }
                        done();
                    }
                    String fallback(Kind kind) {
                        switch (kind) {
                            case A:
                                return "a";
                            case B: {
                                return "b";
                            }
                        }
                        throw new IllegalArgumentException("Unknown kind " + kind);
                    }
                    void missed(Kind kind) {
                        switch (kind) {
                            case A:
                                run();
                                break;
                            case B:
                                stop();
                                break;
                        }
                        done();
                    }
                }
                """;

        // У каждой константы есть ветка; каждая ветка выходит, а после switch стоит throw. В третьем
        // методе значение C молча пропускается
        assertThat(RuleTests.lines(new SwitchWithoutDefaultRule(), code)).containsExactly(33);
    }

    @Test
    void caseChangeComparedWithTextWithoutLocaleLetters() {
        String code = """
                class Sample {
                    boolean run(String type, String format, String unit, String title) {
                        if ("LONG".equals(type.toUpperCase()) || format.toLowerCase().trim().startsWith("json")) {
                            return true;
                        }
                        switch (unit.toLowerCase()) {
                            case "day": return true;
                            case "month": return false;
                        }
                        return title.toLowerCase().equals("title") || "ADMIN".equals(type.toUpperCase());
                    }
                }
                """;

        // От локали зависят только i и I: в LONG, json, day и month их нет, а в title и ADMIN есть
        assertThat(RuleTests.lines(new CaseWithoutLocaleRule(), code)).containsExactly(10, 10);
    }

    @Test
    void uploadedNameIsCheckedBeforeUse() {
        String code = """
                @RestController
                class FileController {
                    @PostMapping("/slip")
                    void slip(@RequestParam MultipartFile file) throws IOException {
                        File target = new File(root, file.getOriginalFilename());
                        FileUtil.checkSlip(root, target);
                        file.transferTo(target);
                    }
                    @PostMapping("/dots")
                    void dots(@RequestParam String name) throws IOException {
                        if (name.contains("..")) {
                            throw new IllegalArgumentException("Bad name");
                        }
                        Files.createDirectory(base.resolve(name));
                    }
                    @PostMapping("/own")
                    void own(@RequestParam String name) throws IOException {
                        if (KkFileUtils.isIllegalFileName(name)) {
                            return;
                        }
                        Files.createDirectory(base.resolve(name));
                    }
                    @PostMapping("/name")
                    void name(@RequestParam MultipartFile file) throws IOException {
                        String name = FilenameUtils.getName(file.getOriginalFilename());
                        file.transferTo(base.resolve(name));
                    }
                    @PostMapping("/raw")
                    void raw(@RequestParam MultipartFile file) throws IOException {
                        if (file.getOriginalFilename().contains(" ")) {
                            return;
                        }
                        file.transferTo(base.resolve(file.getOriginalFilename()));
                    }
                }
                """;

        // checkSlip, отказ на "..", своя проверка имени и FilenameUtils.getName - это и есть проверка пути;
        // поиск пробела в имени от выхода из каталога не защищает
        assertThat(RuleTests.lines(new PathTraversalRule(), code)).containsExactly(33);
    }

    @Test
    void valueOfKnownKey() {
        String code = """
                class Sample {
                    private final Map<String, Properties> byBank = new HashMap<>();
                    void put(String bank, Properties added) {
                        if (byBank.get(bank) == null) {
                            byBank.put(bank, added);
                        } else {
                            Properties known = byBank.get(bank);
                            known.putAll(added);
                        }
                    }
                    void all(Map<Object, String[]> params, Map<String, String[]> other) {
                        for (Object key : params.keySet()) {
                            String name = (String) key;
                            String[] values = params.get(name);
                            use(values.length);
                            String[] foreign = other.get(name);
                            use(foreign.length);
                        }
                        for (Iterator<Object> iterator = params.keySet().iterator(); iterator.hasNext();) {
                            Object next = iterator.next();
                            String[] values = params.get(next);
                            use(values.length);
                        }
                    }
                }
                """;

        // Значение по ключу уже сверили с null; ключ взят из keySet этой же карты, пусть и под другим
        // именем или через итератор. Ключ чужой карты ничего не обещает
        assertThat(RuleTests.lines(new NullableDereferenceRule(), code)).containsExactly(17);
    }

    @Test
    void booleanWithFallback() {
        String code = """
                class Sample {
                    String mask(String value, Boolean unique, Boolean keep, Map<String, Object> attributes) {
                        if (Objects.isNull(unique)) {
                            unique = UNIQUE;
                        }
                        Boolean noCache = (Boolean) attributes.getOrDefault("noCache", false);
                        Boolean logfile = find(value).map(Kind.LOG::equals).orElse(false);
                        if (unique || noCache || logfile) {
                            return value;
                        }
                        return keep ? value : null;
                    }
                }
                """;

        // Пустое значение заменили запасным, а у getOrDefault и orElse запасное значение задано сразу;
        // параметр keep никто не проверял
        assertThat(RuleTests.lines(new NullUnboxingRule(), code)).containsExactly(11);
    }

    @Test
    void searchResultCheckedWhileAssigned() {
        String code = """
                class Sample {
                    boolean parse(String token, String mail) {
                        int pos;
                        if ((pos = token.indexOf(":")) < 0) {
                            return false;
                        }
                        use(token.substring(0, pos));
                        int at = mail.indexOf("@");
                        use(mail.substring(at));
                        return true;
                    }
                }
                """;

        // Результат поиска сверили с нулем в том же условии, где присвоили; второй поиск никто не проверял
        assertThat(RuleTests.lines(new IndexOutOfBoundsRule(), code)).containsExactly(9);
    }

    @Test
    void broadCatchThatKeepsStackTrace() {
        String code = """
                class Sample {
                    Result upload(File file) {
                        try {
                            return storage.put(file);
                        } catch (Exception e) {
                            log.error("Upload failed", e);
                            return Result.failed();
                        }
                    }
                    Result remove(File file) {
                        try {
                            return storage.remove(file);
                        } catch (Exception e) {
                            log.info("Remove failed: {}", e.getMessage());
                            return Result.failed();
                        }
                    }
                }
                """;

        // Обе находки остаются, но там, где стек ушел в лог, это только подозрение
        assertThat(RuleTests.check(new BroadCatchRule(), code))
                .extracting(violation -> violation.line() + " " + violation.confidenceOrDefault())
                .containsExactly("5 " + Confidence.SUSPICION, "13 " + Confidence.PROBABLE);
    }

    @Test
    void myBatisGeneratorMarksEveryMember() {
        String mapper = """
                public interface TopicMapper {
                    /**
                     * This method was generated by MyBatis Generator.
                     *
                     * @mbg.generated
                     */
                    long countByExample(TopicExample example);
                    /**
                     * @mbg.generated
                     */
                    Topic selectByPrimaryKeyWithLogicalDelete(Integer id, boolean deleted);
                }
                """;
        String own = """
                public class TopicService {
                    // ids are generated by the database
                    void save(Topic topic) {
                    }
                }
                """;

        // Генератор помечает не файл, а каждый метод; слова "generated by" в обычном комментарии - не пометка
        assertThat(GeneratedSources.isGenerated(RuleTests.parse(Path.of("TopicMapper.java"), mapper).unit())).isTrue();
        assertThat(GeneratedSources.isGenerated(RuleTests.parse(Path.of("TopicService.java"), own).unit())).isFalse();
    }

    @Test
    void knownKeyIsRejectedBeforePut() {
        String code = """
                class Sample {
                    private final Map<String, Card> cards = new HashMap<>();
                    Card create(String type) {
                        if (cards.containsKey(type)) {
                            throw new IllegalStateException("Card type " + type + " already exists");
                        }
                        Card card = build(type);
                        cards.put(type, card);
                        return card;
                    }
                    Card find(String type) {
                        if (cards.containsKey(type)) {
                            return cards.get(type);
                        }
                        return null;
                    }
                }
                """;

        // Повтор ключа - отказ, а не замена: это проверка, а не двойной поиск. containsKey + get - двойной поиск
        assertThat(RuleTests.lines(new MapDoubleLookupRule(), code)).containsExactly(12);
    }

    @Test
    void reactiveRepositoryDoesNotBlock() {
        String code = """
                import reactor.core.publisher.Flux;
                import reactor.core.publisher.Mono;
                class PostService {
                    Mono<Post> get(Long id) {
                        return this.posts.findById(id);
                    }
                    Mono<Post> visible(Long id) {
                        return postRepository.find(id).filter(Post::isVisible).switchIfEmpty(Mono.empty());
                    }
                    Mono<List<PostDto>> all() {
                        Flux<Post> found = postRepository.findAll();
                        return found.map(mapper::toDto).collectList();
                    }
                    Mono<Void> reset(String hash) {
                        return users.updatePassword(hash).then(tokenRepository.removeByTokenHash(hash));
                    }
                    Mono<Post> wrapped(Long id) {
                        return Mono.just(postRepository.findById(id));
                    }
                    Mono<PostDto> mapped(Long id) {
                        Post post = postRepository.getById(id);
                        return Mono.just(mapper.toDto(post));
                    }
                }
                """;

        // Вызов, результат которого сам Mono или Flux, ничего не ждет. Значение, которое кладут в Mono.just
        // или в обычную переменную, получено блокирующим чтением
        assertThat(RuleTests.lines(new BlockingCallInReactiveRule(), code)).containsExactly(18, 21);
    }

    @Test
    void offerToQueueWithoutLimit() {
        String code = """
                class Sample {
                    private final PriorityQueue<Integer> minHeap = new PriorityQueue<>();
                    private final Queue<Node> visited = new LinkedList<>();
                    private final BlockingQueue<Event> unlimited = new LinkedBlockingQueue<>();
                    private final BlockingQueue<Event> limited = new LinkedBlockingQueue<>(100);
                    private final ArrayBlockingQueue<Event> fixed = new ArrayBlockingQueue<>(10);
                    void run(Node node, Event event, BlockingQueue<Event> given, Queue<Event> plain) {
                        minHeap.offer(1);
                        visited.offer(node);
                        unlimited.offer(event);
                        plain.offer(event);
                        limited.offer(event);
                        fixed.offer(event);
                        given.offer(event);
                    }
                }
                """;

        // Элемент теряется только у очереди с пределом размера: у остальных offer всегда возвращает true
        assertThat(RuleTests.lines(new IgnoredBooleanResultRule(), code)).containsExactly(12, 13, 14);
    }

    @Test
    void helperThatAlwaysThrowsIsAnExit() {
        String code = """
                class MemberService {
                    void updatePassword(String phone, String password) {
                        List<Member> members = mapper.selectByPhone(phone);
                        if (CollectionUtils.isEmpty(members)) {
                            Asserts.fail("No such member");
                        }
                        Member member = members.get(0);
                        member.setPassword(password);
                    }
                    void notify(String phone, Promise<Void> promise) {
                        List<Member> members = mapper.selectByPhone(phone);
                        if (members.isEmpty()) {
                            promise.fail("No such member");
                        }
                        send(members.get(0));
                    }
                }
                """;

        // Asserts.fail бросает исключение, и до get(0) пустой список не дойдет; promise.fail ничего не бросает
        assertThat(RuleTests.lines(new ListGetFirstWithoutCheckRule(), code)).containsExactly(15);
    }

    @Test
    void staticInstanceIsNotSetYetInsideItsOwnConstructor() {
        String code = """
                final class IvoryTower {
                    private static final IvoryTower INSTANCE = new IvoryTower();
                    private static final Object LOCK = new Object();
                    private IvoryTower() {
                        if (INSTANCE != null) {
                            throw new IllegalStateException("Already initialized");
                        }
                        if (LOCK != null) {
                            ready();
                        }
                    }
                }
                """;

        // Конструктор и создает INSTANCE: в нем поле еще пустое, и проверка не лишняя. LOCK к этому
        // времени тоже может быть не задан, но правило судит только о поле с экземпляром своего класса
        assertThat(RuleTests.lines(new ConstantConditionRule(), code)).containsExactly(8);
    }

    @Test
    void equalsOnValueThatWasAlreadyUsed() {
        String code = """
                class Sample {
                    Locale parse(String str, Order order, Map<String, Link> links) {
                        String[] segments = str.split("_", 3);
                        if (str.equals("th_TH")) {
                            return THAI;
                        }
                        links.entrySet().removeIf(e -> e.getKey().equals("self"));
                        if (segments.length > 1) {
                            order.getStatus().trim();
                        }
                        return order.getStatus().equals("NEW") ? ROOT : null;
                    }
                }
                """;

        // К str уже обращались строкой выше, а ключ записи карты пустым не бывает; обращение к статусу
        // стоит в ветке, которая могла и не выполниться
        assertThat(RuleTests.lines(new EqualsOnPossibleNullRule(), code)).containsExactly(11);
    }

    @Test
    void checkWrittenAsAssertionCall() {
        String code = """
                class Sample {
                    void put(Map<String, Object> current, String segment, Object other, List<Member> members) {
                        Object child = current.get(segment);
                        Assert.state(child instanceof Map, () -> "Conflict at " + segment);
                        Map<String, Object> nested = (Map<String, Object>) child;
                        log.info("Other is {}", other instanceof Map);
                        Map<String, Object> second = (Map<String, Object>) other;
                        Preconditions.checkArgument(!members.isEmpty(), "No members");
                        use(members.get(0));
                    }
                }
                """;

        // Assert.state и Preconditions.checkArgument бросают исключение, когда условие ложно; запись того же
        // условия в лог ничего не проверяет
        assertThat(RuleTests.lines(new CastWithoutInstanceofRule(), code)).containsExactly(7);
        assertThat(RuleTests.lines(new ListGetFirstWithoutCheckRule(), code)).isEmpty();
    }

    @Test
    void mapKeyedByOwnIdentifier() {
        String code = """
                class Sample {
                    void run(List<SysMenu> menus, List<Header> headers) {
                        Map<Long, SysMenu> byId = menus.stream().collect(Collectors.toMap(SysMenu::getMenuId, Function.identity()));
                        Map<Long, SysMenu> byParent = menus.stream().collect(Collectors.toMap(SysMenu::getParentId, Function.identity()));
                        Map<String, String> byName = headers.stream().collect(Collectors.toMap(Header::getName, Header::getValue));
                    }
                }
                """;

        // getMenuId у SysMenu - его собственный идентификатор: повтор возможен, только если объект попал в
        // поток дважды. У нескольких пунктов меню один родитель, а заголовки с одним именем - обычное дело
        assertThat(RuleTests.check(new ToMapWithoutMergeRule(), code))
                .extracting(violation -> violation.line() + " " + violation.confidenceOrDefault())
                .containsExactly("3 " + Confidence.SUSPICION, "4 " + Confidence.PROBABLE, "5 " + Confidence.PROBABLE);
    }

    // Два сервиса в одном хранилище: у каждого свой pom.xml, свой запускаемый класс, свои адреса и свои таблицы
    @Test
    void separateApplicationsOfOneRepository() throws IOException {
        String controller = """
                package %s;
                @RestController
                public class StatusController {
                    @GetMapping({"", "/"})
                    public String status() { return "ok"; }
                    @GetMapping("/")
                    public String again() { return "ok"; }
                }
                """;
        String user = """
                package %s;
                @Entity
                @Table(name = "users")
                public class User {
                    @Id
                    private Long id;
                    private String email;
                }
                """;
        write("shop/pom.xml", "<project/>");
        write("shop/src/main/java/shop/Application.java", APPLICATION.formatted("shop"));
        write("shop/src/main/java/shop/StatusController.java", controller.formatted("shop"));
        write("shop/src/main/java/shop/User.java", user.formatted("shop").replace("private String email;",
                "@Column(name = \"`Email`\")\n    private String email;"));
        write("shop/src/main/resources/db/migration/V1__users.sql",
                "create table users (id bigint primary key, email varchar(100));\n");
        write("billing/pom.xml", "<project/>");
        write("billing/src/main/java/billing/Application.java", APPLICATION.formatted("billing"));
        write("billing/src/main/java/billing/StatusController.java", controller.formatted("billing"));
        write("billing/src/main/java/billing/User.java", user.formatted("billing"));
        write("billing/src/main/resources/db/migration/V1__users.sql",
                "create table users (id bigint primary key, login varchar(100));\n");
        List<SourceFile> sources = new FileSystemSourceLoader().load(dir, path -> true, List.of()).sources();
        ScanContext context = new ScanContext(
                dir,
                sources,
                new FileSystemConfigLoader().load(dir, file -> true),
                new FileSystemTextFileLoader().load(dir, file -> true));

        // Адрес GET / у двух сервисов - не конфликт, и один метод с адресами "" и "/" не спорит сам с собой;
        // два метода одного контроллера с одним адресом - конфликт в каждом сервисе
        assertThat(places(new DuplicateEndpointRule().checkProject(sources)))
                .containsExactly("billing/StatusController.java:6", "shop/StatusController.java:6");
        // Колонки email нет только в таблице сервиса billing: таблица users сервиса shop к нему не относится.
        // В сервисе shop имя колонки записано в кавычках Hibernate: `Email` - это та же колонка email
        assertThat(places(new EntityFieldWithoutColumnRule().checkContext(context)))
                .containsExactly("billing/User.java:7");
    }

    private void write(String path, String content) throws IOException {
        Path file = dir.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    // Находки в виде "каталог/файл:строка", по алфавиту
    private List<String> places(List<Violation> violations) {
        return violations.stream()
                .map(violation -> violation.file().getParent().getFileName() + "/" + violation.file().getFileName()
                        + ":" + violation.line())
                .sorted()
                .toList();
    }
}
