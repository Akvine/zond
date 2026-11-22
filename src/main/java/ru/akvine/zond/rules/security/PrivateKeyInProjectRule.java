package ru.akvine.zond.rules.security;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.TestPaths;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractContextRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.files.GitIgnore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Закрытый ключ в папке проекта, не закрытый {@code .gitignore}: хранилище ключей ({@code .jks}, {@code .p12}) с
 * парой ключей либо файл {@code .pem} / {@code .key}. Ключ, однажды попавший в хранилище, остается в его истории
 * у каждого, кто его клонировал, а файл из {@code src/main/resources} еще и уезжает внутри каждой сборки. Пароль
 * хранилища обычно лежит тут же, в настройках.
 */
@Component
public class PrivateKeyInProjectRule extends AbstractContextRule {
    // Каталоги сборки и служебные: их содержимое в репозиторий не попадает
    private static final Set<String> SKIPPED_DIRECTORIES = Set.of(
            "build", "target", "out", "node_modules", ".git", ".gradle", ".idea", ".mvn", ".settings");
    private static final int MAX_DEPTH = 9;
    private static final int MAX_FILES = 50_000;
    // Хранилища и ключи занимают килобайты: большой файл с таким расширением - что-то другое
    private static final long MAX_BYTES = 4L * 1024 * 1024;

    private static final Pattern KEY_STORE_NAME = Pattern.compile(".*\\.(jks|jceks|keystore|ks|p12|pfx)");
    private static final Pattern KEY_FILE_NAME = Pattern.compile(".*\\.(pem|key|ppk)|id_(rsa|dsa|ecdsa|ed25519)");
    private static final int JKS_MAGIC = 0xFEEDFEED;
    private static final int JCEKS_MAGIC = 0xCECECECE;
    private static final int PRIVATE_KEY_ENTRY = 1;
    private static final int TRUSTED_CERTIFICATE_ENTRY = 2;
    private static final int SECRET_KEY_ENTRY = 3;
    private static final int MAX_ENTRIES = 10_000;
    // PKCS #12: мешок с закрытым ключом узнается по идентификатору keyBag или pkcs8ShroudedKeyBag, он не шифруется
    private static final byte[] PKCS12_KEY_BAG =
            {0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x0C, 0x0A, 0x01};
    private static final Set<Byte> KEY_BAG_KINDS = Set.of((byte) 0x01, (byte) 0x02);
    private static final int ASN1_SEQUENCE = 0x30;
    private static final Pattern PEM_PRIVATE_KEY =
            Pattern.compile("-----BEGIN ([A-Z0-9]+ )?PRIVATE KEY-----|PuTTY-User-Key-File-\\d");
    // Ключи тестов и примеров настоящими не бывают
    private static final Pattern SAMPLE_NAME =
            Pattern.compile(".*(test|demo|example|sample|dummy|fake|localhost|self-?signed).*");
    // Каталог или само хранилище с примерами: spring-security-samples, examples/tls, docs/demo
    private static final Pattern SAMPLE_PLACE =
            Pattern.compile("(^|.*[^a-z])(tests?|demos?|examples?|samples?|tutorials?|dummy|fake)([^a-z].*|$)");
    private static final String MAIN_RESOURCES = "src/main/resources/";
    private static final byte[] NOTHING = new byte[0];

