package com.akine.billing.infrastructure;

import com.akine.billing.domain.ConceptoObligacion;
import com.akine.billing.domain.Obligacion;
import com.akine.billing.domain.Responsable;
import com.akine.billing.domain.port.ObligacionRepositoryPort;
import com.akine.contracting.spi.ArancelCongelado;
import com.akine.contracting.spi.ArancelDirectory;
import com.akine.contracting.spi.ArancelVigente;
import com.akine.contracting.spi.ResolucionDeArancel;
import com.akine.encounter.spi.SesionCerrada;
import com.akine.offering.spi.PracticaDeOferta;
import com.akine.offering.spi.PracticasDeOfertaDirectory;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.person.spi.CoberturaAplicable;
import com.akine.person.spi.CoberturasAplicablesDirectory;
import com.akine.person.spi.ReferenciaCongelada;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * El reparto de AKINE F-4: financiador + coseguro con convenio, particular sin el.
 *
 * <p>Lo que se mide es la DECISION: que practica, que cobertura, cuantas filas y por cuanto. Que
 * las filas pasen las CHECK de V77 y que el cierre real las produzca lo mide
 * {@code ObligacionDelFinanciadorIT} contra MySQL.
 */
@DisplayName("Devengo de la obligacion al cerrar la sesion (AKINE F-4)")
class ObligacionDevengadorTest {

	private static final long ORG = 7L;
	private static final long SEDE = 20L;
	private static final long PERSONA = 1204L;
	private static final long SESION = 3312L;
	private static final long OFERTA = 42L;
	private static final long FINANCIADOR = 31L;
	private static final long PLAN = 4L;
	private static final long COBERTURA = 310L;
	private static final long KINE = 55L;
	private static final long FONO = 56L;
	// 23:30 en Cordoba del 06/10 es 07/10 en UTC: el dia tiene que salir de la sede.
	private static final Instant CERRADA = Instant.parse("2026-10-07T02:30:00Z");
	private static final LocalDate DIA_LOCAL = LocalDate.of(2026, 10, 6);

	private final ObligacionRepositoryPort obligaciones = mock(ObligacionRepositoryPort.class);
	private final CoberturasAplicablesDirectory coberturas = mock(CoberturasAplicablesDirectory.class);
	private final ArancelDirectory aranceles = mock(ArancelDirectory.class);
	private final PracticasDeOfertaDirectory practicas = mock(PracticasDeOfertaDirectory.class);
	private final ConsultorioDirectory consultorios = mock(ConsultorioDirectory.class);

	private final ObligacionDevengador devengador = new ObligacionDevengador(
			obligaciones, coberturas, aranceles, practicas, consultorios);

	@BeforeEach
	void base() {
		given(obligaciones.findDeLaSesion(SESION)).willReturn(List.of());
		given(obligaciones.save(any())).willAnswer(i -> i.getArgument(0));
		given(consultorios.find(ORG, SEDE)).willReturn(Optional.of(
				new ConsultorioSnapshot(SEDE, ORG, "Sede", "America/Argentina/Cordoba", true)));
		given(practicas.practicasHabilitadas(ORG, SEDE, OFERTA))
				.willReturn(List.of(new PracticaDeOferta(KINE, true)));
	}

	@Test
	@DisplayName("con cobertura y arancel: financiador + coseguro, que suman el total del arancel")
	void financiador_y_coseguro() {
		cubre(KINE, "12000.00", "10500.00", "1500.00");

		devengador.alCerrar(cierre(Set.of(KINE), true));

		List<Obligacion> filas = guardadas(2);
		Obligacion financiador = filas.get(0);
		Obligacion coseguro = filas.get(1);

		assertThat(financiador.getConcepto()).isEqualTo(ConceptoObligacion.FINANCIADOR);
		assertThat(financiador.getResponsable()).isEqualTo(Responsable.FINANCIADOR);
		assertThat(financiador.getFinanciadorId()).isEqualTo(FINANCIADOR);
		assertThat(financiador.getImporteOriginal()).isEqualByComparingTo("10500.00");
		assertThat(coseguro.getConcepto()).isEqualTo(ConceptoObligacion.COSEGURO);
		assertThat(coseguro.getResponsable()).isEqualTo(Responsable.PACIENTE);
		assertThat(coseguro.getFinanciadorId()).isNull();
		assertThat(coseguro.getImporteOriginal()).isEqualByComparingTo("1500.00");
		assertThat(financiador.getImporteOriginal().add(coseguro.getImporteOriginal()))
				.isEqualByComparingTo("12000.00");

		// El snapshot va entero en las dos, con los requisitos para RF-M21-003.
		for (Obligacion fila : filas) {
			var snapshot = fila.getSnapshotDeConvenio().orElseThrow();
			assertThat(snapshot.convenioId()).isEqualTo(12L);
			assertThat(snapshot.arancelId()).isEqualTo(77L);
			assertThat(snapshot.coberturaId()).isEqualTo(COBERTURA);
			assertThat(snapshot.requeriaOrden()).isTrue();
			assertThat(snapshot.requeriaAutorizacion()).isFalse();
			assertThat(snapshot.credencialVencida()).isTrue();
			assertThat(fila.getPracticaId()).isEqualTo(KINE);
			assertThat(fila.getMoneda()).isEqualTo("ARS");
			assertThat(fila.isAlertaPracticaNoHabilitada()).isFalse();
		}
	}

