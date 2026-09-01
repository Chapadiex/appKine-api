package com.akine.encounter;

import com.akine.TestcontainersConfiguration;
import com.akine.encounter.application.OperatingActor;
import com.akine.encounter.application.SesionService;
import com.akine.encounter.application.SesionView;
import com.akine.encounter.domain.Asistencia;
import com.akine.encounter.domain.CierreDeSesion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
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
 * El correlativo de sesion: <b>dos cierres concurrentes no se llevan el mismo numero</b>.
 *
 * <h2>Por que no puede ser un test unitario</h2>
 *
 * <p>Lo que decide la correctitud es que el {@code UPDATE ... SET ultimo_numero = ultimo_numero + 1}
 * tome un lock exclusivo de fila y serialice, y eso solo lo puede contestar MySQL. Un mock
 * verificaria el orden de las llamadas, no que dos transacciones se esperen.
 *
 * <p>El caso real: un profesional cierra la sesion de hoy mientras otro cierra una sesion vieja del
 * MISMO paciente. Con {@code SELECT MAX(numero) + 1} las dos leen el mismo maximo y las dos escriben
 * el mismo numero — y el unique lo rechaza, asi que una se cae con un 500 en vez de tomar el
 * siguiente.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class CierreConcurrenteIT {

	private static final String ZONA = "America/Argentina/Cordoba";

	@Autowired private SesionService sesionService;
	@Autowired private JdbcTemplate jdbc;

	@Test
	@DisplayName("dos cierres concurrentes del mismo paciente reciben numeros distintos y consecutivos")
	void dos_cierres_concurrentes_no_repiten_numero() {
		Fixture fixture = crearFixture();

		Callable<SesionView> primera = () -> cerrar(fixture, fixture.sesionA());
		Callable<SesionView> segunda = () -> cerrar(fixture, fixture.sesionB());

		List<Desenlace> desenlaces = enParalelo(List.of(primera, segunda));

		assertThat(desenlaces.stream().filter(Desenlace::fallo).toList())
				.as("los dos cierres tienen que entrar: no compiten por un recurso, solo por el numero")
				.isEmpty();

		List<Integer> numeros = jdbc.queryForList("""
				SELECT numero_sesion FROM sesion
				 WHERE historia_clinica_id = ? AND numero_sesion IS NOT NULL
				 ORDER BY numero_sesion
				""", Integer.class, fixture.historiaClinicaId());

		assertThat(numeros)
				.as("uno y dos, sin repetir y sin huecos. Desenlaces: %s", desenlaces)
				.containsExactly(1, 2);
	}

	@Test
	@DisplayName("cerrar dos veces la misma sesion no renumera ni consume un correlativo")
	void el_cierre_es_idempotente() {
		// RN-M14-005: resultado estable ante retry. Y si la idempotencia se evaluara DESPUES de
		// pedir el numero, cada reintento consumiria un correlativo que nadie usa y la numeracion
		// del paciente quedaria con huecos que parecen sesiones borradas.
		Fixture fixture = crearFixture();

		SesionView primera = cerrar(fixture, fixture.sesionA());
		SesionView segunda = cerrar(fixture, fixture.sesionA());

		assertThat(segunda.numeroSesion()).isEqualTo(primera.numeroSesion());
		assertThat(jdbc.queryForObject("""
				SELECT ultimo_numero FROM sesion_numerador WHERE historia_clinica_id = ?
				""", Integer.class, fixture.historiaClinicaId()))
				.as("el numerador avanzo UNA vez, no dos")
				.isEqualTo(1);
	}

	private SesionView cerrar(Fixture fixture, long sesionId) {
		SesionView actual = sesionService.ver(fixture.actor(), fixture.consultorioId(), sesionId);
		return sesionService.cerrar(fixture.actor(), fixture.consultorioId(), sesionId,
				new CierreDeSesion(Asistencia.PRESENTE, "Terapia manual", null, null, null, null),
				actual.version());
	}

	// =================================================================================
	// Ejecucion concurrente
	// =================================================================================

	/** La barrera es lo que hace real la carrera: sin ella la primera suele terminar antes. */
	private static List<Desenlace> enParalelo(List<Callable<SesionView>> tareas) {
		CyclicBarrier salida = new CyclicBarrier(tareas.size());
		try (ExecutorService pool = Executors.newFixedThreadPool(tareas.size())) {
			List<Future<Desenlace>> futuros = new ArrayList<>();
			for (Callable<SesionView> tarea : tareas) {
				futuros.add(pool.submit(() -> {
					salida.await(10, TimeUnit.SECONDS);
					try {
						return new Desenlace(tarea.call(), null);
					} catch (Exception error) {
						return new Desenlace(null, error);
					}
				}));
			}
			List<Desenlace> desenlaces = new ArrayList<>();
			for (Future<Desenlace> futuro : futuros) {
				desenlaces.add(futuro.get(30, TimeUnit.SECONDS));
			}
			return desenlaces;
		} catch (Exception fallo) {
			throw new IllegalStateException("La ejecucion concurrente no pudo completarse", fallo);
		}
	}

	private record Desenlace(SesionView sesion, Exception error) {

		boolean fallo() {
			return error != null;
		}

		@Override
		public String toString() {
			return fallo()
					? "FALLO(" + error.getClass().getSimpleName() + ": " + error.getMessage() + ")"
					: "OK(numero=" + sesion.numeroSesion() + ")";
		}
	}

	// =================================================================================
	// Fixture
	// =================================================================================

	private record Fixture(
			long consultorioId, long historiaClinicaId, long sesionA, long sesionB,
			OperatingActor actor) {
	}

	/**
	 * Dos sesiones del MISMO paciente, ya abiertas, listas para cerrar.
	 *
	 * <p>Se insertan directo y no por el servicio: abrirlas exigiria dos turnos, dos slots y toda
	 * la cadena de M12, que ya tiene su propio test. Lo que este test mide es el numerador.
	 */
	private Fixture crearFixture() {
		String sufijo = UUID.randomUUID().toString().substring(0, 8);

		long organizationId = insertar("""
				INSERT INTO organization (name, slug, timezone, active, version, created_at, updated_at)
				VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "SELECT id FROM organization WHERE slug = ?",
				new Object[]{"Centro " + sufijo, "cierre-it-" + sufijo, ZONA},
				new Object[]{"cierre-it-" + sufijo});

		long consultorioId = insertar("""
				INSERT INTO consultorio (organization_id, name, timezone, active, version, created_at, updated_at)
				VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "SELECT id FROM consultorio WHERE organization_id = ? AND name = ?",
				new Object[]{organizationId, "Sede " + sufijo, ZONA},
				new Object[]{organizationId, "Sede " + sufijo});

		String email = "cierre-it-" + sufijo + "@ejemplo.test";
		long cuentaId = insertar("""
				INSERT INTO cuenta (email, email_normalizado, nombre, apellido, estado, active,
				                    version, created_at, updated_at)
				VALUES (?, ?, 'Sintetico', 'DePrueba', 'ACTIVA', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "SELECT id FROM cuenta WHERE email_normalizado = ?",
				new Object[]{email, email}, new Object[]{email});

		long membershipId = insertar("""
				INSERT INTO membership (organization_id, consultorio_id, account_id, role_code,
				                        is_founder, valid_from, estado, active, version,
				                        created_at, updated_at)
				VALUES (?, ?, ?, 'PROFESIONAL', 0, DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 5 YEAR),
				        'ACTIVA', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", """
				SELECT id FROM membership WHERE organization_id = ? AND account_id = ?
				""", new Object[]{organizationId, consultorioId, cuentaId},
				new Object[]{organizationId, cuentaId});

		String apellido = "Paciente" + sufijo;
		long personaId = insertar("""
				INSERT INTO persona (organization_id, apellido, nombre, apellido_clave, nombre_clave,
				                     active, version, created_at, updated_at)
				VALUES (?, ?, 'Sintetico', ?, 'SINTETICO', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "SELECT id FROM persona WHERE organization_id = ? AND apellido = ?",
				new Object[]{organizationId, apellido, apellido.toUpperCase()},
				new Object[]{organizationId, apellido});

		jdbc.update("""
				INSERT INTO perfil_paciente (organization_id, persona_id, activado_en, activado_por,
				                             active, version, created_at, updated_at)
				VALUES (?, ?, UTC_TIMESTAMP(6), ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, personaId, cuentaId);

		long historiaClinicaId = insertar("""
				INSERT INTO historia_clinica (organization_id, persona_id, abierta_en, abierta_por,
				                              active, version, created_at, updated_at)
				VALUES (?, ?, UTC_TIMESTAMP(6), ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "SELECT id FROM historia_clinica WHERE organization_id = ? AND persona_id = ?",
				new Object[]{organizationId, personaId, cuentaId},
				new Object[]{organizationId, personaId});

		long servicioId = insertar("""
				INSERT INTO servicio (codigo, nombre, naturaleza, modalidad_default,
				                      requiere_caso_clinico_default, genera_registro_clinico_default,
				                      active, version, created_at, updated_at)
				VALUES (?, ?, 'CLINICO', 'INDIVIDUAL', 0, 0, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "SELECT id FROM servicio WHERE codigo = ?",
				new Object[]{"CIERRE-" + sufijo.toUpperCase(), "Servicio " + sufijo},
				new Object[]{"CIERRE-" + sufijo.toUpperCase()});

		long ofertaId = insertar("""
				INSERT INTO oferta_servicio_consultorio
				       (organization_id, consultorio_id, servicio_id, nombre_comercial, modalidad,
				        duracion_minutos, capacidad, precio_base, moneda, admite_obra_social,
				        requiere_caso_clinico, genera_registro_clinico, requiere_profesional,
				        requiere_espacio, vigencia_desde, active, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, 'INDIVIDUAL', 60, 1, 8500.00, 'ARS', 0, 0, 0, 1, 0,
				        DATE_SUB(CURDATE(), INTERVAL 5 YEAR), 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", """
				SELECT id FROM oferta_servicio_consultorio
				 WHERE organization_id = ? AND nombre_comercial = ?
				""", new Object[]{organizationId, consultorioId, servicioId, "Oferta " + sufijo},
				new Object[]{organizationId, "Oferta " + sufijo});

		return new Fixture(
				consultorioId, historiaClinicaId,
				insertarSesion(organizationId, consultorioId, historiaClinicaId, ofertaId, membershipId, cuentaId),
				insertarSesion(organizationId, consultorioId, historiaClinicaId, ofertaId, membershipId, cuentaId),
				new OperatingActor(cuentaId, false, organizationId, consultorioId));
	}

	private long insertarSesion(
			long organizationId, long consultorioId, long historiaClinicaId,
			long ofertaId, long membershipId, long cuentaId) {

		jdbc.update("""
				INSERT INTO sesion (organization_id, consultorio_id, historia_clinica_id, oferta_id,
				                    profesional_membership_id, estado, iniciada_en,
				                    iniciada_por_cuenta_id, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, 'BORRADOR', UTC_TIMESTAMP(6), ?, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, consultorioId, historiaClinicaId, ofertaId, membershipId, cuentaId);
		return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}

	private long insertar(String insert, String select, Object[] insertArgs, Object[] selectArgs) {
		jdbc.update(insert, insertArgs);
		return jdbc.queryForObject(select, Long.class, selectArgs);
	}

	@Test
	@DisplayName("cerrar una atencion devenga su deuda, con el precio de la oferta congelado")
	void el_cierre_devenga_la_deuda() {
		// Prueba la cadena entera contra la base: `encounter` cierra, `billing` reacciona por el
		// SPI y la fila queda. Es lo unico que verifica que el cableado entre los dos modulos
		// funciona de verdad; un test con mocks solo probaria que se llamo al observador.
		Fixture fixture = crearFixture();

		cerrar(fixture, fixture.sesionA());

		var deuda = jdbc.queryForMap("""
				SELECT importe_original, saldo, moneda, estado, responsable, snapshot_precio
				  FROM obligacion WHERE sesion_id = ?
				""", fixture.sesionA());

		assertThat(deuda.get("importe_original")).hasToString("8500.00");
		assertThat(deuda.get("saldo")).hasToString("8500.00");
		assertThat(deuda.get("moneda")).isEqualTo("ARS");
		assertThat(deuda.get("estado")).isEqualTo("PENDIENTE");
		assertThat(deuda.get("responsable")).isEqualTo("PACIENTE");
		assertThat(deuda.get("snapshot_precio"))
				.as("el precio se congela al devengar: editar la oferta manana no puede reescribir esto")
				.hasToString("8500.00");
	}

	@Test
	@DisplayName("cerrar dos veces no devenga dos deudas")
	void el_devengo_es_idempotente() {
		// El cierre es idempotente por RN-M14-005, asi que el devengo tiene que serlo tambien. Sin
		// la consulta previa del observador, el segundo cierre chocaria contra el unique de V36 y
		// haria fallar un cierre que deberia no hacer nada.
		Fixture fixture = crearFixture();

		cerrar(fixture, fixture.sesionA());
		cerrar(fixture, fixture.sesionA());

		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM obligacion WHERE sesion_id = ?", Long.class, fixture.sesionA()))
				.isEqualTo(1);
	}

	@Test
	@DisplayName("una sesion cerrada con el paciente AUSENTE no devenga deuda")
	void el_ausente_no_devenga() {
		// M18 devenga "al concretar la prestacion", y una ausencia no es una prestacion. Cobrar un
		// no-show es una politica de centro que necesita su propia configuracion —cuanto, con
		// cuanto aviso— y no existe: cobrarlo por defecto seria decidirlo por el usuario.
		Fixture fixture = crearFixture();

		SesionView actual = sesionService.ver(fixture.actor(), fixture.consultorioId(), fixture.sesionA());
		sesionService.cerrar(fixture.actor(), fixture.consultorioId(), fixture.sesionA(),
				new CierreDeSesion(Asistencia.AUSENTE, null, null, null, null, null),
				actual.version());

		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM obligacion WHERE sesion_id = ?", Long.class, fixture.sesionA()))
				.isZero();
	}
}
