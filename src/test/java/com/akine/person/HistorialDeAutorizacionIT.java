package com.akine.person;

import com.akine.TestcontainersConfiguration;
import com.akine.person.application.AutorizacionAltaCommand;
import com.akine.person.application.AutorizacionEdicionCommand;
import com.akine.person.application.AutorizacionService;
import com.akine.person.application.AutorizacionView;
import com.akine.person.application.ConsumoDeAutorizacionService;
import com.akine.person.application.DocumentoEstadoFiltro;
import com.akine.person.application.EventoDeAutorizacionView;
import com.akine.person.application.HistorialDeAutorizacionView;
import com.akine.person.application.OperatingActor;
import com.akine.person.application.OrdenAltaCommand;
import com.akine.person.application.OrdenMedicaService;
import com.akine.person.application.OrdenView;
import com.akine.person.application.ResolucionDeAutorizacionCommand;
import com.akine.person.application.ReversionCommand;
import com.akine.person.domain.AccionSobreAutorizacion;
import com.akine.person.domain.EstadoAutorizacion;
import com.akine.person.domain.exception.AutorizacionNotAccessibleException;
import com.akine.person.domain.exception.AutorizacionSuperpuestaException;
import com.akine.person.spi.ConsumoPorSesion;
import com.akine.person.spi.ResultadoDeConsumo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AKINE B-4 contra MySQL real (DP-23): cada mutacion de la autorizacion deja su evento en la
 * misma transaccion, un rollback no deja evento, el consumo concurrente no duplica eventos, el
 * historial no cruza tenants y la orden deriva su situacion de las autorizaciones que la usan.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class HistorialDeAutorizacionIT {

	private static final String ZONA = "America/Argentina/Cordoba";

	@Autowired private AutorizacionService autorizaciones;
	@Autowired private ConsumoDeAutorizacionService consumo;
	@Autowired private OrdenMedicaService ordenes;
	@Autowired private TransactionTemplate transacciones;
	@Autowired private JdbcTemplate jdbc;

	@Test
	@DisplayName("cada mutacion deja su evento, en orden, con estado anterior y nuevo")
	void cada_mutacion_deja_su_evento() {
		Fixture f = crearFixture();
		AutorizacionView alta = autorizaciones.registrar(f.admin(), f.personaId(), alta(f,
				EstadoAutorizacion.PENDIENTE, "H-1", 10));
		long id = alta.id();

		AutorizacionView observada = autorizaciones.resolver(f.admin(), f.personaId(), id,
				new ResolucionDeAutorizacionCommand(AccionSobreAutorizacion.OBSERVAR,
						"Falta la firma", null, null, null, alta.version()));
		AutorizacionView aprobada = autorizaciones.resolver(f.admin(), f.personaId(), id,
				new ResolucionDeAutorizacionCommand(AccionSobreAutorizacion.APROBAR, null, 6,
						null, null, observada.version()));
		autorizaciones.editar(f.admin(), f.personaId(), id, new AutorizacionEdicionCommand(
				null, null, null, null, hoy().plusDays(60), null, aprobada.version()));
		ResultadoDeConsumo consumido = consumir(f, 970_000L + aleatorio()).get(0);
		consumo.revertir(f.admin(), id,
				new ReversionCommand(consumido.movimientoId(), "Sesion cargada a otra persona"));
		autorizaciones.darDeBaja(f.admin(), f.personaId(), id, "Cargada por duplicado");

		List<EventoDeAutorizacionView> eventos = todos(f, id);
		assertThat(eventos).extracting(EventoDeAutorizacionView::tipo).containsExactly(
				"ALTA", "OBSERVACION", "APROBACION", "MODIFICACION", "CONSUMO",
				"REVERSION_DE_CONSUMO", "ANULACION");
		assertThat(eventos).extracting(EventoDeAutorizacionView::estadoAnterior).containsExactly(
				null, "PENDIENTE", "OBSERVADA", "APROBADA", "APROBADA", "APROBADA", "APROBADA");
		assertThat(eventos).extracting(EventoDeAutorizacionView::estadoNuevo).containsExactly(
				"PENDIENTE", "OBSERVADA", "APROBADA", "APROBADA", "APROBADA", "APROBADA",
				"APROBADA");
		assertThat(eventos.get(1).motivo()).isEqualTo("Falta la firma");
		assertThat(eventos.get(2).detalle()).isEqualTo("cantidadAutorizada: 10 -> 6");
		assertThat(eventos.get(3).detalle()).startsWith("vigenciaHasta: ");
		assertThat(eventos.get(4).movimientoId()).isEqualTo(consumido.movimientoId());
		assertThat(eventos.get(4).cantidad()).isEqualTo(1);
		assertThat(eventos.get(5).motivo()).isEqualTo("Sesion cargada a otra persona");
		assertThat(eventos.get(6).activa()).isFalse();
		assertThat(eventos.get(6).motivo()).isEqualTo("Cargada por duplicado");
		assertThat(eventos).allMatch(evento -> evento.actorCuentaId() != null);

		// Paginado: 7 eventos de a 3 son tres paginas, sin perder ni repetir ninguno.
		HistorialDeAutorizacionView ultima =
				autorizaciones.historial(f.admin(), f.personaId(), id, 2, 3, hoy());
		assertThat(ultima.total()).isEqualTo(7);
		assertThat(ultima.contenido()).extracting(EventoDeAutorizacionView::tipo)
				.containsExactly("ANULACION");
		assertThat(ultima.vencida()).isFalse();
		assertThat(ultima.activa()).isFalse();
	}

	@Test
	@DisplayName("si la transaccion de la mutacion hace rollback, el evento tampoco queda")
	void rollback_no_deja_evento() {
		Fixture f = crearFixture();
		AutorizacionView alta = autorizaciones.registrar(f.admin(), f.personaId(), alta(f,
				EstadoAutorizacion.PENDIENTE, "R-1", 10));

		assertThatThrownBy(() -> transacciones.executeWithoutResult(estado -> {
			autorizaciones.resolver(f.admin(), f.personaId(), alta.id(),
					new ResolucionDeAutorizacionCommand(AccionSobreAutorizacion.APROBAR, null,
							null, null, null, alta.version()));
			assertThat(eventosEnBase(alta.id()))
					.as("dentro de la transaccion el evento ya esta escrito")
					.isEqualTo(2);
			throw new IllegalStateException("falla posterior a la mutacion");
		})).isInstanceOf(IllegalStateException.class);

		assertThat(eventosEnBase(alta.id())).as("solo el ALTA sobrevive").isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT estado FROM autorizacion WHERE id = ?",
				String.class, alta.id())).isEqualTo("PENDIENTE");
	}

	@Test
	@DisplayName("una mutacion rechazada por un invariante no deja evento")
	void invariante_rechazado_no_deja_evento() {
		Fixture f = crearFixture();
		autorizaciones.registrar(f.admin(), f.personaId(),
				alta(f, EstadoAutorizacion.APROBADA, "S-1", 10));
		AutorizacionView pendiente = autorizaciones.registrar(f.admin(), f.personaId(),
				alta(f, EstadoAutorizacion.PENDIENTE, "S-2", 10));

		assertThatThrownBy(() -> autorizaciones.resolver(f.admin(), f.personaId(), pendiente.id(),
				new ResolucionDeAutorizacionCommand(AccionSobreAutorizacion.APROBAR, null, null,
						null, null, pendiente.version())))
				.isInstanceOf(AutorizacionSuperpuestaException.class);

		assertThat(eventosEnBase(pendiente.id())).isEqualTo(1);
	}

	@Test
	@DisplayName("cinco consumos concurrentes sobre dos unidades: dos eventos, uno por movimiento")
	void consumo_concurrente_no_duplica_eventos() {
		Fixture f = crearFixture();
		long id = autorizaciones.registrar(f.admin(), f.personaId(),
				alta(f, EstadoAutorizacion.APROBADA, "C-1", 2)).id();

		List<Callable<List<ResultadoDeConsumo>>> rafaga = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			long sesion = 980_000L + i + aleatorio() * 10L;
			rafaga.add(() -> consumir(f, sesion));
		}
		List<Exception> errores = enParalelo(rafaga);

		assertThat(errores).as("%s", errores).isEmpty();
		Integer consumos = jdbc.queryForObject("""
				SELECT COUNT(*) FROM autorizacion_movimiento
				 WHERE autorizacion_id = ? AND tipo = 'CONSUMO'
				""", Integer.class, id);
		Integer eventosDeConsumo = jdbc.queryForObject("""
				SELECT COUNT(*) FROM autorizacion_evento
				 WHERE autorizacion_id = ? AND tipo = 'CONSUMO'
				""", Integer.class, id);
		Integer movimientosDistintos = jdbc.queryForObject("""
				SELECT COUNT(DISTINCT movimiento_id) FROM autorizacion_evento
				 WHERE autorizacion_id = ? AND tipo = 'CONSUMO'
				""", Integer.class, id);
		assertThat(consumos).isEqualTo(2);
		assertThat(eventosDeConsumo).isEqualTo(2);
		assertThat(movimientosDistintos).isEqualTo(2);
	}

	@Test
	@DisplayName("el historial de una autorizacion de otro tenant responde 404 y no se mezcla")
	void aislamiento_de_tenant() {
		Fixture a = crearFixture();
		Fixture b = crearFixture();
		long id = autorizaciones.registrar(a.admin(), a.personaId(),
				alta(a, EstadoAutorizacion.APROBADA, "T-1", 10)).id();

		assertThatThrownBy(() ->
				autorizaciones.historial(b.admin(), b.personaId(), id, 0, 50, hoy()))
				.isInstanceOf(AutorizacionNotAccessibleException.class);
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM autorizacion_evento WHERE autorizacion_id = ? "
						+ "AND organization_id <> ?", Integer.class, id, a.organizationId()))
				.isZero();
	}

	@Test
	@DisplayName("la orden deriva su situacion: en tramite, autorizada, en curso, cumplida")
	void situacion_de_la_orden() {
		Fixture f = crearFixture();
		long ordenId = ordenes.registrar(f.admin(), f.personaId(), new OrdenAltaCommand(
				f.coberturaId(), "OM-" + aleatorio(), "Dra. Sintetica", "MP 1", hoy().minusDays(40),
				null, 1, hoy().minusDays(40), hoy().plusDays(90), null)).id();
		assertThat(situacion(f, ordenId)).isEqualTo("SIN_AUTORIZACION");

		AutorizacionView pendiente = autorizaciones.registrar(f.admin(), f.personaId(),
				new AutorizacionAltaCommand(f.coberturaId(), f.practicaId(), ordenId, "O-1",
						EstadoAutorizacion.PENDIENTE, 5, hoy().minusDays(30), hoy().plusDays(60),
						null));
		assertThat(situacion(f, ordenId)).isEqualTo("EN_TRAMITE");

		autorizaciones.resolver(f.admin(), f.personaId(), pendiente.id(),
				new ResolucionDeAutorizacionCommand(AccionSobreAutorizacion.APROBAR, null, null,
						null, null, pendiente.version()));
		assertThat(situacion(f, ordenId)).isEqualTo("AUTORIZADA");

		consumir(f, 990_000L + aleatorio());
		OrdenView cumplida = ordenes.listar(f.admin(), f.personaId(),
				DocumentoEstadoFiltro.TODAS, hoy()).get(0);
		assertThat(cumplida.situacion()).as("una sesion prescripta, una consumida")
				.isEqualTo("CUMPLIDA");
		assertThat(cumplida.sesionesConsumidas()).isEqualTo(1);
	}

	// =================================================================================
	// Operaciones
	// =================================================================================

	private String situacion(Fixture f, long ordenId) {
		return ordenes.listar(f.admin(), f.personaId(), DocumentoEstadoFiltro.TODAS, hoy())
				.stream()
				.filter(orden -> orden.id() == ordenId)
				.findFirst()
				.orElseThrow()
				.situacion();
	}

	private List<EventoDeAutorizacionView> todos(Fixture f, long id) {
		return autorizaciones.historial(f.admin(), f.personaId(), id, 0, 100, hoy()).contenido();
	}

	private int eventosEnBase(long autorizacionId) {
		return jdbc.queryForObject(
				"SELECT COUNT(*) FROM autorizacion_evento WHERE autorizacion_id = ?",
				Integer.class, autorizacionId);
	}

	private List<ResultadoDeConsumo> consumir(Fixture f, long sesionId) {
		return consumo.consumirPorSesion(new ConsumoPorSesion(
				f.organizationId(), f.personaId(), f.consultorioId(), sesionId, hoy(), 1,
				f.adminCuentaId(), Set.of(f.practicaId())));
	}

	private static AutorizacionAltaCommand alta(
			Fixture f, EstadoAutorizacion estado, String numero, int cantidad) {
		return new AutorizacionAltaCommand(f.coberturaId(), f.practicaId(), null, numero, estado,
				cantidad, hoy().minusDays(30), hoy().plusDays(120), null);
	}

	private static LocalDate hoy() {
		return LocalDate.now(ZoneId.of(ZONA));
	}

	private static int aleatorio() {
		return Math.abs(UUID.randomUUID().hashCode() % 100_000);
	}

	private static <T> List<Exception> enParalelo(List<Callable<T>> tareas) {
		CyclicBarrier salida = new CyclicBarrier(tareas.size());
		try (ExecutorService pool = Executors.newFixedThreadPool(tareas.size())) {
			List<Future<Exception>> futuros = new ArrayList<>();
			for (Callable<T> tarea : tareas) {
				futuros.add(pool.submit(() -> {
					salida.await(10, TimeUnit.SECONDS);
					try {
						tarea.call();
						return null;
					} catch (Exception error) {
						return error;
					}
				}));
			}
			List<Exception> errores = new ArrayList<>();
			for (Future<Exception> futuro : futuros) {
				Exception error = futuro.get(60, TimeUnit.SECONDS);
				if (error != null) {
					errores.add(error);
				}
			}
			return errores;
		} catch (Exception error) {
			throw new IllegalStateException("La ejecucion concurrente no pudo completarse", error);
		}
	}

	// =================================================================================
	// Fixture — todo sintetico
	// =================================================================================

	private record Fixture(
			long organizationId,
			long consultorioId,
			long adminCuentaId,
			long personaId,
			long coberturaId,
			long practicaId) {

		OperatingActor admin() {
			return new OperatingActor(adminCuentaId, false, organizationId, consultorioId);
		}
	}

	private Fixture crearFixture() {
		String s = UUID.randomUUID().toString().substring(0, 10);

		long org = insertar("""
				INSERT INTO organization (name, slug, timezone, active, version, created_at, updated_at)
				VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "Centro " + s, "b4-it-" + s, ZONA);
		long sede = insertar("""
				INSERT INTO consultorio (organization_id, name, timezone, active, version, created_at, updated_at)
				VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", org, "Sede " + s, ZONA);

		String email = "b4-adm-" + s + "@ejemplo.test";
		long admin = insertar("""
				INSERT INTO cuenta (email, email_normalizado, nombre, apellido, estado, active,
				                    version, created_at, updated_at)
				VALUES (?, ?, 'Sintetico', 'DePrueba', 'ACTIVA', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", email, email);
		// paciente:manage para registrar, resolver, editar, revertir y dar de baja.
		insertar("""
				INSERT INTO membership (organization_id, consultorio_id, account_id, role_code,
				                        is_founder, valid_from, estado, active, version,
				                        created_at, updated_at)
				VALUES (?, ?, ?, 'ADMINISTRATIVO', 0, DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 5 YEAR),
				        'ACTIVA', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", org, sede, admin);

		String apellido = "Paciente" + s;
		long persona = insertar("""
				INSERT INTO persona (organization_id, apellido, nombre, apellido_clave, nombre_clave,
				                     active, version, created_at, updated_at)
				VALUES (?, ?, 'Sintetico', ?, 'SINTETICO', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", org, apellido, apellido.toUpperCase());
		jdbc.update("""
				INSERT INTO perfil_paciente (organization_id, persona_id, activado_en, activado_por,
				                             active, version, created_at, updated_at)
				VALUES (?, ?, UTC_TIMESTAMP(6), ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", org, persona, admin);

		long financiador = insertar("""
				INSERT INTO financiador (organization_id, codigo, nombre, tipo, active, version,
				                         created_at, updated_at)
				VALUES (?, ?, ?, 'PREPAGA', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", org, "OS-" + s, "Financiador " + s);
		long plan = insertar("""
				INSERT INTO plan_cobertura (organization_id, financiador_id, codigo, nombre,
				                            vigencia_desde, requiere_autorizacion,
				                            requiere_credencial, active, version,
				                            created_at, updated_at)
				VALUES (?, ?, ?, ?, '2020-01-01', 1, 0, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", org, financiador, "P-" + s, "Plan " + s);
		long cobertura = insertar("""
				INSERT INTO cobertura_paciente (
				        organization_id, persona_id, tipo,
				        financiador_id, financiador_codigo, financiador_nombre, financiador_tipo,
				        plan_id, plan_codigo, plan_nombre,
				        requeria_autorizacion, requeria_credencial, referencia_capturada_el,
				        numero_afiliado, vigencia_desde, principal, active, version,
				        created_at, updated_at)
				VALUES (?, ?, 'FINANCIADA', ?, ?, ?, 'PREPAGA', ?, ?, ?, 1, 0, UTC_TIMESTAMP(6),
				        ?, '2020-01-01', 1, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", org, persona, financiador, "OS-" + s, "Financiador OS-" + s,
				plan, "P-" + s, "Plan P-" + s, "AF-" + s);

		long especialidad = insertar("""
				INSERT INTO especialidad (organization_id, codigo, name, valid_from, active,
				                          version, created_at, updated_at)
				VALUES (?, ?, ?, '2020-01-01 00:00:00.000000', 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", org, "E-" + s, "Especialidad " + s);
		long practica = insertar("""
				INSERT INTO practica (organization_id, especialidad_id, codigo, name, valid_from,
				                      active, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, '2020-01-01 00:00:00.000000', 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", org, especialidad, "P1-" + s, "Practica " + s);

		return new Fixture(org, sede, admin, persona, cobertura, practica);
	}

	private long insertar(String insert, Object... args) {
		jdbc.update(insert, args);
		return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}
}
