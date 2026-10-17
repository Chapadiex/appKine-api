package com.akine.activity;

import com.akine.TestcontainersConfiguration;
import com.akine.activity.ActividadItFixture.Tenant;
import com.akine.activity.application.AjustarSaldoCommand;
import com.akine.activity.application.AjusteDePaseService;
import com.akine.activity.application.AsistenciaService;
import com.akine.activity.application.ClaseService;
import com.akine.activity.application.InscripcionService;
import com.akine.activity.application.ProductoService;
import com.akine.activity.application.RegistrarAsistenciaCommand;
import com.akine.activity.application.VenderPaseCommand;
import com.akine.activity.application.VentaDePaseService;
import com.akine.activity.domain.OrigenAsistencia;
import com.akine.activity.domain.ResultadoAsistencia;
import com.akine.testsupport.ContadorDeSentencias;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>Cuantas sentencias cuestan los listados de F9</b> (AKINE-09.03), con la misma vara que
 * {@code AgendaDelDiaSentenciasIT}: el mismo listado con una fila y con seis. Si la cuenta crece con
 * las filas, hay una consulta por fila.
 *
 * <p>Los listados: clases de la sede, participantes de una clase, su detalle operativo, pases de una
 * persona, el ledger paginado de un pase y productos de la sede. Ademas se <b>mide</b> —sin
 * afirmar igualdad, porque cada item es una transaccion propia por diseno (RF-M13-008)— la
 * asistencia en lote, para dejar escrito cuanto cuesta cada participante.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import({TestcontainersConfiguration.class, ContadorDeSentencias.Config.class})
class ListadosDeActividadSentenciasIT {

	private static final int MUCHAS = 6;

	@Autowired private ClaseService claseService;
	@Autowired private InscripcionService inscripcionService;
	@Autowired private AsistenciaService asistenciaService;
	@Autowired private ProductoService productoService;
	@Autowired private VentaDePaseService ventaService;
	@Autowired private AjusteDePaseService ajusteService;
	@Autowired private JdbcTemplate jdbc;

	private ActividadItFixture fx;

	@BeforeEach
	void setUp() {
		fx = new ActividadItFixture(jdbc, inscripcionService);
	}

	@Test
	@DisplayName("Los listados de clases, inscripciones y pases hacen las mismas sentencias con una fila que con seis")
	void los_listados_no_crecen_con_las_filas() {
		Escenario una = escenario(1);
		Escenario seis = escenario(MUCHAS);

		Map<String, int[]> medidas = new LinkedHashMap<>();
		medidas.put("clases de la sede (ClaseService.listar)",
				comparar(una, seis, e -> claseService.listar(e.tenant.actor(), e.tenant.consultorioId(),
						Instant.now(), Instant.now().plus(30, ChronoUnit.DAYS)).size()));
		medidas.put("participantes (InscripcionService.participantes)",
				comparar(una, seis, e -> inscripcionService.participantes(
						e.tenant.actor(), e.tenant.consultorioId(), e.clase).size()));
		medidas.put("detalle operativo (AsistenciaService.detalleOperativo)",
				comparar(una, seis, e -> asistenciaService.detalleOperativo(
						e.tenant.actor(), e.tenant.consultorioId(), e.clase, 0, 50).contenido().size()));
		medidas.put("pases de la persona (VentaDePaseService.deLaPersona)",
				comparar(una, seis, e -> ventaService.deLaPersona(
						e.tenant.actor(), e.tenant.consultorioId(), e.tenant.persona(0)).size()));
		medidas.put("ledger paginado (VentaDePaseService.movimientos)",
				comparar(una, seis, e -> (int) ventaService.movimientos(
						e.tenant.actor(), e.tenant.consultorioId(), e.pase, 0, 50).getNumberOfElements()));
		medidas.put("productos de la sede (ProductoService.listar)",
				comparar(una, seis, e -> productoService.listar(
						e.tenant.actor(), e.tenant.consultorioId(), false).size()));

		medidas.forEach((listado, cuenta) -> System.out.printf(
				"[sentencias] %-58s 1 fila = %2d, %d filas = %2d%n", listado, cuenta[0], MUCHAS, cuenta[1]));
		medidas.forEach((listado, cuenta) -> assertThat(cuenta[1])
				.as("%s no hace una consulta por fila", listado)
				.isEqualTo(cuenta[0]));
	}

