package com.akine.scheduling;

import com.akine.scheduling.application.OperatingActor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Datos sinteticos y ejecucion concurrente para los tests de integracion de agenda.
 *
 * <p>Vive aparte porque lo comparten {@code TurnoConcurrenteIT} (05.02) y
 * {@code TurnoCicloConcurrenteIT} (05.03), y dos copias de un fixture de once tablas divergen sola
 * la primera vez que una migracion agrega una columna obligatoria.
 *
 * <p><b>Todo lo que inserta es sintetico.</b> Ningun dato real de paciente entra jamas en un
 * fixture: es una regla dura del proyecto, no una recomendacion.
 *
 * <p>Cada escenario crea su PROPIA organizacion, sede, oferta y personas, con un sufijo aleatorio.
 * Es lo que permite correr los tests en cualquier orden y contar filas sin filtrar por hora —enlazar
 * un {@code Instant} como parametro de JDBC lo convierte con la zona de la sesion, que no es
 * necesariamente la conversion que hace Hibernate al guardar, y la consulta devolvia cero contra
 * filas que existian—.
 */
public final class AgendaFixtures {

	public static final String ZONA = "America/Argentina/Cordoba";

	private final JdbcTemplate jdbc;

	public AgendaFixtures(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** Un centro completo, listo para reservar los lunes de 09:00 a 13:00. */
	public record Fixture(
			long organizationId,
			long consultorioId,
			long ofertaId,
			long profesionalMembershipId,
			long personaA,
			long personaB,
			long personaC,
			OperatingActor actor) {
	}

	public Fixture crear(int capacidad) {
		String sufijo = UUID.randomUUID().toString().substring(0, 8);

		long organizationId = insertarOrganization(sufijo);
		long consultorioId = insertarConsultorio(organizationId, sufijo);

		long adminAccountId = insertarCuenta("admin-" + sufijo);
		insertarMembership(organizationId, consultorioId, adminAccountId, "ORG_ADMIN");

		return poblar(organizationId, consultorioId, adminAccountId, capacidad, false);
	}

	/**
	 * Completa un tenant que YA existe —p.ej. uno dado de alta por el camino HTTP real— con lo que
	 * hace falta para reservar: un profesional con disponibilidad los lunes de 09:00 a 13:00 (hora
	 * de la sede), una oferta que lo habilita y tres pacientes.
	 *
	 * <p>{@code requiereEspacio} en {@code true} hace que cada reserva tome un box de la sede: lo
	 * usan los tests de las sondas de impacto (paquete E-1), que necesitan turnos con espacio.
	 */
	public Fixture poblar(
			long organizationId, long consultorioId, long adminAccountId, int capacidad,
			boolean requiereEspacio) {

		String sufijo = UUID.randomUUID().toString().substring(0, 8);

		long profesionalAccountId = insertarCuenta("pro-" + sufijo);
		long profesionalMembershipId =
				insertarMembership(organizationId, consultorioId, profesionalAccountId, "PROFESIONAL");

		long servicioId = insertarServicio(sufijo);
		long ofertaId = insertarOferta(
				organizationId, consultorioId, servicioId, capacidad, requiereEspacio, sufijo);
		insertarHabilitacion(organizationId, consultorioId, ofertaId, profesionalMembershipId);
		// Lunes de 09:00 a 13:00. El slot de las 09:00 cae adentro con cualquier duracion sensata.
		insertarDisponibilidad(organizationId, consultorioId, profesionalMembershipId);

		return new Fixture(
				organizationId, consultorioId, ofertaId, profesionalMembershipId,
				insertarPaciente(organizationId, adminAccountId, "A" + sufijo),
				insertarPaciente(organizationId, adminAccountId, "B" + sufijo),
				insertarPaciente(organizationId, adminAccountId, "C" + sufijo),
				new OperatingActor(adminAccountId, false, organizationId, consultorioId));
	}

	// =================================================================================
	// Ejecucion concurrente
	// =================================================================================

	/**
	 * Corre las tareas de verdad en paralelo y devuelve el desenlace de cada una.
	 *
	 * <p>La {@link CyclicBarrier} es lo que hace real la carrera: sin ella, la primera tarea suele
	 * terminar antes de que la segunda arranque y el test pasaria sin haber probado nada. Con la
	 * barrera, las dos llegan al servicio dentro de la misma ventana de microsegundos.
	 *
	 * <p>Ninguna excepcion se propaga: se recogen todas y el test decide cuantos exitos y cuantos
	 * rechazos esperaba. Dejar que una explote perderia el desenlace de la otra, que es justamente
	 * la mitad de la informacion.
	 */
	public static <T> List<Desenlace<T>> enParalelo(List<Callable<T>> tareas) {
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
		} catch (Exception fallo) {
			throw new IllegalStateException("La ejecucion concurrente no pudo completarse", fallo);
		}
	}

	/** Recorre la cadena de causas: Spring envuelve las excepciones de servicio. */
	public static boolean causaEs(Throwable error, Class<? extends Throwable> tipo) {
		for (Throwable actual = error; actual != null; actual = actual.getCause()) {
			if (tipo.isInstance(actual)) {
				return true;
			}
		}
		return false;
	}

	public record Desenlace<T>(T valor, Exception error) {

