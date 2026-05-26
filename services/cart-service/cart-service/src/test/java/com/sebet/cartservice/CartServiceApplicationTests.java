package com.sebet.cartservice;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
		"spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.orm.jpa.autoconfigure.HibernateJpaAutoConfiguration"
})
@Disabled("Requires full application infrastructure/repository wiring")
class CartServiceApplicationTests {

	@Test
	void contextLoads() {
	}

}
