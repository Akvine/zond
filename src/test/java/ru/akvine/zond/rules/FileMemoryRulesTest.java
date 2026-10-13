package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.performance.FileAssembledInMemoryRule;
import ru.akvine.zond.rules.performance.FileBytesInResponseRule;
import ru.akvine.zond.rules.performance.FileContentInEntityRule;
import ru.akvine.zond.rules.performance.FileInMessageBodyRule;
import ru.akvine.zond.rules.performance.RemoteFileIntoMemoryRule;
import ru.akvine.zond.rules.performance.TextFileAssembledInStringRule;
import ru.akvine.zond.rules.performance.WholeFileReadIntoMemoryRule;
import ru.akvine.zond.rules.resources.DeleteOnExitRule;
import ru.akvine.zond.rules.resources.FilesHeldInBeanRule;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила jr:401 - jr:409: файл, который собирают, читают, отдают или хранят в памяти целиком
 */
class FileMemoryRulesTest {

    @Test
    void fileWrittenIntoByteArrayStreamOverDataIsReported() {
        String code = """
                class CsvGenerator {
                    byte[] generate(Table table) throws IOException {
                        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
                             CSVPrinter printer = new CSVPrinter(new PrintWriter(out), format)) {
                            for (Row row : table.rows()) {
                                printer.printRecord(row.values());
                            }
                            return out.toByteArray();
                        }
                    }
                    byte[] toBytes(Workbook workbook) throws IOException {
                        ByteArrayOutputStream stream = new ByteArrayOutputStream();
                        workbook.write(stream);
                        return stream.toByteArray();
                    }
                    byte[] export(List<Row> rows) {
                        ByteArrayOutputStream target = new ByteArrayOutputStream();
                        csvExporter.write(rows, target);
                        return target.toByteArray();
                    }
                    byte[] archive(List<Entry> entries) throws IOException {
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                        ZipOutputStream zip = new ZipOutputStream(bytes);
                        for (Entry entry : entries) {
                            zip.putNextEntry(new ZipEntry(entry.name()));
                            zip.write(entry.content());
                        }
                        return bytes.toByteArray();
                    }
                }
                """;

        List<Violation> violations = RuleTests.check(new FileAssembledInMemoryRule(), code);

        assertThat(violations).extracting(Violation::line).containsExactly(3, 12, 17, 22);
        // Что пишет вызванный метод, по коду не видно - о нем правило говорит осторожнее
        assertThat(violations).extracting(Violation::confidence).containsExactly(
                Confidence.PROBABLE, Confidence.PROBABLE, Confidence.SUSPICION, Confidence.PROBABLE);
        assertThat(violations.get(0).message())
                .contains("'out'").contains("printer.printRecord(row.values())").contains("out.toByteArray()");
        assertThat(violations.get(1).message()).contains("workbook.write(stream)");
    }

    @Test
    void smallBuffersAndRealOutputStreamsAreNotReported() {
        String code = """
                class Codec {
                    byte[] mask(byte[] data) {
                        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
                        for (byte value : data) {
                            encoded.write(value ^ 0x5A);
                        }
                        return encoded.toByteArray();
                    }
                    byte[] frame(int id, byte[] body) {
                        ByteArrayOutputStream frame = new ByteArrayOutputStream();
                        frame.write(id);
                        frame.writeBytes(body);
                        return frame.toByteArray();
                    }
                    byte[] read(InputStream in) throws IOException {
                        ByteArrayOutputStream copy = new ByteArrayOutputStream();
                        byte[] chunk = new byte[8192];
                        int read;
                        while ((read = in.read(chunk)) != -1) {
                            copy.write(chunk, 0, read);
                        }
                        return copy.toByteArray();
                    }
                    void stream(Table table, OutputStream out) {
                        PrintWriter writer = new PrintWriter(out);
                        for (Row row : table.rows()) {
                            writer.println(row);
                        }
                    }
                    void perRow(List<Row> rows) {
                        for (Row row : rows) {
                            ByteArrayOutputStream cell = new ByteArrayOutputStream();
                            cell.writeBytes(row.bytes());
                            send(cell.toByteArray());
                        }
                    }
                }
                """;

        // Побайтовое кодирование, буфер на одно значение, копирование потока (о нем говорит jr:405),
        // запись в настоящий выходной поток и буфер, который создается заново для каждой строки
        assertThat(RuleTests.check(new FileAssembledInMemoryRule(), code)).isEmpty();
    }

