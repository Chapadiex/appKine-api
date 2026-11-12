package com.akine;

import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Base real para los tests de integracion: un servidor por JVM y una base por contexto de Spring.
 *
 * <p>La imagen es la misma del compose.yaml —PostgreSQL 18 con pgvector 0.8.1— y no "latest": un
 * test que corre contra una version distinta de la del despliegue no prueba lo que dice probar.
 * Se declara compatible con {@code postgres} porque es la imagen oficial con la extension agregada.
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
 * <p>{@code max_connections} difiere del despliegue por este diseño: la cache de Spring guarda
 * hasta 32 contextos con su pool abierto contra este unico servidor. El directorio en memoria y
 * {@code fsync}/{@code synchronous_commit} apagados tampoco son los del despliegue: sobrevivir a un
 * corte de luz no le importa a un test y no cambia locks, aislamiento ni SQL.
 *
 * <p>Requiere Docker corriendo.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	static {
		// Igual que AkineApiApplication: el driver manda la zona de la JVM al conectar, y el alias
		// legado que elige Windows (America/Buenos_Aires) no existe en el tzdata de la imagen.
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
	}

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
			DockerImageName.parse("pgvector/pgvector:0.8.1-pg18-trixie").asCompatibleSubstituteFor("postgres"))
			.withUsername("test")
			.withTmpFs(Map.of("/var/lib/postgresql", "rw"))
			.withCommand("postgres", "-c", "max_connections=1000", "-c", "fsync=off",
					"-c", "synchronous_commit=off", "-c", "full_page_writes=off", "-c", "timezone=UTC");

	private static final AtomicInteger BASES = new AtomicInteger();

	/** Para el test que crea sus propias bases descartables, con el superusuario del contenedor. */
	public static PostgreSQLContainer servidor() {
		return POSTGRES;
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
		POSTGRES.start();
		String base = "akine_it_" + BASES.incrementAndGet();
		crearBase(base);
		String url = POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + base);
		return new JdbcConnectionDetails() {
			@Override
			public String getUsername() {
				return POSTGRES.getUsername();
			}

			@Override
			public String getPassword() {
				return POSTGRES.getPassword();
			}

			@Override
			public String getJdbcUrl() {
				return url;
			}
		};
	}

	/**
	 * El usuario del contenedor es superusuario, asi que la base queda suya y la extension vector se
	 * puede crear desde la migracion, como en un servidor recien inicializado por ops/postgres/init.
	 */
	private static void crearBase(String base) {
		try (Connection admin = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
				POSTGRES.getPassword());
				Statement sql = admin.createStatement()) {
			sql.execute("CREATE DATABASE " + base);
		} catch (SQLException e) {
			throw new IllegalStateException("No se pudo crear la base " + base, e);
		}
	}
}
