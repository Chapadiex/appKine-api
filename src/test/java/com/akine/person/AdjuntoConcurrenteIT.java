package com.akine.person;

import com.akine.TestcontainersConfiguration;
import com.akine.person.application.AdjuntoAltaCommand;
import com.akine.person.application.AdjuntoService;
import com.akine.person.application.AdjuntoService.AdjuntoAlta;
import com.akine.person.application.OperatingActor;
import com.akine.person.domain.CategoriaAdjunto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La subida idempotente del adjunto ADMINISTRATIVO (M25) con varios hilos contra MySQL real
 * (AKINE B-6, escenario 27 de {@code docs/tests-diferidos.md}).
 *
 * <h2>Por que hace falta</h2>
 *
 * <p>{@code AdjuntoService.subir} promete que subir dos veces el mismo contenido devuelve el
 * adjunto que ya existe. El pre-chequeo por checksum lo cumple en el camino feliz; con dos
 * subidas SIMULTANEAS los dos pre-chequeos dan vacio, las dos insertan, y quien decide es el
 * unique de {@code adjunto_administrativo}. El servicio atrapa ese choque —el INSERT va en una
 * transaccion propia, {@code AdjuntoEscrituraAparte}— y relee la fila ganadora para devolverla.
 *
 * <p>Hasta ahora eso estaba "probado por el unique", no por dos hilos (registro de 03.06 en el
 * plan de implementacion). Es justamente el escenario que en el adjunto CLINICO destapo un
 * defecto la primera vez que corrio: la relectura no ve al ganador bajo {@code REPEATABLE READ}
 * y la idempotencia termina en un error. Ningun unitario lo reproduce: la foto de InnoDB y el
 * choque contra el unique los pone el motor.
 *
 * <h2>Resultado de la primera corrida (08/10/2026, sobre {@code main} 9c15b95): DEFECTO</h2>
 *
 * <p>Los dos escenarios de contenido identico FALLAN, y es el mismo defecto que ya se corrigio en
 * {@code clinical.AdjuntoClinicoService}: {@code subir} corre con el aislamiento por defecto
 * ({@code REPEATABLE READ}), su pre-chequeo por checksum es la primera lectura consistente y fija
 * la foto ANTES de que el ganador commitee, asi que la relectura del {@code catch} no encuentra la
 * fila, el {@code orElseThrow} relanza el choque y el perdedor recibe un
 * {@code DataIntegrityViolationException} —un 409 {@code conflict} por el handler global— donde el
 * contrato promete la respuesta idempotente. Con
 * {@code @Transactional(isolation = Isolation.READ_COMMITTED)} en {@code subir} los tres pasan
 * (verificado localmente, sin commitear: el arreglo es del backend y va en su propio cambio).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class AdjuntoConcurrenteIT {

	private static final String ZONA = "America/Argentina/Cordoba";

	@Autowired private AdjuntoService servicio;
	@Autowired private JdbcTemplate jdbc;

	@Test
	@DisplayName("dos subidas simultaneas del mismo contenido: ninguna falla, una crea y las dos devuelven el mismo adjunto")
	void dos_subidas_iguales_son_una() {
		Fixture fixture = crearFixture();
		byte[] contenido = pdf("credencial que se reintenta " + fixture.personaId());

		List<Desenlace<AdjuntoAlta>> desenlaces = enParalelo(List.of(
				() -> subir(fixture, contenido),
				() -> subir(fixture, contenido)));

		assertThat(desenlaces.stream().filter(Desenlace::fallo).toList())
				.as("un reintento no es un error: ninguna subida falla. Desenlaces: %s", desenlaces)
				.isEmpty();
		assertThat(desenlaces.stream().filter(d -> d.valor().creado()).count())
				.as("exactamente una crea; la otra es la respuesta idempotente. Desenlaces: %s",
						desenlaces)
				.isEqualTo(1);
		assertThat(desenlaces.stream().map(d -> d.valor().adjunto().id()).distinct().count())
				.as("y las dos devuelven el MISMO adjunto. Desenlaces: %s", desenlaces)
				.isEqualTo(1);
		assertThat(contarVigentes(fixture))
				.as("en la base queda una sola fila")
				.isEqualTo(1);
	}

	@Test
	@DisplayName("una rafaga de cuatro subidas iguales deja una sola fila y ningun error")
	void la_rafaga_no_duplica() {
		Fixture fixture = crearFixture();
		byte[] contenido = pdf("comprobante de la rafaga " + fixture.personaId());

		List<Callable<AdjuntoAlta>> rafaga = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			rafaga.add(() -> subir(fixture, contenido));
		}
		List<Desenlace<AdjuntoAlta>> desenlaces = enParalelo(rafaga);

		assertThat(desenlaces.stream().filter(Desenlace::fallo).toList())
				.as("ninguna de las cuatro falla. Desenlaces: %s", desenlaces)
				.isEmpty();
		assertThat(desenlaces.stream().filter(d -> d.valor().creado()).count())
				.as("una sola crea. Desenlaces: %s", desenlaces)
				.isEqualTo(1);
		assertThat(contarVigentes(fixture)).isEqualTo(1);
	}

	@Test
	@DisplayName("control: dos contenidos distintos subidos a la vez entran los dos")
	void contenidos_distintos_no_se_pisan() {
		// Sin este control, un servicio que serializara TODAS las subidas de una persona —o que
		// devolviera siempre "ya existia"— pasaria los dos tests de arriba. La idempotencia es por
		// checksum, no por persona.
		Fixture fixture = crearFixture();

		List<Desenlace<AdjuntoAlta>> desenlaces = enParalelo(List.of(
				() -> subir(fixture, pdf("frente del DNI " + fixture.personaId())),
				() -> subir(fixture, pdf("dorso del DNI " + fixture.personaId()))));

		assertThat(desenlaces.stream().filter(Desenlace::fallo).toList())
				.as("Desenlaces: %s", desenlaces)
				.isEmpty();
		assertThat(desenlaces.stream().filter(d -> d.valor().creado()).count())
				.as("las dos crean. Desenlaces: %s", desenlaces)
				.isEqualTo(2);
		assertThat(contarVigentes(fixture)).isEqualTo(2);
	}

	// =================================================================================
	// Ejecucion concurrente
	// =================================================================================

	/** Barrera de salida para que las N tareas arranquen juntas y no en fila. */
	private static <T> List<Desenlace<T>> enParalelo(List<Callable<T>> tareas) {
		CyclicBarrier salida = new CyclicBarrier(tareas.size());
		try (ExecutorService pool = Executors.newFixedThreadPool(tareas.size())) {
			List<Future<Desenlace<T>>> futuros = new ArrayList<>();
			for (Callable<T> tarea : tareas) {
				futuros.add(pool.submit(() -> {
					salida.await(10, TimeUnit.SECONDS);
					try {
						return new Desenlace<>(tarea.call(), null);
					} catch (Exception error) {
						return new Desenlace<T>(null, error);
					}
				}));
			}
			List<Desenlace<T>> desenlaces = new ArrayList<>();
			for (Future<Desenlace<T>> futuro : futuros) {
				desenlaces.add(futuro.get(30, TimeUnit.SECONDS));
			}
			return desenlaces;
		} catch (Exception error) {
			throw new IllegalStateException("La ejecucion concurrente no pudo completarse", error);
		}
	}

	private record Desenlace<T>(T valor, Exception error) {

		boolean fallo() {
			return error != null;
		}

		@Override
		public String toString() {
			return fallo()
					? "FALLO(" + error.getClass().getSimpleName() + ": " + error.getMessage() + ")"
					: "OK(" + valor + ")";
		}
	}

	// =================================================================================
	// Fixture
	// =================================================================================

	private AdjuntoAlta subir(Fixture fixture, byte[] contenido) {
		return servicio.subir(fixture.actor(), fixture.personaId(), new AdjuntoAltaCommand(
				CategoriaAdjunto.CREDENCIAL_COBERTURA, "Credencial sintetica", "credencial.pdf",
				"application/pdf", contenido));
	}

	/** Un PDF minimo: el servicio detecta el tipo por los bytes, no por lo declarado. */
	private static byte[] pdf(String marca) {
		return ("%PDF-1.7\n% " + marca + "\n").getBytes(StandardCharsets.UTF_8);
	}

	private int contarVigentes(Fixture fixture) {
		return jdbc.queryForObject("""
				SELECT COUNT(*) FROM adjunto_administrativo
				WHERE persona_id = ? AND deleted_at IS NULL
				""", Integer.class, fixture.personaId());
	}

	private record Fixture(long personaId, OperatingActor actor) {
	}

	private Fixture crearFixture() {
		String sufijo = UUID.randomUUID().toString().substring(0, 12);
		long organizationId = insertarOrganization(sufijo);
		long consultorioId = insertarConsultorio(organizationId, sufijo);
		long accountId = insertarCuenta(sufijo);
		insertarMembership(organizationId, consultorioId, accountId);
		long personaId = insertarPersona(organizationId, sufijo);
		return new Fixture(personaId,
				new OperatingActor(accountId, false, organizationId, consultorioId));
	}

	private long insertarOrganization(String sufijo) {
		String slug = "adjunto-it-" + sufijo;
		jdbc.update("""
				INSERT INTO organization (name, slug, timezone, active, version, created_at, updated_at)
				VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "Centro Sintetico " + sufijo, slug, ZONA);
		return jdbc.queryForObject("SELECT id FROM organization WHERE slug = ?", Long.class, slug);
	}

	private long insertarConsultorio(long organizationId, String sufijo) {
		String nombre = "Sede Sintetica " + sufijo;
		jdbc.update("""
				INSERT INTO consultorio (organization_id, name, timezone, active, version,
				                         created_at, updated_at)
				VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, nombre, ZONA);
		return jdbc.queryForObject(
				"SELECT id FROM consultorio WHERE organization_id = ? AND name = ?",
				Long.class, organizationId, nombre);
	}

	private long insertarCuenta(String sufijo) {
		String email = "adjunto-it-" + sufijo + "@ejemplo.test";
		jdbc.update("""
				INSERT INTO cuenta (email, email_normalizado, nombre, apellido, estado,
				                    active, version, created_at, updated_at)
				VALUES (?, ?, 'Sintetico', 'DePrueba', 'ACTIVA', 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", email, email);
		return jdbc.queryForObject(
				"SELECT id FROM cuenta WHERE email_normalizado = ?", Long.class, email);
	}

	/** {@code CONSULTORIO_ADMIN}: tiene {@code paciente:manage}, que es lo que exige subir. */
	private void insertarMembership(long organizationId, long consultorioId, long accountId) {
		jdbc.update("""
				INSERT INTO membership (organization_id, consultorio_id, account_id, role_code,
				                        is_founder, valid_from, estado, active, version,
				                        created_at, updated_at)
				VALUES (?, ?, ?, 'CONSULTORIO_ADMIN', 0,
				        DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 5 YEAR), 'ACTIVA', 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, consultorioId, accountId);
	}

	private long insertarPersona(long organizationId, String sufijo) {
		String documento = String.valueOf(10000000 + Math.abs(sufijo.hashCode()) % 80000000);
		jdbc.update("""
				INSERT INTO persona (organization_id, tipo_documento, numero_documento,
				                     documento_clave, apellido, nombre, apellido_clave,
				                     nombre_clave, created_at, updated_at)
				VALUES (?, 'DNI', ?, ?, 'Sintetica', 'Adjunto', 'SINTETICA', 'ADJUNTO',
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, documento, documento);
		return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}
}
