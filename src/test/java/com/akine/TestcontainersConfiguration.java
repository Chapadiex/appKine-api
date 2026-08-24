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

	/**
	 * El contenedor, con la misma configuracion de servidor que {@code compose.yaml}.
	 *
	 * <p><b>{@code --log-bin-trust-function-creators=1} no es un ajuste de comodidad.</b> La
	 * migracion V14 crea los dos triggers que hacen {@code audit_event} append-only en la BASE
	 * (RN-M24-001, AKINE-01.03). Con el log binario activo —el default de MySQL 8.4— crear un
	 * trigger exige {@code SUPER}, y el usuario que Testcontainers crea tiene privilegios solo
	 * sobre su esquema. Sin esta bandera, V14 falla con
	 * <i>"You do not have the SUPER privilege and binary logging is enabled"</i> y no arranca
	 * ningun test de integracion.
	 *
	 * <p>Tiene que estar tambien en el servidor real, y por eso esta en {@code compose.yaml} con
	 * la explicacion de lo que implica. Un test que corre con una configuracion de servidor
	 * distinta de la de produccion no prueba lo que dice probar — que es el mismo motivo por el
	 * que la imagen esta fijada en 8.4.
	 *
	 * <p>{@code withCommand} reemplaza el CMD de la imagen, asi que {@code mysqld} tiene que ir
	 * explicito: sin el, el contenedor arrancaria la bandera como si fuera el ejecutable.
	 */
	@Bean
	@ServiceConnection
	MySQLContainer mysqlContainer() {
		return new MySQLContainer(DockerImageName.parse("mysql:8.4"))
				.withCommand("mysqld", "--log-bin-trust-function-creators=1");
	}
}
