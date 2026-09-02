package com.akine.contracting;

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
 * Datos sinteticos y ejecutor concurrente para los tests de integracion de M16.
 *
 * <p>Se inserta con JDBC crudo y no por los servicios: montar el escenario a traves de la API
 * exigiria tokens, contexto y permisos, y lo que estos tests miden es el comportamiento de InnoDB,
 * no el del camino HTTP.
 *
 * <p>Datos <b>exclusivamente sinteticos</b> (AGENT.md §10) y con sufijo aleatorio por corrida, para
 * que dos ejecuciones no choquen entre si por los uniques.
 *
 * <p>{@link #enParalelo} es el mismo ejecutor que usan {@code AgendaFixtures} y
 * {@code CierreConcurrenteIT}: barrera de salida comun para que los hilos empiecen juntos, y cada
 * desenlace se devuelve con su excepcion en vez de propagarse, para poder contar cuantos ganaron.
 */
public final class ConvenioFixtures {

	public static final String ZONA = "America/Argentina/Cordoba";

	private final JdbcTemplate jdbc;

	public ConvenioFixtures(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** Organizacion, sede, financiador, plan y practica, todo listo para firmar convenios. */
	public Escenario crear() {
		String sufijo = UUID.randomUUID().toString().substring(0, 12);

		long organizationId = insertarOrganizacion(sufijo);
		long consultorioId = insertarConsultorio(organizationId, "Sede " + sufijo);
		long financiadorId = insertarFinanciador(organizationId, sufijo);
		long planId = insertarPlan(organizationId, financiadorId, sufijo);
		long especialidadId = insertarEspecialidad(organizationId, sufijo);
		long practicaId = insertarPractica(organizationId, especialidadId, sufijo);

		// CONSULTORIO_ADMIN: es uno de los dos roles a los que 03.03 le dio asignacion base de
		// convenio:manage. El permiso se evalua contra el evaluador REAL, no contra un doble, para
		// que este test tambien falle si esa asignacion se quita por descuido.
		long accountId = insertarCuenta(sufijo);
		insertarMembership(organizationId, consultorioId, accountId, "CONSULTORIO_ADMIN");

		return new Escenario(
				organizationId, consultorioId, financiadorId, planId, practicaId, accountId);
	}

	/** Otra practica de la MISMA organizacion, para el caso "practicas distintas conviven". */
	public long otraPractica(Escenario escenario) {
		String sufijo = UUID.randomUUID().toString().substring(0, 12);
		long especialidadId = insertarEspecialidad(escenario.organizationId(), sufijo);
		return insertarPractica(escenario.organizationId(), especialidadId, sufijo);
	}

	public record Escenario(
			long organizationId,
			long consultorioId,
			long financiadorId,
			long planId,
			long practicaId,
			long accountId) {
	}

	// =================================================================================
	// Ejecucion concurrente
	// =================================================================================

	/** Corre las tareas a la vez, con barrera de salida, y devuelve el desenlace de cada una. */
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

	private long insertarOrganizacion(String sufijo) {
		String slug = "convenio-it-" + sufijo;
		jdbc.update("""
				INSERT INTO organization (name, slug, timezone, active, version,
				                          created_at, updated_at)
				VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "Centro Sintetico " + sufijo, slug, ZONA);
		return jdbc.queryForObject("SELECT id FROM organization WHERE slug = ?", Long.class, slug);
	}

	private long insertarConsultorio(long organizationId, String nombre) {
		jdbc.update("""
				INSERT INTO consultorio (organization_id, name, timezone, active, version,
				                         created_at, updated_at)
				VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, nombre, ZONA);
		return jdbc.queryForObject(
				"SELECT id FROM consultorio WHERE organization_id = ? AND name = ?",
				Long.class, organizationId, nombre);
	}

	private long insertarFinanciador(long organizationId, String sufijo) {
		jdbc.update("""
				INSERT INTO financiador (organization_id, codigo, nombre, tipo, active, version,
				                         created_at, updated_at)
				VALUES (?, ?, ?, 'PREPAGA', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, "FIN-" + sufijo, "Financiador " + sufijo);
		return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}

	private long insertarPlan(long organizationId, long financiadorId, String sufijo) {
		jdbc.update("""
				INSERT INTO plan_cobertura (organization_id, financiador_id, codigo, nombre,
				                            vigencia_desde, requiere_autorizacion,
				                            requiere_credencial, active, version,
				                            created_at, updated_at)
				VALUES (?, ?, ?, ?, '2020-01-01', 0, 1, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, financiadorId, "PLAN-" + sufijo, "Plan " + sufijo);
		return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}

	private long insertarEspecialidad(long organizationId, String sufijo) {
		jdbc.update("""
				INSERT INTO especialidad (organization_id, codigo, name, valid_from, active, version,
				                          created_at, updated_at)
				VALUES (?, ?, ?, '2020-01-01 00:00:00.000000', 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, "ESP-" + sufijo, "Especialidad " + sufijo);
		return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}

	private long insertarCuenta(String sufijo) {
		String email = "convenio-it-" + sufijo + "@ejemplo.test";
		jdbc.update("""
				INSERT INTO cuenta (email, email_normalizado, nombre, apellido, estado,
				                    active, version, created_at, updated_at)
				VALUES (?, ?, 'Sintetico', 'DePrueba', 'ACTIVA', 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", email, email);
		return jdbc.queryForObject(
				"SELECT id FROM cuenta WHERE email_normalizado = ?", Long.class, email);
	}

	/** {@code valid_from} cinco anos atras, mismo criterio que el fixture de la agenda. */
	private void insertarMembership(
			long organizationId, long consultorioId, long accountId, String rol) {

		jdbc.update("""
				INSERT INTO membership (organization_id, consultorio_id, account_id, role_code,
				                        is_founder, valid_from, estado, active, version,
				                        created_at, updated_at)
				VALUES (?, ?, ?, ?, 0, DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 5 YEAR), 'ACTIVA',
				        1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, consultorioId, accountId, rol);
	}

	private long insertarPractica(long organizationId, long especialidadId, String sufijo) {
		jdbc.update("""
				INSERT INTO practica (organization_id, especialidad_id, codigo, name, valid_from,
				                      active, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, '2020-01-01 00:00:00.000000', 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, especialidadId, "PRA-" + sufijo, "Practica " + sufijo);
		return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}
}
