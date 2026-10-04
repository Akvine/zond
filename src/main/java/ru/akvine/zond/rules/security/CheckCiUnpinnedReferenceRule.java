package ru.akvine.zond.rules.security;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.TextFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.parsers.YamlNode;
import ru.akvine.zond.rules.AbstractContextRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.files.CiFiles;
import ru.akvine.zond.rules.files.Images;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class CheckCiUnpinnedReferenceRule extends AbstractContextRule {
    private static final String USES = "uses";
    private static final String IMAGE = "image";
    private static final String NAME = "name";
    private static final char REFERENCE = '@';

    // Действие из этого же репозитория и образ: у них версии в таком виде нет
    private static final String LOCAL_ACTION = "./";
    private static final String DOCKER_ACTION = "docker://";
    private static final Set<String> BRANCHES = Set.of("main", "master", "develop", "dev", "trunk", "latest", "head");

    @Override
    public String code() {
        return RuleCodes.CHECK_CI_UNPINNED_REFERENCE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует файлы CI и ищет действия GitHub, привязанные к ветке, и образы без тега или с тегом latest";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        List<Violation> violations = new ArrayList<>();
        for (TextFile file : context.textFiles()) {
            for (YamlNode document : CiFiles.documents(file)) {
                document.visit((key, node) -> {
                    if (USES.equals(key) && node.isScalar()) {
                        checkAction(file, node, violations);
                    } else if (IMAGE.equals(key)) {
                        checkImage(file, node, violations);
                    }
                });
            }
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

    private void checkAction(TextFile file, YamlNode node, List<Violation> violations) {
        String action = node.text().trim();
        if (action.startsWith(LOCAL_ACTION) || action.startsWith(DOCKER_ACTION) || action.contains("$")) {
            return;
        }
        int reference = action.lastIndexOf(REFERENCE);
        String version = reference < 0 ? "" : action.substring(reference + 1);
        if (version.isEmpty() || BRANCHES.contains(version.toLowerCase(Locale.ROOT))) {
            violations.add(violation(file.path(), node.line(),
                    "Действие '" + action + "' " + (version.isEmpty() ? "без версии" : "привязано к ветке")
                            + ": его код может измениться в любой момент и выполнится в вашей сборке с доступом"
                            + " к секретам; укажите тег версии, а лучше хеш коммита"));
        }
    }

    // image: maven:3.9 либо image: {name: maven:3.9, entrypoint: [...]}
    private void checkImage(TextFile file, YamlNode node, List<Violation> violations) {
        YamlNode image = node.isScalar() ? node : node.get(NAME);
        if (Images.isUnpinned(image.text())) {
            violations.add(violation(file.path(), image.line(),
                    "Образ '" + image.text() + "' без точной версии: сборка в разные дни идет в разном окружении"
                            + " и может сломаться без единой правки в коде; укажите конкретный тег"));
        }
    }
}