	@Test
	@DisplayName("Medicion: la asistencia en lote cuesta lo mismo por participante, sea el primero o el sexto")
	void asistencia_en_lote_por_participante() {
		Escenario una = escenarioDeLote(1);
		Escenario seis = escenarioDeLote(MUCHAS);

		int conUno = ContadorDeSentencias.contar(() -> lote(una));
		int conSeis = ContadorDeSentencias.contar(() -> lote(seis));
		int porParticipante = (conSeis - conUno) / (MUCHAS - 1);
		System.out.printf("[sentencias] asistencia en lote: 1 participante = %d, %d = %d (%d por participante)%n",
				conUno, MUCHAS, conSeis, porParticipante);

		// Lineal y sin nada que crezca con lo que ya se registro: el sexto cuesta lo mismo que el
		// segundo. Si el costo por item dependiera de cuantos van, la diferencia no seria exacta.
		assertThat(conSeis - conUno).isEqualTo(porParticipante * (MUCHAS - 1));
	}

	// =================================================================================

	/** Un tenant con {@code filas} de cada cosa que se lista. */
	private Escenario escenario(int filas) {
		Tenant tenant = fx.crearTenant(10, filas);
		PaseVentaYConsumoIT.declararPorPack(jdbc, tenant.ofertaId());
		long clase = 0;
		for (int i = 0; i < filas; i++) {
			long una = fx.programarClase(tenant, 10, 1 + i);
			if (clase == 0) {
				clase = una;
			}
		}
		for (Long persona : tenant.personas()) {
			fx.inscribir(tenant, clase, persona, false);
		}
		long pase = 0;
		for (int i = 0; i < filas; i++) {
			long producto = productoService.crear(tenant.actor(), tenant.consultorioId(),
					PaseVentaYConsumoIT.comando(tenant.ofertaId(), "Pack " + i, 8)).id();
			long vendido = ventaService.vender(tenant.actor(), tenant.consultorioId(),
					new VenderPaseCommand(producto, tenant.persona(0), null)).pase().id();
			if (pase == 0) {
				pase = vendido;
			}
		}
		// El ledger del primer pase con tantos movimientos como filas: la COMPRA y ajustes de un credito.
		for (int i = 1; i < filas; i++) {
			long version = jdbc.queryForObject("SELECT version FROM pase_servicio WHERE id = ?", Long.class, pase);
			ajusteService.ajustar(tenant.actor(), tenant.consultorioId(), pase,
					new AjustarSaldoCommand(-1, "Medicion " + i, version), false);
		}
		// Asistencias en la clase, para que el detalle operativo tenga que resolverlas. AUSENTE para
		// que ninguna consuma un credito y cambie el largo del ledger que se mide arriba.
		asistenciaService.iniciar(tenant.actor(), tenant.consultorioId(), clase);
		for (Long persona : tenant.personas()) {
			asistenciaService.registrar(tenant.actor(), tenant.consultorioId(), clase,
					new RegistrarAsistenciaCommand(fx.inscripcionDe(clase, persona), ResultadoAsistencia.AUSENTE,
							OrigenAsistencia.MOSTRADOR, null, null));
		}
		return new Escenario(tenant, clase, pase, List.of());
	}

	private Escenario escenarioDeLote(int participantes) {
		Tenant tenant = fx.crearTenant(10, participantes);
		PaseVentaYConsumoIT.declararPorPack(jdbc, tenant.ofertaId());
		long producto = productoService.crear(tenant.actor(), tenant.consultorioId(),
				PaseVentaYConsumoIT.comando(tenant.ofertaId(), "Pack", 8)).id();
		long clase = fx.programarClase(tenant, 10, 1);
		List<Long> inscripciones = new ArrayList<>();
		for (Long persona : tenant.personas()) {
			ventaService.vender(tenant.actor(), tenant.consultorioId(), new VenderPaseCommand(producto, persona, null));
			fx.inscribir(tenant, clase, persona, false);
			inscripciones.add(fx.inscripcionDe(clase, persona));
		}
		asistenciaService.iniciar(tenant.actor(), tenant.consultorioId(), clase);
		return new Escenario(tenant, clase, 0, inscripciones);
	}

	private void lote(Escenario e) {
		asistenciaService.registrarLote(e.tenant.actor(), e.tenant.consultorioId(), e.clase,
				e.inscripciones.stream()
						.map(id -> new RegistrarAsistenciaCommand(id, ResultadoAsistencia.PRESENTE,
								OrigenAsistencia.MOSTRADOR, null, null))
						.toList());
	}

	/** {@code [sentencias con una fila, sentencias con seis]}, verificando que el listado trajo las filas. */
	private static int[] comparar(Escenario una, Escenario seis, java.util.function.ToIntFunction<Escenario> listado) {
		int[] conUna = new int[1];
		int[] conSeis = new int[1];
		int filasUna = ContadorDeSentencias.contar(conUna, () -> listado.applyAsInt(una));
		int filasSeis = ContadorDeSentencias.contar(conSeis, () -> listado.applyAsInt(seis));
		assertThat(filasUna).isEqualTo(1);
		assertThat(filasSeis).isEqualTo(MUCHAS);
		return new int[] {conUna[0], conSeis[0]};
	}

	private record Escenario(Tenant tenant, long clase, long pase, List<Long> inscripciones) {
	}
}
