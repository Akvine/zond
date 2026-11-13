package ru.akvine.zond;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// Страница в браузере здесь выключена явно: будь она включена в app.properties рядом с проектом,
// тест открыл бы браузер и ждал бы, пока на странице не нажмут "Завершить"
@SpringBootTest(properties = "zond.ui.enabled=false")
class ZondApplicationTests {

	@Test
	void contextLoads() {
	}

}