    @Test
    void textFileBuiltInStringAndConvertedToBytesIsReported() {
        String code = """
                class SqlGenerator {
                    byte[] generate(Table table) {
                        StringBuilder sql = new StringBuilder();
                        for (Row row : table.rows()) {
                            sql.append("INSERT INTO t VALUES (").append(row).append(");\\n");
                        }
                        return sql.toString().getBytes(StandardCharsets.UTF_8);
                    }
                    byte[] viaWriter(List<Row> rows) {
                        StringWriter text = new StringWriter();
                        PrintWriter writer = new PrintWriter(text);
                        rows.forEach(row -> writer.println(row));
                        String content = text.toString();
                        return content.getBytes();
                    }
                    byte[] joined(List<String> lines) {
                        return String.join("\\n", lines).getBytes();
                    }
                    byte[] collected(List<Row> rows) {
                        return rows.stream().map(Row::toString).collect(Collectors.joining(System.lineSeparator())).getBytes();
                    }
                    byte[] key(List<String> parts) {
                        return String.join(",", parts).getBytes();
                    }
                    String names(Table table) {
                        StringBuilder names = new StringBuilder();
                        for (Column column : table.columns()) {
                            names.append(column.name()).append(", ");
                        }
                        return names.toString();
                    }
                    byte[] hex(byte[] data) {
                        StringBuilder hex = new StringBuilder();
                        for (byte value : data) {
                            hex.append(String.format("%02x", value));
                        }
                        return hex.toString().getBytes();
                    }
                    byte[] greeting(String name) {
                        StringBuilder greeting = new StringBuilder();
                        greeting.append("Hello, ").append(name);
                        return greeting.toString().getBytes();
                    }
                }
                """;

        List<Violation> violations = RuleTests.check(new TextFileAssembledInStringRule(), code);

        // Склейка через запятую, строка без превращения в байты, шестнадцатеричная запись значения
        // и короткая строка без цикла файлом не считаются
        assertThat(violations).extracting(Violation::line).containsExactly(7, 14, 17, 20);
        assertThat(violations).extracting(Violation::confidence).containsExactly(
                Confidence.PROBABLE, Confidence.PROBABLE, Confidence.SUSPICION, Confidence.SUSPICION);
        assertThat(violations.get(0).message()).contains("'sql'").contains("накопитель, строка и ее байты");
    }

