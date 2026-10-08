package com.akine.contracting;

import com.akine.TestcontainersConfiguration;
import com.akine.contracting.ConvenioFixtures.Desenlace;
import com.akine.contracting.ConvenioFixtures.Escenario;
import com.akine.contracting.application.ConvenioAltaCommand;
import com.akine.contracting.application.ConvenioService;
import com.akine.contracting.application.ImportacionAranceles.EstadoFila;
import com.akine.contracting.application.ImportacionAranceles.Fila;
import com.akine.contracting.application.ImportacionAranceles.MotivoRechazo;
import com.akine.contracting.application.ImportacionAranceles.Resultado;
import com.akine.contracting.application.ImportacionAranceles.ResultadoFila;
import com.akine.contracting.application.ImportacionArancelesRechazadaException;
import com.akine.contracting.application.ImportacionArancelesService;
import com.akine.contracting.application.OperatingActor;
import com.akine.contracting.domain.ModalidadConvenio;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static com.akine.contracting.ConvenioFixtures.causaEs;
import static com.akine.contracting.ConvenioFixtures.enParalelo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B-7 (RF-M16-007) contra MySQL real: la confirmacion es todo o nada, y dos confirmaciones
 * concurrentes sobre el mismo convenio no pueden dejar aranceles solapados.
 *
 * <p>La segunda es la misma pregunta de {@code ConvenioConcurrenteIT} con lotes en vez de altas
 * sueltas: lo que la decide es el {@code FOR UPDATE} sobre {@code convenio_lock} y el
 * {@code READ_COMMITTED} de la relectura, y eso solo lo contesta InnoDB.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class ImportacionArancelesIT {

	private static final LocalDate ENERO = LocalDate.of(2027, 1, 1);
	private static final LocalDate MARZO = LocalDate.of(2027, 3, 1);
	private static final LocalDate JUNIO_30 = LocalDate.of(2027, 6, 30);
	private static final LocalDate JULIO = LocalDate.of(2027, 7, 1);
	private static final LocalDate DICIEMBRE = LocalDate.of(2027, 12, 31);

	@Autowired private ConvenioService convenioService;
	@Autowired private ImportacionArancelesService importacion;
	@Autowired private JdbcTemplate jdbc;

	private ConvenioFixtures fixtures;

	@BeforeEach
	void prepararFixtures() {
		fixtures = new ConvenioFixtures(jdbc);
	}

	@Test
	@DisplayName("una fila invalida en la confirmacion: no entra ninguna, ni las validas")
	void todo_o_nada() {
		Escenario escenario = fixtures.crear();
		long otraPracticaId = fixtures.otraPractica(escenario);
		OperatingActor actor = actor(escenario);
		long convenioId = convenio(escenario, actor);

		List<Fila> lote = List.of(
				fila(escenario.practicaId(), "12000.00", ENERO, JUNIO_30),
				fila(otraPracticaId, "9000.00", ENERO, DICIEMBRE),
				// Importes que no cuadran: la unica fila mala.
				new Fila(escenario.practicaId(), null, null, new BigDecimal("100.00"),
						new BigDecimal("90.00"), BigDecimal.ZERO, JULIO, DICIEMBRE));

		Resultado preview = importacion.previsualizar(
				actor, escenario.consultorioId(), convenioId, lote);
		assertThat(preview.filas()).extracting(ResultadoFila::estado)
				.containsExactly(EstadoFila.ALTA, EstadoFila.ALTA, EstadoFila.RECHAZADA);

		assertThatThrownBy(() -> importacion.confirmar(
				actor, escenario.consultorioId(), convenioId, lote))
				.isInstanceOf(ImportacionArancelesRechazadaException.class);

		assertThat(contarAranceles(convenioId))
				.as("la confirmacion rechazada no deja ni las dos filas validas")
				.isZero();
		assertThat(contarAuditoria(escenario, "ARANCELES_IMPORTADOS")).isZero();

		// Corregida la fila, el mismo lote entra entero.
		List<Fila> corregido = List.of(lote.get(0), lote.get(1),
				fila(escenario.practicaId(), "13000.00", JULIO, DICIEMBRE));
		Resultado aplicado = importacion.confirmar(
				actor, escenario.consultorioId(), convenioId, corregido);
		assertThat(aplicado.aplicada()).isTrue();
		assertThat(contarAranceles(convenioId)).isEqualTo(3);
		assertThat(contarAuditoria(escenario, "ARANCELES_IMPORTADOS")).isEqualTo(1);

		// Reintento del mismo lote (CA-M16-007-05): choca contra sus propias filas, nada se duplica.
		assertThatThrownBy(() -> importacion.confirmar(
				actor, escenario.consultorioId(), convenioId, corregido))
				.isInstanceOf(ImportacionArancelesRechazadaException.class)
				.satisfies(error -> assertThat(
						((ImportacionArancelesRechazadaException) error).getFilas())
						.extracting(ResultadoFila::motivo)
						.containsOnly(MotivoRechazo.ARANCEL_SOLAPADO));
		assertThat(contarAranceles(convenioId)).isEqualTo(3);
	}

	@Test
	@DisplayName("dos confirmaciones concurrentes que se pisan: entra un lote entero y el otro nada")
	void confirmaciones_concurrentes() {
		Escenario escenario = fixtures.crear();
		long otraPracticaId = fixtures.otraPractica(escenario);
		OperatingActor actor = actor(escenario);
		long convenioId = convenio(escenario, actor);

		// Cada lote es valido por si solo y los dos pasarian el preview a la vez. Se pisan en la
		// practica principal (enero-junio contra marzo-diciembre).
		List<Fila> loteA = List.of(
				fila(escenario.practicaId(), "12000.00", ENERO, JUNIO_30),
				fila(otraPracticaId, "9000.00", ENERO, JUNIO_30));
		List<Fila> loteB = List.of(
				fila(otraPracticaId, "9500.00", JULIO, DICIEMBRE),
				fila(escenario.practicaId(), "18000.00", MARZO, DICIEMBRE));

		List<Desenlace<Resultado>> desenlaces = enParalelo(List.of(
				() -> importacion.confirmar(actor, escenario.consultorioId(), convenioId, loteA),
				() -> importacion.confirmar(actor, escenario.consultorioId(), convenioId, loteB)));

		assertThat(desenlaces.stream().filter(d -> !d.fallo()).count())
				.as("exactamente un lote entra. Desenlaces: %s", desenlaces)
				.isEqualTo(1);
		assertThat(desenlaces.stream()
				.filter(Desenlace::fallo)
				.filter(d -> causaEs(d.error(), ImportacionArancelesRechazadaException.class))
				.count())
				.as("y el otro recibe el rechazo de la importacion, no un deadlock. Desenlaces: %s",
						desenlaces)
				.isEqualTo(1);

		assertThat(contarAranceles(convenioId))
				.as("quedan las dos filas del ganador y ninguna del perdedor, ni siquiera la que no "
						+ "chocaba")
				.isEqualTo(2);
		assertThat(contarSolapados(convenioId))
				.as("ningun par de aranceles activos del mismo grupo se pisa")
				.isZero();
	}

	// =================================================================================
	// Apoyo
	// =================================================================================

	private static OperatingActor actor(Escenario escenario) {
		return new OperatingActor(
				escenario.accountId(), false, escenario.organizationId(), escenario.consultorioId());
	}

	private long convenio(Escenario escenario, OperatingActor actor) {
		return convenioService.crear(actor, escenario.consultorioId(), new ConvenioAltaCommand(
				escenario.financiadorId(), escenario.planId(), "CONV-IMP", "Convenio importado",
				ModalidadConvenio.POR_PRESTACION, ENERO, DICIEMBRE, "ARS",
				null, null, null, null, null, null)).id();
	}

	private static Fila fila(long practicaId, String importe, LocalDate desde, LocalDate hasta) {
		return new Fila(practicaId, null, null, new BigDecimal(importe), new BigDecimal(importe),
				BigDecimal.ZERO, desde, hasta);
	}

	private long contarAranceles(long convenioId) {
		return jdbc.queryForObject(
				"SELECT COUNT(*) FROM convenio_arancel WHERE convenio_id = ? AND active = 1",
				Long.class, convenioId);
	}

	private long contarSolapados(long convenioId) {
		return jdbc.queryForObject("""
				SELECT COUNT(*) FROM convenio_arancel a
				  JOIN convenio_arancel b
				    ON b.convenio_id = a.convenio_id AND b.practica_id = a.practica_id
				   AND b.id > a.id AND a.active = 1 AND b.active = 1
				   AND (a.oferta_id <=> b.oferta_id)
				   AND (a.vigencia_hasta IS NULL OR a.vigencia_hasta >= b.vigencia_desde)
				   AND (b.vigencia_hasta IS NULL OR b.vigencia_hasta >= a.vigencia_desde)
				 WHERE a.convenio_id = ?
				""", Long.class, convenioId);
	}

	private long contarAuditoria(Escenario escenario, String evento) {
		return jdbc.queryForObject(
				"SELECT COUNT(*) FROM audit_event WHERE organization_id = ? AND event_type = ?",
				Long.class, escenario.organizationId(), evento);
	}
}
