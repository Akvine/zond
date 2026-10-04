package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.rules.logical.CheckFixedSizeListModificationRule;
import ru.akvine.zond.rules.logical.CheckImmutableCollectionModificationRule;
import ru.akvine.zond.rules.logical.CheckIteratorNextWithoutHasNextRule;
import ru.akvine.zond.rules.logical.CheckListGetFirstWithoutCheckRule;
import ru.akvine.zond.rules.performance.CheckLinkedListGetInLoopRule;
import ru.akvine.zond.rules.performance.CheckListContainsInLoopRule;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила jr:66 - jr:71: работа с коллекциями
 */
class CollectionRulesTest {

    @Test
    void listGetFirstWithoutCheck() {
        assertThat(RuleTests.lines(new CheckListGetFirstWithoutCheckRule(), """
                class Sample {
                    String first(List<String> names, List<String> checked, Map<Integer, String> map) {
                        String a = names.get(0);
                        String b = repository.findAll().get(0);
                        String c = names.stream().sorted().toList().get(0);
                        if (!checked.isEmpty()) {
                            return checked.get(0);
                        }
                        String d = map.get(0);
                        String e = names.get(1);
                        return a;
                    }
                }
                """)).containsExactly(3, 4, 5);
    }

    @Test
    void iteratorNextWithoutHasNext() {
        assertThat(RuleTests.lines(new CheckIteratorNextWithoutHasNextRule(), """
                class Sample {
                    String run(Iterator<String> iterator, Iterator<String> checked, List<String> items,
                               List<String> guarded, Scanner scanner) {
                        String a = iterator.next();
                        String b = items.iterator().next();
                        if (checked.hasNext()) {
                            return checked.next();
                        }
                        if (!guarded.isEmpty()) {
                            return guarded.iterator().next();
                        }
                        return scanner.next();
                    }
                }
                """)).containsExactly(4, 5);
    }

    @Test
    void fixedSizeListModification() {
        assertThat(RuleTests.lines(new CheckFixedSizeListModificationRule(), """
                class Sample {
                    private final List<String> fixed = Arrays.asList("a", "b");
                    void run() {
                        fixed.add("c");
                        List<String> local = Arrays.asList("a");
                        local.remove("a");
                        Arrays.asList("a").clear();
                        local.set(0, "b");
                        List<String> copy = new ArrayList<>(Arrays.asList("a"));
                        copy.add("b");
                        List<String> reassigned = Arrays.asList("a");
                        reassigned = new ArrayList<>();
                        reassigned.add("b");
                    }
                }
                """)).containsExactly(4, 6, 7);
    }

    @Test
    void immutableCollectionModification() {
        assertThat(RuleTests.lines(new CheckImmutableCollectionModificationRule(), """
                class Sample {
                    private final Map<String, String> settings = Map.of("a", "b");
                    void run(List<String> source) {
                        settings.put("c", "d");
                        List<String> names = List.of("a");
                        names.add("b");
                        List<String> upper = source.stream().map(String::toUpperCase).toList();
                        upper.sort(null);
                        Collections.emptyList().add("x");
                        Map<String, String> mutable = new HashMap<>(Map.of("a", "b"));
                        mutable.put("c", "d");
                        List<String> collected = source.stream().collect(Collectors.toList());
                        collected.add("x");
                        boolean has = names.contains("a");
                    }
                }
                """)).containsExactly(4, 6, 8, 9);
    }

    @Test
    void linkedListGetInLoop() {
        assertThat(RuleTests.lines(new CheckLinkedListGetInLoopRule(), """
                class Sample {
                    void run(LinkedList<String> linked, ArrayList<String> array) {
                        List<String> declared = new LinkedList<>();
                        for (int i = 0; i < linked.size(); i++) {
                            use(linked.get(i));
                            use(declared.get(i));
                            use(array.get(i));
                        }
                        use(linked.get(0));
                    }
                }
                """)).containsExactly(5, 6);
    }

    @Test
    void listContainsInLoop() {
        assertThat(RuleTests.lines(new CheckListContainsInLoopRule(), """
                class Sample {
                    void run(List<String> names, Set<String> unique, List<String> items) {
                        for (String item : items) {
                            if (names.contains(item)) {
                                use(item);
                            }
                            if (unique.contains(item)) {
                                use(item);
                            }
                            List<String> local = new ArrayList<>();
                            local.contains(item);
                        }
                        items.stream().filter(item -> names.contains(item)).toList();
                        names.contains("x");
                    }
                }
                """)).containsExactly(4, 13);
    }
}