    @Test
    void fileSentInResponseAsByteArrayIsReported() {
        String controller = """
                @RestController
                class ReportController {
                    @GetMapping("/reports/{id}")
                    ResponseEntity<?> download(@PathVariable long id) {
                        byte[] file = service.generate(id);
                        return ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=report.csv")
                                .body(file);
                    }
                    @GetMapping(value = "/files/{id}", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
                    byte[] raw(@PathVariable long id) {
                        return storage.load(id);
                    }
                    @GetMapping("/export")
                    ResponseEntity<Resource> export() {
                        byte[] bytes = service.export();
                        return ResponseEntity.ok().header("Content-Disposition", "attachment").body(new ByteArrayResource(bytes));
                    }
                    @GetMapping("/legacy")
                    void legacy(HttpServletResponse response) throws IOException {
                        byte[] zip = service.zip();
                        response.setHeader("Content-Disposition", "attachment; filename=a.zip");
                        response.getOutputStream().write(zip);
                    }
                    @GetMapping("/qr")
                    ResponseEntity<byte[]> qr() {
                        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(codes.png());
                    }
                    @GetMapping("/stream")
                    ResponseEntity<StreamingResponseBody> stream() {
                        StreamingResponseBody body = out -> service.write(out);
                        return ResponseEntity.ok().header("Content-Disposition", "attachment").body(body);
                    }
                    @PostMapping("/forward")
                    ResponseEntity<Void> forward(@RequestBody byte[] document) {
                        restClient.post().uri("/files").body(document).retrieve();
                        return ResponseEntity.ok().build();
                    }
                }
                """;
        String converter = """
                class ResponseConverter {
                    ResponseEntity<?> toResponse(byte[] report) {
                        return ResponseEntity.ok().body(report);
                    }
                }
                """;

        List<Violation> violations = RuleTests.checkProject(new FileBytesInResponseRule(),
                Map.of("ReportController.java", controller, "ResponseConverter.java", converter));

        // Картинка с кодом, потоковая отдача и тело исходящего запроса файлом в ответе не являются
        List<Violation> inController = violations.stream()
                .filter(violation -> violation.file().toString().startsWith("ReportController"))
                .toList();
        assertThat(inController).extracting(Violation::line).containsExactly(8, 11, 17, 23);
        assertThat(inController.get(0).message()).contains("body(file)").contains("StreamingResponseBody");
        // Заголовки файла заданы не здесь - о том, что это файл, говорит только название
        assertThat(violations).filteredOn(violation -> violation.file().toString().startsWith("ResponseConverter"))
                .extracting(Violation::confidence).containsExactly(Confidence.SUSPICION);
    }

    @Test
    void wholeFileInMessageBodyIsReported() {
        String code = """
                class ReplySender {
                    Message fileReply(byte[] file, String name) {
                        return MessageBuilder.withBody(file).setHeader("fileName", name).build();
                    }
                    Message errorReply(ErrorResponse error) throws Exception {
                        byte[] body = objectMapper.writeValueAsBytes(error);
                        return MessageBuilder.withBody(body).build();
                    }
                    void forward(MultipartFile upload) throws IOException {
                        rabbitTemplate.convertAndSend("files", upload.getBytes());
                    }
                    void event(String json) {
                        kafkaTemplate.send("orders", json.getBytes(StandardCharsets.UTF_8));
                    }
                    void report(ByteArrayOutputStream buffer) {
                        byte[] report = buffer.toByteArray();
                        kafkaTemplate.send(new ProducerRecord<>("reports", report));
                    }
                    void proto(OrderCreated created) {
                        byte[] document = created.toByteArray();
                        kafkaTemplate.send("orders", document);
                    }
                }
                """;

        List<Violation> violations = RuleTests.check(new FileInMessageBodyRule(), code);

        // Объект, превращенный в JSON или в байты protobuf, - обычное сообщение, а не файл
        assertThat(violations).extracting(Violation::line).containsExactly(3, 10, 17);
        assertThat(violations.get(0).message()).contains("'file'").contains("Claim Check");
    }

