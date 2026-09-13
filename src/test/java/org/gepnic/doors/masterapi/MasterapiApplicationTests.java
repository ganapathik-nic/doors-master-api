package org.gepnic.doors.masterapi;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "DOORS_RUN_INTEGRATION_TESTS", matches = "true")
class MasterapiApplicationTests {

	@Test
	void contextLoads() {
	}

}
