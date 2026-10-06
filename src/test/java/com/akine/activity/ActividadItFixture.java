package com.akine.activity;

import com.akine.activity.application.InscribirCommand;
import com.akine.activity.application.InscripcionService;
import com.akine.activity.application.OperatingActor;
import com.akine.activity.application.ResultadoDeInscripcion;
import org.springframework.jdbc.core.JdbcTemplate;

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
 * Lo que comparten los ITs de inscripciones de 08.02 (escenarios 43, 44 y 45 de
 * {@code docs/tests-diferidos.md}): un tenant con su sede, una oferta grupal, un padron de personas
 * sinteticas y clases programadas listas para recibir inscripciones.
 *
 * <p><b>El tenant y la clase se insertan por JDBC</b>, igual que {@code CobroConcurrenteIT}: llegar
 * hasta una clase por {@code ClaseService#programar} exigiria profesional, disponibilidad y la
 * exclusion de agenda, que tienen sus propios tests y no son lo que estos miden. Las inscripciones,
 * en cambio, pasan <b>siempre</b> por {@link InscripcionService}: el {@code UPDATE} condicional del
 * cupo, el orden liberar→leer-cola y el {@code clearAutomatically} son justamente lo que esta en duda.
 *
 * <p><b>Las fechas son relativas al dia de corrida</b> —{@code UTC_TIMESTAMP(6) + 7 dias}— y se
 * calculan en el motor, no en Java: una clase con fecha fija vence y la inscripcion empieza a
 * contestar "ya empezo"; y enlazar un {@code Instant} por JDBC lo convierte con la zona de la
 * sesion, que no es necesariamente la que usa Hibernate (ver {@code AgendaFixtures}).
 *
 * <p>Todo lo que inserta es sintetico, y las personas <b>no tienen correo</b>: el aviso de
 * {@code CUPO_LIBERADO} se saltea con un log y el outbox no entra en la medicion.
 */
public final class ActividadItFixture {

	public static final String ZONA = "America/Argentina/Cordoba";

	private final JdbcTemplate jdbc;
	private final InscripcionService inscripciones;

	public ActividadItFixture(JdbcTemplate jdbc, InscripcionService inscripciones) {
		this.jdbc = jdbc;
		this.inscripciones = inscripciones;
	}

	/** Un tenant con un ORG_ADMIN —que tiene {@code inscripcion:manage}— y su padron. */
	public record Tenant(
			long organizationId,
			long consultorioId,
			long ofertaId,
			List<Long> personas,
			OperatingActor actor) {

		public long persona(int indice) {
			return personas.get(indice);
		}
	}

	/**
	 * @param capacidadOferta la capacidad de la oferta: entra en la efectiva como
	 *                        {@code min(clase, oferta)}
	 * @param cantidadPersonas cuantas personas sinteticas cargar en el padron
	 */
	public Tenant crearTenant(int capacidadOferta, int cantidadPersonas) {
		String sufijo = UUID.randomUUID().toString().substring(0, 8);

		long organizationId = insertar("""
				INSERT INTO organization (name, slug, timezone, active, version, created_at, updated_at)
				VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "SELECT id FROM organization WHERE slug = ?",
				new Object[]{"Centro " + sufijo, "clase-it-" + sufijo, ZONA},
				new Object[]{"clase-it-" + sufijo});

		long consultorioId = insertar("""
				INSERT INTO consultorio (organization_id, name, timezone, active, version, created_at, updated_at)
				VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "SELECT id FROM consultorio WHERE organization_id = ? AND name = ?",
				new Object[]{organizationId, "Sede " + sufijo, ZONA},
				new Object[]{organizationId, "Sede " + sufijo});

		String email = "clase-it-" + sufijo + "@ejemplo.test";
		long cuentaId = insertar("""
				INSERT INTO cuenta (email, email_normalizado, nombre, apellido, estado, active,
				                    version, created_at, updated_at)
				VALUES (?, ?, 'Sintetico', 'DePrueba', 'ACTIVA', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "SELECT id FROM cuenta WHERE email_normalizado = ?",
				new Object[]{email, email}, new Object[]{email});

		jdbc.update("""
				INSERT INTO membership (organization_id, consultorio_id, account_id, role_code,
				                        is_founder, valid_from, estado, active, version,
				                        created_at, updated_at)
				VALUES (?, ?, ?, 'ORG_ADMIN', 0, DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 5 YEAR),
				        'ACTIVA', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, consultorioId, cuentaId);

		long servicioId = insertar("""
				INSERT INTO servicio (codigo, nombre, naturaleza, modalidad_default,
				                      requiere_caso_clinico_default, genera_registro_clinico_default,
				                      active, version, created_at, updated_at)
				VALUES (?, ?, 'CLINICO', 'GRUPAL', 0, 0, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "SELECT id FROM servicio WHERE codigo = ?",
				new Object[]{"CLASE-" + sufijo.toUpperCase(), "Servicio " + sufijo},
				new Object[]{"CLASE-" + sufijo.toUpperCase()});

		// requiere_espacio = 0: sin box, la capacidad efectiva es min(clase, oferta) y nada mas. Un
		// espacio agregaria una tercera fuente del limite y el test no podria decir cual actuo.
		long ofertaId = insertar("""
				INSERT INTO oferta_servicio_consultorio
				       (organization_id, consultorio_id, servicio_id, nombre_comercial, modalidad,
				        duracion_minutos, capacidad, admite_obra_social, requiere_caso_clinico,
				        genera_registro_clinico, requiere_profesional, requiere_espacio,
				        vigencia_desde, active, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, 'GRUPAL', 60, ?, 0, 0, 0, 0, 0,
				        DATE_SUB(CURDATE(), INTERVAL 5 YEAR), 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", """
				SELECT id FROM oferta_servicio_consultorio
				 WHERE organization_id = ? AND nombre_comercial = ?
				""", new Object[]{organizationId, consultorioId, servicioId, "Oferta " + sufijo, capacidadOferta},
				new Object[]{organizationId, "Oferta " + sufijo});

		List<Long> personas = new ArrayList<>();
		for (int i = 0; i < cantidadPersonas; i++) {
			String apellido = "Persona" + i + "x" + sufijo;
			personas.add(insertar("""
					INSERT INTO persona (organization_id, apellido, nombre, apellido_clave, nombre_clave,
					                     active, version, created_at, updated_at)
					VALUES (?, ?, 'Sintetica', ?, 'SINTETICA', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
					""", "SELECT id FROM persona WHERE organization_id = ? AND apellido = ?",
					new Object[]{organizationId, apellido, apellido.toUpperCase()},
					new Object[]{organizationId, apellido}));
		}

		return new Tenant(organizationId, consultorioId, ofertaId, List.copyOf(personas),
				new OperatingActor(cuentaId, false, organizationId, consultorioId));
	}

	/**
	 * Una clase {@code PROGRAMADA} dentro de una semana, con la capacidad propia pedida.
	 *
	 * @param diasAdelante para que dos clases del mismo tenant no compartan horario; el motor no
	 *                     lo exige —la insercion no pasa por la exclusion de agenda— pero una
	 *                     grilla imposible confunde a quien lea los datos de una corrida
	 */
	public long programarClase(Tenant tenant, int capacidad, int diasAdelante) {
		jdbc.update("""
				INSERT INTO clase_programada (organization_id, consultorio_id, oferta_id, titulo,
				                              inicio, fin, capacidad, estado,
				                              programado_por_cuenta_id, programado_en, version)
				VALUES (?, ?, ?, 'Clase sintetica',
				        DATE_ADD(UTC_TIMESTAMP(6), INTERVAL ? DAY),
				        DATE_ADD(DATE_ADD(UTC_TIMESTAMP(6), INTERVAL ? DAY), INTERVAL 1 HOUR),
				        ?, 'PROGRAMADA', ?, UTC_TIMESTAMP(6), 0)
				""", tenant.organizationId(), tenant.consultorioId(), tenant.ofertaId(),
				diasAdelante, diasAdelante, capacidad, tenant.actor().accountId());
		return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}

	/** Inscribe por el servicio real, sin clave de idempotencia. */
	public ResultadoDeInscripcion inscribir(
			Tenant tenant, long claseId, long personaId, boolean aceptaListaEspera) {

		return inscripciones.inscribir(tenant.actor(), tenant.consultorioId(), claseId,
				new InscribirCommand(personaId, aceptaListaEspera, null));
	}

	// =================================================================================
	// Lecturas directas al motor: lo que se compara contra lo que dijo el servicio
	// =================================================================================

	public int cupoOcupado(long claseId) {
		return jdbc.queryForObject(
				"SELECT cupo_ocupado FROM clase_programada WHERE id = ?", Integer.class, claseId);
	}

	public String estadoDe(long inscripcionId) {
		return jdbc.queryForObject(
				"SELECT estado FROM inscripcion_clase WHERE id = ?", String.class, inscripcionId);
	}

	public long inscripcionDe(long claseId, long personaId) {
		return jdbc.queryForObject("""
				SELECT id FROM inscripcion_clase
				 WHERE clase_id = ? AND persona_id = ? AND deleted_at IS NULL
				""", Long.class, claseId, personaId);
	}

	public int contarPorEstado(long claseId, String estado) {
		return jdbc.queryForObject("""
				SELECT COUNT(*) FROM inscripcion_clase WHERE clase_id = ? AND estado = ?
				""", Integer.class, claseId, estado);
	}

	public List<Integer> posicionesEnEspera(long claseId) {
		return jdbc.queryForList("""
				SELECT posicion_espera FROM inscripcion_clase
				 WHERE clase_id = ? AND estado = 'LISTA_ESPERA'
				 ORDER BY posicion_espera
				""", Integer.class, claseId);
	}

	/** Personas con mas de una inscripcion viva en la misma clase. Tiene que ser cero siempre. */
	public int personasDuplicadas(long claseId) {
		return jdbc.queryForObject("""
				SELECT COUNT(*) FROM (
				    SELECT persona_id FROM inscripcion_clase
				     WHERE clase_id = ? AND deleted_at IS NULL
				     GROUP BY persona_id HAVING COUNT(*) > 1) d
				""", Integer.class, claseId);
	}

	// =================================================================================
	// Ejecucion concurrente
	// =================================================================================

	/**
	 * Corre las tareas de verdad en paralelo y devuelve el desenlace de cada una.
	 *
	 * <p>La {@link CyclicBarrier} es lo que hace real la carrera: sin ella la primera tarea suele
	 * terminar antes de que la siguiente arranque, y el test pasaria sin haber probado nada.
	 *
	 * <p><b>Nunca mas tareas que conexiones</b>: el pool de Hikari es de diez por defecto, y una
	 * tarea que espera conexion mientras otra espera un lock convierte la carrera en una cola.
	 */
	public static <T> List<Desenlace<T>> enParalelo(List<Callable<T>> tareas) {
		if (tareas.size() > 8) {
			throw new IllegalArgumentException("Mas de 8 tareas no caben en el pool de conexiones");
		}
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
				desenlaces.add(futuro.get(60, TimeUnit.SECONDS));
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

	private long insertar(String insert, String select, Object[] insertArgs, Object[] selectArgs) {
		jdbc.update(insert, insertArgs);
		return jdbc.queryForObject(select, Long.class, selectArgs);
	}
}