		public boolean fallo() {
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
	// Inserciones
	// =================================================================================

	private long insertarOrganization(String sufijo) {
		String slug = "turno-it-" + sufijo;
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
		String email = "turno-it-" + sufijo + "@ejemplo.test";
		jdbc.update("""
				INSERT INTO cuenta (email, email_normalizado, nombre, apellido, estado,
				                    active, version, created_at, updated_at)
				VALUES (?, ?, 'Sintetico', 'DePrueba', 'ACTIVA', 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", email, email);
		return jdbc.queryForObject(
				"SELECT id FROM cuenta WHERE email_normalizado = ?", Long.class, email);
	}

	/** {@code valid_from} cinco anos atras: ver el javadoc del fixture de {@code DisponibilidadIT}. */
	private long insertarMembership(
			long organizationId, long consultorioId, long accountId, String rol) {

		jdbc.update("""
				INSERT INTO membership (organization_id, consultorio_id, account_id, role_code,
				                        is_founder, valid_from, estado, active, version,
				                        created_at, updated_at)
				VALUES (?, ?, ?, ?, 0, DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 5 YEAR), 'ACTIVA',
				        1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, consultorioId, accountId, rol);
		return jdbc.queryForObject("""
				SELECT id FROM membership
				 WHERE organization_id = ? AND consultorio_id = ? AND account_id = ?
				""", Long.class, organizationId, consultorioId, accountId);
	}

	private long insertarServicio(String sufijo) {
		String codigo = "TURNO-IT-" + sufijo.toUpperCase();
		jdbc.update("""
				INSERT INTO servicio (codigo, nombre, naturaleza, modalidad_default,
				                      requiere_caso_clinico_default, genera_registro_clinico_default,
				                      active, version, created_at, updated_at)
				VALUES (?, ?, 'CLINICO', 'INDIVIDUAL', 0, 0, 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", codigo, "Servicio Sintetico " + sufijo);
		return jdbc.queryForObject("SELECT id FROM servicio WHERE codigo = ?", Long.class, codigo);
	}

	/**
	 * {@code requiere_espacio = 0} en los tests de concurrencia, a proposito: lo que miden es la
	 * exclusion sobre el profesional y el cupo. Meter un box agregaria una segunda fuente de
	 * rechazo y el test no podria decir cual de las dos actuo.
	 */
	private long insertarOferta(
			long organizationId, long consultorioId, long servicioId, int capacidad,
			boolean requiereEspacio, String sufijo) {

		String nombre = "Oferta Sintetica " + sufijo;
		jdbc.update("""
				INSERT INTO oferta_servicio_consultorio
				       (organization_id, consultorio_id, servicio_id, nombre_comercial, modalidad,
				        duracion_minutos, capacidad, admite_obra_social, requiere_caso_clinico,
				        genera_registro_clinico, requiere_profesional, requiere_espacio,
				        vigencia_desde, active, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, 60, ?, 0, 0, 0, 1, ?,
				        DATE_SUB(CURDATE(), INTERVAL 5 YEAR), 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, consultorioId, servicioId, nombre,
				capacidad > 1 ? "GRUPAL" : "INDIVIDUAL", capacidad, requiereEspacio ? 1 : 0);
		return jdbc.queryForObject("""
				SELECT id FROM oferta_servicio_consultorio
				 WHERE organization_id = ? AND nombre_comercial = ?
				""", Long.class, organizationId, nombre);
	}

	private void insertarHabilitacion(
			long organizationId, long consultorioId, long ofertaId, long membershipId) {

		jdbc.update("""
				INSERT INTO oferta_profesional_habilitado
				       (organization_id, consultorio_id, oferta_id, membership_id, valid_from,
				        active, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 5 YEAR), 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, consultorioId, ofertaId, membershipId);
	}

	private void insertarDisponibilidad(
			long organizationId, long consultorioId, long membershipId) {

		jdbc.update("""
				INSERT INTO profesional_disponibilidad
				       (organization_id, consultorio_id, membership_id, dia_semana,
				        hora_desde, hora_hasta, vigencia_desde, active, version,
				        created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, ?, DATE_SUB(CURDATE(), INTERVAL 5 YEAR), 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, consultorioId, membershipId,
				DayOfWeek.MONDAY.getValue(), LocalTime.of(9, 0), LocalTime.of(13, 0));
	}

	/**
	 * Persona MAS perfil de paciente: {@code TurnoService} exige el perfil vigente y no lo crea.
	 *
	 * <p>RF-M07-010 sostenido por la estructura: son dos tablas y no hay ninguna columna
	 * {@code es_paciente}. Un fixture que inserte solo la persona hace fallar la reserva con
	 * {@code persona-sin-perfil-paciente}, que es el comportamiento correcto.
	 */
	private long insertarPaciente(long organizationId, long activadoPor, String sufijo) {
		String apellido = "Paciente" + sufijo;
		jdbc.update("""
				INSERT INTO persona (organization_id, apellido, nombre, apellido_clave, nombre_clave,
				                     active, version, created_at, updated_at)
				VALUES (?, ?, 'Sintetico', ?, 'SINTETICO', 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, apellido, apellido.toUpperCase());
		long personaId = jdbc.queryForObject("""
				SELECT id FROM persona WHERE organization_id = ? AND apellido = ?
				""", Long.class, organizationId, apellido);

		jdbc.update("""
				INSERT INTO perfil_paciente (organization_id, persona_id, activado_en, activado_por,
				                             active, version, created_at, updated_at)
				VALUES (?, ?, UTC_TIMESTAMP(6), ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, personaId, activadoPor);
		return personaId;
	}
}