	@Test
	@DisplayName("el dia del convenio es el de la sede, no el de UTC")
	void dia_local_de_la_sede() {
		cubre(KINE, "12000.00", "10500.00", "1500.00");

		devengador.alCerrar(cierre(Set.of(KINE), true));

		verify(coberturas).aplicables(ORG, SEDE, PERSONA, KINE, OFERTA, DIA_LOCAL);
		verify(aranceles).congelar(ORG, SEDE, FINANCIADOR, PLAN, KINE, OFERTA, DIA_LOCAL);
	}

	@Test
	@DisplayName("coseguro cero (cobertura total): solo la fila del financiador")
	void coseguro_cero() {
		cubre(KINE, "12000.00", "12000.00", "0.00");

		devengador.alCerrar(cierre(Set.of(KINE), true));

		assertThat(guardadas(1).get(0).getConcepto()).isEqualTo(ConceptoObligacion.FINANCIADOR);
	}

	@Test
	@DisplayName("financiador cero: solo el coseguro, a cargo del paciente")
	void financiador_cero() {
		cubre(KINE, "3000.00", "0.00", "3000.00");

		devengador.alCerrar(cierre(Set.of(KINE), true));

		assertThat(guardadas(1).get(0).getConcepto()).isEqualTo(ConceptoObligacion.COSEGURO);
	}

	@Test
	@DisplayName("arancel total cero: practica sin cargo, ninguna deuda (ni particular)")
	void total_cero() {
		cubre(KINE, "0.00", "0.00", "0.00");

		devengador.alCerrar(cierre(Set.of(KINE), true));

		verify(obligaciones, never()).save(any());
	}

	@Test
	@DisplayName("sin cobertura aplicable: una sola particular por el precio de la oferta")
	void sin_cobertura_particular() {
		given(coberturas.aplicables(anyLong(), anyLong(), anyLong(), anyLong(), any(), any()))
				.willReturn(List.of());

		devengador.alCerrar(cierre(Set.of(KINE), true));

		Obligacion particular = guardadas(1).get(0);
		assertThat(particular.getConcepto()).isEqualTo(ConceptoObligacion.PARTICULAR);
		assertThat(particular.getResponsable()).isEqualTo(Responsable.PACIENTE);
		assertThat(particular.getImporteOriginal()).isEqualByComparingTo("8500.00");
		assertThat(particular.getSnapshotDeConvenio()).isEmpty();
	}

	@Test
	@DisplayName("la oferta no admite obra social: particular, y ni se pregunta por cobertura")
	void oferta_sin_obra_social() {
		devengador.alCerrar(cierre(Set.of(KINE), false));

		assertThat(guardadas(1).get(0).getConcepto()).isEqualTo(ConceptoObligacion.PARTICULAR);
		verifyNoInteractions(coberturas, aranceles);
	}

	@Test
	@DisplayName("E-7: la recepcion lo resolvio como Particular: solo particular aunque haya convenio")
	void particular_por_recepcion_manda_sobre_la_cobertura() {
		cubre(KINE, "12000.00", "10500.00", "1500.00");

		devengador.alCerrar(new SesionCerrada(SESION, ORG, SEDE, PERSONA, OFERTA, 8, true, CERRADA,
				99L, new BigDecimal("8500.00"), "ARS", Set.of(KINE), null, true, 600L, true));

		Obligacion particular = guardadas(1).get(0);
		assertThat(particular.getConcepto()).isEqualTo(ConceptoObligacion.PARTICULAR);
		assertThat(particular.getResponsable()).isEqualTo(Responsable.PACIENTE);
		assertThat(particular.getImporteOriginal()).isEqualByComparingTo("8500.00");
		assertThat(particular.getFinanciadorId()).isNull();
		verifyNoInteractions(coberturas, aranceles);
	}

