package ru.akvine.zond.cli;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Нумерованное меню: печатает пункты и ждет, пока пользователь выберет один из них
 */
@Component
@RequiredArgsConstructor
public class ConsoleMenu {
    private final ConsoleInput input;

    /**
     * @return номер выбранного пункта, начиная с 1
     * @throws InputClosedException если ввод закончился
     */
    public int choose(String title, List<String> items) {
        System.out.println();
        System.out.println(title);
        for (int index = 0; index < items.size(); index++) {
            System.out.println("  " + (index + 1) + ". " + items.get(index));
        }

        while (true) {
            String answer = input.ask("Выберите пункт: ");
            try {
                int choice = Integer.parseInt(answer);
                if (choice >= 1 && choice <= items.size()) {
                    return choice;
                }
            } catch (NumberFormatException exception) {
                // Не число - сообщаем ниже и спрашиваем еще раз
            }
            System.out.println("Введите число от 1 до " + items.size());
        }
    }
}