    @Test
    void readingWholeFileIntoMemoryIsReported() {
        String code = """
                class ImportService {
                    void importFile(Path uploaded) throws IOException {
                        byte[] content = Files.readAllBytes(uploaded);
                        List<String> lines = Files.readAllLines(uploaded);
                    }
                    String template() throws IOException {
                        return Files.readString(Path.of("templates/mail.html"));
                    }
                    byte[] publicKey() throws IOException {
                        return getClass().getResourceAsStream("/keys/public.pem").readAllBytes();
                    }
                    byte[] fromStorage(InputStream objectContent) throws IOException {
                        return IOUtils.toByteArray(objectContent);
                    }
                    byte[] copy(InputStream in) throws IOException {
                        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                        in.transferTo(buffer);
                        return buffer.toByteArray();
                    }
                    byte[] manual(InputStream in) throws IOException {
                        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                        byte[] chunk = new byte[8192];
                        int read;
                        while ((read = in.read(chunk)) != -1) {
                            buffer.write(chunk, 0, read);
                        }
                        return buffer.toByteArray();
                    }
                    void stream(InputStream in, OutputStream out) throws IOException {
                        in.transferTo(out);
                    }
                    byte[] upload(MultipartFile file) throws IOException {
                        return file.getInputStream().readAllBytes();
                    }
                    String note() throws IOException {
                        return Files.readString(notePath);
                    }
                    byte[] stored(Resource reportResource) throws IOException {
                        return reportResource.getContentAsByteArray();
                    }
                    byte[] cached(ContentCachingResponseWrapper wrapper) {
                        return wrapper.getContentAsByteArray();
                    }
                    String error(Response response) throws IOException {
                        return IOUtils.toString(response.body().asInputStream(), StandardCharsets.UTF_8);
                    }
                }
                @Configuration
                class Dictionary {
                    @PostConstruct
                    void load() throws IOException {
                        words = Files.readAllLines(Path.of("/data/words.txt"));
                    }
                }
                @RestController
                class ImportController {
                }
                """;

        List<Violation> violations = RuleTests.check(new WholeFileReadIntoMemoryRule(), code);

        // Шаблон и ключ - файлы известного размера, словарь читается один раз при запуске, передача
        // потока в поток памяти не занимает, о загруженном файле сообщает jr:242, буфер обертки ответа
        // уже лежит в памяти, а текст ответа другого сервиса читают целиком, чтобы разобрать
        assertThat(violations).extracting(Violation::line).containsExactly(3, 4, 13, 17, 25, 36, 39);
        // Файл без единого признака размера и происхождения - только подозрение
        assertThat(violations).extracting(Violation::confidence).containsExactly(
                Confidence.PROBABLE, Confidence.PROBABLE, Confidence.PROBABLE, Confidence.PROBABLE,
                Confidence.PROBABLE, Confidence.SUSPICION, Confidence.PROBABLE);
        assertThat(violations.get(3).message()).contains("копирует поток в буфер");
        assertThat(violations.get(4).message()).startsWith("Цикл копирует поток");
    }

    @Test
    void wholeFileReadInCommandLineToolIsOnlySuspicion() {
        String code = """
                class ReportReader {
                    List<String> read(Path reportFile) throws IOException {
                        return Files.readAllLines(reportFile);
                    }
                }
                """;

        // В проекте нет ни контроллеров, ни слушателей: файлы читаются по одному, а не десятками сразу
        assertThat(RuleTests.check(new WholeFileReadIntoMemoryRule(), code))
                .extracting(Violation::confidence).containsExactly(Confidence.SUSPICION);
    }

    @Test
    void remoteResponseReadIntoByteArrayIsReported() {
        String code = """
                class FileClient {
                    byte[] download(String url) {
                        return restTemplate.getForObject(url, byte[].class);
                    }
                    byte[] modern(String id) {
                        return restClient.get()
                                .uri("/files/{id}", id)
                                .retrieve()
                                .body(byte[].class);
                    }
                    Mono<byte[]> reactive() {
                        return webClient.get().retrieve().bodyToMono(byte[].class);
                    }
                    byte[] jdk(HttpRequest request) throws Exception {
                        return httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray()).body();
                    }
                    Order order(String url) {
                        return restTemplate.getForObject(url, Order.class);
                    }
                    byte[] qrCode(String url) {
                        return restTemplate.getForObject(url, byte[].class);
                    }
                    void streamed(String url, OutputStream out) {
                        restTemplate.execute(url, HttpMethod.GET, null, response -> response.getBody().transferTo(out));
                    }
                }
                @FeignClient(name = "storage")
                interface StorageClient {
                    @GetMapping("/files/{id}")
                    byte[] download(@PathVariable String id);
                    @GetMapping("/files/{id}/meta")
                    FileMeta meta(@PathVariable String id);
                }
                """;

        List<Violation> violations = RuleTests.check(new RemoteFileIntoMemoryRule(), code);

        // QR-код мал: читать его целиком нормально
        assertThat(violations).extracting(Violation::line).containsExactly(3, 9, 12, 15, 30);
        assertThat(violations.get(4).message()).contains("'download'").contains("byte[]");
    }