	@Test
	@DisplayName("E-7: con turno y recepcion con cobertura, el devengo por convenio no cambia")
	void recepcion_con_cobertura_no_cambia() {
		cubre(KINE, "12000.00", "10500.00", "1500.00");

		devengador.alCerrar(new SesionCerrada(SESION, ORG, SEDE, PERSONA, OFERTA, 8, true, CERRADA,
				99L, new BigDecimal("8500.00"), "ARS", Set.of(KINE), null, true, 600L, false));

		assertThat(guardadas(2)).extracting(Obligacion::getConcepto)
				.containsExactly(ConceptoObligacion.FINANCIADOR, ConceptoObligacion.COSEGURO);
	}

	@Test
	@DisplayName("aplicable pero congelar no devuelve (carrera): prueba la siguiente y si no, particular")
	void congelar_vacio_cae_a_particular() {
		given(coberturas.aplicables(anyLong(), anyLong(), anyLong(), anyLong(), any(), any()))
				.willReturn(List.of(aplicable()));
		given(aranceles.congelar(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), any(), any()))
				.willReturn(Optional.empty());

		devengador.alCerrar(cierre(Set.of(KINE), true));

		assertThat(guardadas(1).get(0).getConcepto()).isEqualTo(ConceptoObligacion.PARTICULAR);
	}

	@Test
	@DisplayName("sin tratamientos: se usa la practica principal de la oferta (DP-11)")
	void sin_tratamientos_usa_la_principal() {
		cubre(KINE, "12000.00", "10500.00", "1500.00");

		devengador.alCerrar(cierre(Set.of(), true));

		verify(coberturas).aplicables(ORG, SEDE, PERSONA, KINE, OFERTA, DIA_LOCAL);
		assertThat(guardadas(2)).allMatch(o -> o.getPracticaId() == KINE);
	}

	@Test
	@DisplayName("sin tratamientos y la oferta sin practicas: particular")
	void sin_tratamientos_ni_practicas_particular() {
		given(practicas.practicasHabilitadas(ORG, SEDE, OFERTA)).willReturn(List.of());

		devengador.alCerrar(cierre(Set.of(), true));

		assertThat(guardadas(1).get(0).getConcepto()).isEqualTo(ConceptoObligacion.PARTICULAR);
		verifyNoInteractions(coberturas);
	}

	@Test
	@DisplayName("realizada no habilitada en la oferta: se devenga igual y queda la alerta (DP-11)")
	void realizada_no_habilitada_alerta() {
		cubre(FONO, "9000.00", "8000.00", "1000.00");

		devengador.alCerrar(cierre(Set.of(FONO), true));

		assertThat(guardadas(2)).allMatch(Obligacion::isAlertaPracticaNoHabilitada);
	}

	@Test
	@DisplayName("dos practicas realizadas: UNA obligacion por responsable, la principal primero")
	void dos_practicas_una_sola_facturada() {
		cubre(KINE, "12000.00", "10500.00", "1500.00");
		cubre(FONO, "9000.00", "8000.00", "1000.00");

		devengador.alCerrar(cierre(Set.of(FONO, KINE), true));

		List<Obligacion> filas = guardadas(2);
		assertThat(filas).allMatch(o -> o.getPracticaId() == KINE);
		verify(aranceles, never()).congelar(anyLong(), anyLong(), anyLong(), anyLong(), eq(FONO), any(), any());
	}

	@Test
	@DisplayName("si la principal no tiene cobertura, se factura la siguiente realizada")
	void la_principal_sin_cobertura_pasa_a_la_siguiente() {
		given(coberturas.aplicables(ORG, SEDE, PERSONA, KINE, OFERTA, DIA_LOCAL)).willReturn(List.of());
		cubre(FONO, "9000.00", "8000.00", "1000.00");

		devengador.alCerrar(cierre(Set.of(FONO, KINE), true));

		assertThat(guardadas(2)).allMatch(o -> o.getPracticaId() == FONO);
	}

	@Test
	@DisplayName("re-disparo: si la sesion ya tiene deuda, no se devenga nada mas")
	void redisparo_idempotente() {
		Obligacion previa = new Obligacion(ORG, SEDE, SESION, PERSONA, Responsable.PACIENTE,
				new BigDecimal("8500.00"), "ARS", OFERTA, "Sesion 8", CERRADA);
		given(obligaciones.findDeLaSesion(SESION)).willReturn(List.of(previa));

		devengador.alCerrar(cierre(Set.of(KINE), true));

		verify(obligaciones, never()).save(any());
		verifyNoInteractions(coberturas, aranceles);
	}

	@Test
	@DisplayName("sin asistencia no hay deuda")
	void sin_asistencia() {
		devengador.alCerrar(new SesionCerrada(SESION, ORG, SEDE, PERSONA, OFERTA, 8, false, CERRADA,
				99L, new BigDecimal("8500.00"), "ARS", Set.of(KINE), null, true));

		verifyNoInteractions(obligaciones, coberturas, aranceles);
	}

	@Test
	@DisplayName("particular sin precio: no se devenga y no se lanza")
	void particular_sin_precio() {
		devengador.alCerrar(new SesionCerrada(SESION, ORG, SEDE, PERSONA, OFERTA, 8, true, CERRADA,
				99L, null, null, Set.of(KINE), null, false));

		verify(obligaciones, never()).save(any());
	}

	@Test
	@DisplayName("con una sede que no resuelve, el dia se calcula en UTC")
	void sede_no_resuelve_usa_utc() {
		given(consultorios.find(ORG, SEDE)).willReturn(Optional.empty());

		devengador.alCerrar(cierre(Set.of(KINE), true));

		verify(coberturas).aplicables(ORG, SEDE, PERSONA, KINE, OFERTA, LocalDate.of(2026, 10, 7));
	}

	@Test
	@DisplayName("con una zona mal cargada en la sede, el dia se calcula en UTC")
	void zona_invalida_usa_utc() {
		given(consultorios.find(ORG, SEDE)).willReturn(Optional.of(
				new ConsultorioSnapshot(SEDE, ORG, "Sede", "Marte/Olympus", true)));

		devengador.alCerrar(cierre(Set.of(KINE), true));

		verify(coberturas).aplicables(ORG, SEDE, PERSONA, KINE, OFERTA, LocalDate.of(2026, 10, 7));
	}

	// =================================================================================

	private SesionCerrada cierre(Set<Long> practicasRealizadas, boolean admiteObraSocial) {
		return new SesionCerrada(SESION, ORG, SEDE, PERSONA, OFERTA, 8, true, CERRADA, 99L,
				new BigDecimal("8500.00"), "ARS", practicasRealizadas, null, admiteObraSocial);
	}

	private List<Obligacion> guardadas(int cuantas) {
		ArgumentCaptor<Obligacion> captor = ArgumentCaptor.forClass(Obligacion.class);
		verify(obligaciones, times(cuantas)).save(captor.capture());
		return captor.getAllValues();
	}

	private static CoberturaAplicable aplicable() {
		return new CoberturaAplicable(COBERTURA, true,
				new ReferenciaCongelada(FINANCIADOR, "Obra social", PLAN, "Plan 210"),
				// La lectura viva de B-2. El devengador no la usa para guardar: congela aparte.
				ResolucionDeArancel.resuelta(new ArancelVigente(12L, "CONV-1", "Convenio sintetico",
						"POR_PRESTACION", FINANCIADOR, PLAN, KINE, 77L, new BigDecimal("12000.00"),
						new BigDecimal("10500.00"), new BigDecimal("1500.00"), "ARS", true, false,
						true, null, LocalDate.of(2026, 1, 1), null, LocalDate.of(2026, 1, 1), null,
						DIA_LOCAL)),
				true, LocalDate.of(2026, 1, 1));
	}

	private void cubre(long practica, String total, String financiador, String coseguro) {
		given(coberturas.aplicables(ORG, SEDE, PERSONA, practica, OFERTA, DIA_LOCAL))
				.willReturn(List.of(aplicable()));
		given(aranceles.congelar(ORG, SEDE, FINANCIADOR, PLAN, practica, OFERTA, DIA_LOCAL))
				.willReturn(Optional.of(new ArancelCongelado(12L, "CONV-1", "Convenio sintetico",
						"POR_PRESTACION", FINANCIADOR, PLAN, practica, 77L, new BigDecimal(total),
						new BigDecimal(financiador), new BigDecimal(coseguro), "ARS", true, false,
						true, DIA_LOCAL, CERRADA)));
	}
}
