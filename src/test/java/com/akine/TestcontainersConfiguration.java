package com.akine;

import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Base real para los tests de integracion: un servidor por JVM y una base por contexto de Spring.
 *
 * <p>La imagen esta fijada en 8.4 —la misma del compose.yaml y la aprobada por el plan— y
 * no en "latest": un test que corre contra una version distinta de la de produccion no
 * prueba lo que dice probar.
 *
 * <p><b>Por que un solo servidor.</b> Cuando el contenedor era un {@code @Bean} cada contexto
 * distinto levantaba el suyo: 17 en una corrida del CI, ~2,5 min de arranques, y todos vivos a la
 * vez mientras el contexto siguiera en la cache de Spring.
 *
 * <p><b>Por que una base por contexto y no una sola.</b> Cada contexto tiene sus workers
 * programados —el del outbox, entre otros— y compartir el esquema les dejaria tomar filas de los
 * tests de otro contexto. Ademas hay tests que dependen de empezar sin ciertos datos, como
 * {@code PlatformAdminSinBootstrapIT}. Asi el aislamiento es el mismo que con un contenedor por
 * contexto.
 *
 * <p>Requiere Docker corriendo.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	private static final String USUARIO = "test";

	/**
	 * El servidor, con la misma configuracion que {@code compose.yaml}. No es un bean: si lo fuera,
	 * cerrar un contexto lo apagaria para los demas. Lo baja Ryuk al terminar la JVM.
	 *
	 * <p><b>{@code --log-bin-trust-function-creators=1} no es un ajuste de comodidad.</b> La
	 * migracion V14 crea los dos triggers que hacen {@code audit_event} append-only en la BASE
	 * (RN-M24-001, AKINE-01.03). Con el log binario activo —el default de MySQL 8.4— crear un
	 * trigger exige {@code SUPER}, y el usuario que Testcontainers crea no lo tiene.
	 * Sin esta bandera, V14 falla con
	 * <i>"You do not have the SUPER privilege and binary logging is enabled"</i> y no arranca
	 * ningun test de integracion.
	 *
	 * <p>Tiene que estar tambien en el servidor real, y por eso esta en {@code compose.yaml} con
	 * la explicacion de lo que implica. Un test que corre con una configuracion de servidor
	 * distinta de la de produccion no prueba lo que dice probar — que es el mismo motivo por el
	 * que la imagen esta fijada en 8.4.
	 *
	 * <p>{@code --max-connections} si difiere, y es por este diseño: la cache de Spring guarda hasta
	 * 32 contextos con su pool abierto contra este unico servidor, y el default de 151 no alcanza.
	 *
	 * <p>El directorio en memoria y las tres banderas de durabilidad tampoco son las de produccion:
	 * sobrevivir a un corte de luz no le importa a un test y no cambia locks, aislamiento ni SQL. Las
	 * migraciones son casi todas DDL, que fuerza el disco en cada sentencia.
	 *
	 * <p>{@code withCommand} reemplaza el CMD de la imagen, asi que {@code mysqld} tiene que ir
	 * explicito: sin el, el contenedor arrancaria la bandera como si fuera el ejecutable.
	 */
	private static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
			.withUsername(USUARIO)
			.withTmpFs(Map.of("/var/lib/mysql", "rw"))
			.withCommand("mysqld", "--log-bin-trust-function-creators=1", "--max-connections=1000",
					"--innodb-flush-log-at-trx-commit=0", "--sync-binlog=0", "--innodb-doublewrite=0");

	private static final AtomicInteger BASES = new AtomicInteger();

	/** Para el test que crea sus propias bases descartables, con el usuario root. */
	public static MySQLContainer servidor() {
		return MYSQL;
	}

	/**
	 * Se evalua una vez por contexto: cada uno migra y usa su propia base.
	 *
	 * <p><b>Tiene que ser este bean y no un {@code DynamicPropertyRegistrar}.</b> El
	 * {@code DataSource} lo inyecta, asi que nunca puede armarse sin el. Con el registrar, un contexto
	 * con servidor web pide los filtros —y con ellos el {@code DataSource}— antes de aplicar las
	 * propiedades, y Flyway migra la base del perfil {@code local}: paso el 01/11/2026 con
	 * {@code akine_local}.
	 */
	@Bean
	JdbcConnectionDetails baseDelContexto() {
		MYSQL.start();
		int numero = BASES.incrementAndGet();
		crearBase(numero);
		String url = MYSQL.getJdbcUrl().replace("/" + MYSQL.getDatabaseName(), "/akine_it_" + numero);
		return new JdbcConnectionDetails() {
			@Override
			public String getUsername() {
				return MYSQL.getUsername();
			}

			@Override
			public String getPassword() {
				return MYSQL.getPassword();
			}

			@Override
			public String getJdbcUrl() {
				return url;
			}
		};
	}

	private static void crearBase(int numero) {
		try (Connection root = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
				Statement sql = root.createStatement()) {
			sql.execute("CREATE DATABASE akine_it_" + numero);
			sql.execute("GRANT ALL ON akine_it_" + numero + ".* TO '" + USUARIO + "'@'%'");
		} catch (SQLException e) {
			throw new IllegalStateException("No se pudo crear la base akine_it_" + numero, e);
		}
	}
}
