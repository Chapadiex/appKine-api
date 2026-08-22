package com.akine;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base real para los tests de integracion.
 *
 * <p>La imagen esta fijada en 8.4 —la misma del compose.yaml y la aprobada por el plan— y
 * no en "latest": un test que corre contra una version distinta de la de produccion no
 * prueba lo que dice probar.
 *
 * <p>Requiere Docker corriendo.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	MySQLContainer mysqlContainer() {
		return new MySQLContainer(DockerImageName.parse("mysql:8.4"));
	}
}