    @Test
    void fileContentStoredInEntityFieldIsReported() {
        String code = """
                @Entity
                class Document {
                    @Id
                    Long id;
                    @Lob
                    byte[] content;
                    byte[] attachment;
                    byte[] passwordHash;
                    @Lob
                    String description;
                    @Lob
                    String xmlDocument;
                    @Lob
                    @Basic(fetch = FetchType.LAZY)
                    byte[] scan;
                    @Column(length = 512)
                    byte[] data;
                    @Lob
                    Blob stream;
                }
                class Upload {
                    @Lob
                    byte[] content;
                }
                """;

        List<Violation> violations = RuleTests.check(new FileContentInEntityRule(), code);

        // Хеш, длинный текст, лениво загружаемое поле, короткая колонка и Blob (он читается потоком)
        // файлом в памяти не считаются; класс без @Entity правило не трогает
        assertThat(violations).extracting(Violation::line).containsExactly(6, 7, 12);
        assertThat(violations).extracting(Violation::confidence)
                .containsExactly(Confidence.PROBABLE, Confidence.SUSPICION, Confidence.SUSPICION);
        assertThat(violations.get(0).message()).contains("'content'").contains("'Document'");
    }

    @Test
    void filesKeptInBeanFieldOrCacheAreReported() {
        String code = """
                @Service
                class ReportStore {
                    private final Map<String, byte[]> reports = new ConcurrentHashMap<>();
                    private final List<Order> orders = new ArrayList<>();
                    private static final Map<String, byte[]> SIGNATURES = Map.of("png", new byte[] {1});
                    private final Map<Zone, byte[]> colors = new EnumMap<>(Zone.class);
                    ReportStore() {
                        colors.put(Zone.RED, new byte[] {1, 2, 3});
                    }
                    public void save(String id, byte[] report) {
                        reports.put(id, report);
                    }
                    @Cacheable("reports")
                    public byte[] load(String id) {
                        return reports.get(id);
                    }
                    @Cacheable("meta")
                    public ReportMeta meta(String id) {
                        return null;
                    }
                }
                class Plain {
                    private final Map<String, byte[]> parts = new HashMap<>();
                    void add(String name, byte[] part) {
                        parts.put(name, part);
                    }
                }
                class Holder {
                    static final Map<UUID, ByteArrayOutputStream> BUFFERS = new HashMap<>();
                    static void keep(UUID id, ByteArrayOutputStream buffer) {
                        BUFFERS.put(id, buffer);
                    }
                }
                """;

        List<Violation> violations = RuleTests.check(new FilesHeldInBeanRule(), code);

        // Не считаются: набор постоянных сигнатур, карта, заполненная один раз в конструкторе бина,
        // и поле обычного объекта (оно живет, пока жив сам объект)
        assertThat(violations).extracting(Violation::line).containsExactly(3, 14, 29);
        assertThat(violations.get(0).message()).contains("'reports'").contains("соседний экземпляр");
        assertThat(violations.get(1).message()).contains("'load'").contains("кеш");
    }

    @Test
    void deleteOnExitInServiceCodeIsReported() {
        String code = """
                class TempFiles {
                    File create() throws IOException {
                        File file = File.createTempFile("report", ".csv");
                        file.deleteOnExit();
                        return file;
                    }
                    public static void main(String[] args) throws IOException {
                        File file = File.createTempFile("cli", ".tmp");
                        file.deleteOnExit();
                    }
                }
                @Component
                class LockFile {
                    @PostConstruct
                    void init() {
                        File lock = new File("app.lock");
                        lock.deleteOnExit();
                    }
                }
                """;

        // Утилита командной строки и один файл на весь срок работы приложения утечкой не являются
        assertThat(RuleTests.lines(new DeleteOnExitRule(), code)).containsExactly(4);
    }
}
