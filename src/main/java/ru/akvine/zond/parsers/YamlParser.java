package ru.akvine.zond.parsers;

import lombok.experimental.UtilityClass;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Разбор YAML библиотекой SnakeYAML. Берется только дерево узлов: объекты по тегам не создаются,
 * поэтому незнакомые теги (!reference в GitLab CI) и чужие файлы разбору не мешают.
 */
@UtilityClass
public class YamlParser {
    // Якорь, который ссылается сам на себя, иначе увел бы обход в бесконечность
    private static final int MAX_DEPTH = 50;
    private static final String LINE_SEPARATOR = "\n";

    /**
     * @return документы файла (их разделяет "---") либо пусто, если файл - не корректный YAML
     */
    public Optional<List<YamlNode>> parse(List<String> lines) {
        try {
            List<YamlNode> documents = new ArrayList<>();
            for (Node document : new Yaml().composeAll(new StringReader(String.join(LINE_SEPARATOR, lines)))) {
                if (document != null) {
                    documents.add(convert(document, lineOf(document), 0));
                }
            }
            return Optional.of(documents);
        } catch (RuntimeException exception) {
            // Шаблон Helm, подстановка @project.version@ и прочее, что YAML не является
            return Optional.empty();
        }
    }

    private YamlNode convert(Node node, int line, int depth) {
        if (depth > MAX_DEPTH) {
            return YamlNode.missing();
        }
        if (node instanceof ScalarNode scalar) {
            return YamlNode.scalar(line, scalar.getValue());
        }
        if (node instanceof MappingNode mapping) {
            Map<String, YamlNode> entries = new LinkedHashMap<>();
            for (NodeTuple tuple : mapping.getValue()) {
                if (tuple.getKeyNode() instanceof ScalarNode key) {
                    // Значению отдаем строку ключа: на нее и нужно показывать в отчете
                    entries.put(key.getValue(), convert(tuple.getValueNode(), lineOf(key), depth + 1));
                }
            }
            return YamlNode.map(line, entries);
        }
        if (node instanceof SequenceNode sequence) {
            List<YamlNode> items = new ArrayList<>();
            for (Node item : sequence.getValue()) {
                items.add(convert(item, lineOf(item), depth + 1));
            }
            return YamlNode.list(line, items);
        }
        return YamlNode.missing();
    }

    private int lineOf(Node node) {
        return node.getStartMark().getLine() + 1;
    }
}