    @Override
    public String code() {
        return RuleCodes.PRIVATE_KEY_IN_PROJECT_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует папку проекта и ищет закрытые ключи, не закрытые .gitignore: хранилища ключей с парой"
                + " ключей (.jks, .p12) и файлы .pem / .key";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        Path root = context.root().toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        GitIgnore ignore = GitIgnore.of(root);
        List<Violation> violations = new ArrayList<>();
        for (Path file : findFiles(root)) {
            String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
            boolean keyStore = KEY_STORE_NAME.matcher(name).matches();
            if (!keyStore && !KEY_FILE_NAME.matcher(name).matches() || ignore.isIgnored(file)) {
                continue;
            }
            byte[] content = read(file);
            Optional<String> found = keyStore ? keyInStore(content) : keyInText(content);
            if (found.isEmpty()) {
                continue;
            }
            String relative = root.relativize(file).toString().replace('\\', '/');
            String packaged = relative.contains(MAIN_RESOURCES)
                    ? " Файл лежит в ресурсах приложения, поэтому попадает еще и в каждую сборку."
                    : "";
            // Без .git и .gitignore неизвестно, хранится ли папка в репозитории вообще; ключ теста или примера
            // настоящим обычно не бывает
            boolean weakEvidence = !ignore.isRepository() || TestPaths.isTest(file)
                    || SAMPLE_NAME.matcher(name).matches() || isInSamples(root, file);
            violations.add(violation(file, found.get() + " '" + relative + "' лежит в папке проекта и не закрыт"
                    + " .gitignore: закрытый ключ попадет в хранилище и останется в его истории у каждого, кто его"
                    + " клонировал." + packaged + " Храните ключи вне кода - в хранилище секретов либо в секрете"
                    + " среды развертывания, - подключайте файлом при запуске, а ключ, уже попавший в git, считайте"
                    + " раскрытым и перевыпустите")
                    .withConfidence(weakEvidence ? Confidence.SUSPICION : Confidence.PROBABLE));
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.SECURITY;
    }

    // Хранилище только с сертификатами доверенных сторон (truststore) секретов не содержит
    private Optional<String> keyInStore(byte[] content) {
        if (content.length < Integer.BYTES) {
            return Optional.empty();
        }
        // Числа в JKS записаны старшим байтом вперед - так же их читает и ByteBuffer
        ByteBuffer input = ByteBuffer.wrap(content);
        int magic = input.getInt();
        if (magic == JKS_MAGIC || magic == JCEKS_MAGIC) {
            return hasKeyEntry(input) ? Optional.of("Хранилище ключей с закрытым ключом") : Optional.empty();
        }
        // Файл .p12 / .pfx; keytool с Java 9 пишет PKCS #12 и в файл с расширением .jks
        return (content[0] & 0xFF) == ASN1_SEQUENCE && hasPkcs12KeyBag(content)
                ? Optional.of("Хранилище ключей PKCS #12 с закрытым ключом")
                : Optional.empty();
    }

    // Запись JKS: вид, псевдоним, дата; у пары ключей - зашифрованный ключ и цепочка сертификатов, у доверенного
    // сертификата - его тип и содержимое. Оборванный файл - не хранилище
    private boolean hasKeyEntry(ByteBuffer input) {
        if (input.remaining() < 2 * Integer.BYTES) {
            return false;
        }
        input.getInt();
        int entries = input.getInt();
        for (int index = 0; index < entries && index < MAX_ENTRIES && input.remaining() >= Integer.BYTES; index++) {
            int kind = input.getInt();
            if (kind == PRIVATE_KEY_ENTRY || kind == SECRET_KEY_ENTRY) {
                return true;
            }
            boolean skipped = kind == TRUSTED_CERTIFICATE_ENTRY
                    && skipText(input) && skip(input, Long.BYTES) && skipText(input)
                    && input.remaining() >= Integer.BYTES && skip(input, input.getInt());
            if (!skipped) {
                return false;
            }
        }
        return false;
    }

    // Строка в записи JKS: два байта длины и сама строка
    private boolean skipText(ByteBuffer input) {
        return input.remaining() >= Short.BYTES && skip(input, Short.toUnsignedInt(input.getShort()));
    }

    private boolean skip(ByteBuffer input, int bytes) {
        if (bytes < 0 || input.remaining() < bytes) {
            return false;
        }
        input.position(input.position() + bytes);
        return true;
    }

    private boolean hasPkcs12KeyBag(byte[] content) {
        for (int start = 0; start + PKCS12_KEY_BAG.length < content.length; start++) {
            boolean matches = true;
            for (int offset = 0; offset < PKCS12_KEY_BAG.length && matches; offset++) {
                matches = content[start + offset] == PKCS12_KEY_BAG[offset];
            }
            if (matches && KEY_BAG_KINDS.contains(content[start + PKCS12_KEY_BAG.length])) {
                return true;
            }
        }
        return false;
    }

    private Optional<String> keyInText(byte[] content) {
        String text = new String(content, StandardCharsets.ISO_8859_1);
        return PEM_PRIVATE_KEY.matcher(text).find() ? Optional.of("Файл с закрытым ключом") : Optional.empty();
    }

    // Файл, который не прочитался или слишком велик для ключа, - пустое содержимое: ключа в нем не найдется
    private byte[] read(Path file) {
        try {
            return Files.size(file) > MAX_BYTES ? NOTHING : Files.readAllBytes(file);
        } catch (IOException exception) {
            return NOTHING;
        }
    }

    private List<Path> findFiles(Path root) {
        List<Path> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root, MAX_DEPTH)) {
            files.limit(MAX_FILES)
                    .filter(file -> !isInSkippedDirectory(root.relativize(file)))
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.naturalOrder())
                    .forEach(found::add);
        } catch (IOException | UncheckedIOException exception) {
            // Папка не прочиталась целиком - сообщаем о том, что успели найти
        }
        return found;
    }

    // Примером бывает и вся проверяемая папка: по ее имени это тоже видно
    private boolean isInSamples(Path root, Path file) {
        List<Path> directories = new ArrayList<>();
        if (root.getFileName() != null) {
            directories.add(root.getFileName());
        }
        Path parent = root.relativize(file).getParent();
        if (parent != null) {
            parent.forEach(directories::add);
        }
        return directories.stream()
                .map(directory -> directory.toString().toLowerCase(Locale.ROOT))
                .anyMatch(directory -> SAMPLE_PLACE.matcher(directory).matches());
    }

    private boolean isInSkippedDirectory(Path relative) {
        for (Path part : relative) {
            if (SKIPPED_DIRECTORIES.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }
}
