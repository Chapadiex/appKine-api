package com.akine.person.application;

import com.akine.contracting.spi.ArancelDirectory;
import com.akine.contracting.spi.ArancelVigente;
import com.akine.contracting.spi.MotivoSinArancel;
import com.akine.contracting.spi.ReferenciaDeCobertura;
import com.akine.contracting.spi.ResolucionDeArancel;
import com.akine.offering.spi.OfertaDirectory;
import com.akine.offering.spi.OfertaSnapshot;
import com.akine.offering.spi.PracticaDeOferta;
import com.akine.offering.spi.PracticasDeOfertaDirectory;
import com.akine.offering.spi.PrecioDeOferta;
import com.akine.person.application.CoberturaParaOferta.Condicion;
import com.akine.person.application.CoberturaParaOferta.Motivo;
import com.akine.person.domain.CoberturaPaciente;
import com.akine.person.domain.Persona;
import com.akine.person.domain.exception.OfertaNoAccesibleEnSedeException;
import com.akine.person.domain.exception.PersonaNotAccessibleException;
import com.akine.person.domain.port.PersonRepositoryPorts.CoberturaPacienteRepositoryPort;
import com.akine.person.domain.port.PersonRepositoryPorts.PersonaRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * B-3 (RF-M08-006/007): la cobertura aplicable por oferta, sin base. El tenant, la sede y el
 * arancel por oferta contra MySQL los cubre {@code CoberturaPorOfertaIT}.
 */
@DisplayName("Cobertura aplicable por oferta (B-3)")
class CoberturaParaOfertaServiceTest {

	private static final long ORG = 7L;
	private static final long SEDE = 20L;
	private static final long PERSONA = 1204L;
	private static final long OFERTA = 34L;
	private static final long KINE = 410L;
	private static final long FONO = 411L;
	private static final LocalDate HOY = LocalDate.of(2027, 6, 15);

	private final OperatingActor actor = new OperatingActor(40L, false, ORG, SEDE);

	private CoberturaPacienteRepositoryPort coberturas;
	private ArancelDirectory aranceles;
	private OfertaDirectory ofertas;
	private PracticasDeOfertaDirectory practicas;
	private CoberturaParaOfertaService servicio;

	@BeforeEach
	void setUp() {
		coberturas = mock(CoberturaPacienteRepositoryPort.class);
		aranceles = mock(ArancelDirectory.class);
		ofertas = mock(OfertaDirectory.class);
		practicas = mock(PracticasDeOfertaDirectory.class);
		PersonaRepositoryPort personas = mock(PersonaRepositoryPort.class);
		given(personas.findByIdAndOrganizationId(PERSONA, ORG))
				.willReturn(Optional.of(mock(Persona.class)));
		servicio = new CoberturaParaOfertaService(personas,
				new CoberturasAplicablesService(coberturas, aranceles), ofertas, practicas,
				mock(com.akine.organization.spi.PermissionGuard.class));

		given(ofertas.find(ORG, SEDE, OFERTA)).willReturn(Optional.of(new OfertaSnapshot(
				OFERTA, ORG, SEDE, 5L, "Kinesio", 45, 1, false, true, false, false, true,
				LocalDate.of(2027, 1, 1), null, true)));
		precio(true);
		declarar(KINE);
		given(coberturas.activasDe(ORG, PERSONA)).willReturn(List.of(
				financiada(1L, 88L, 901L, true), financiada(2L, 89L, 902L, false)));
		given(aranceles.resolver(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), any(), any()))
				.willReturn(ResolucionDeArancel.sinArancel(MotivoSinArancel.SIN_CONVENIO_VIGENTE));
	}

	@Test
	@DisplayName("CA-M08-006-01: la cobertura con convenio aplica con su arancel; la otra no, con "
			+ "motivo; la condicion sugerida es COBERTURA")
	void aplica_una_y_la_otra_no() {
		convenio(88L, 901L, KINE, 5L);

		CoberturaParaOferta r = servicio.resolver(actor, PERSONA, OFERTA, HOY);

		assertThat(r.condicionSugerida()).isEqualTo(Condicion.COBERTURA);
		assertThat(r.aplicables()).singleElement().satisfies(a -> {
			assertThat(a.coberturaId()).isEqualTo(1L);
			assertThat(a.practicaId()).isEqualTo(KINE);
			assertThat(a.arancel().arancelId()).isEqualTo(5L);
		});
		assertThat(r.noAplicables()).singleElement().satisfies(n -> {
			assertThat(n.coberturaId()).isEqualTo(2L);
			assertThat(n.motivo()).isEqualTo(Motivo.SIN_CONVENIO_VIGENTE);
		});
		assertThat(r.precioParticular()).isEqualByComparingTo("8500");
	}

	@Test
	@DisplayName("CA-M08-006-06: oferta que no admite obra social -> ninguna aplica, PARTICULAR, y "
			+ "ni se consulta el convenio")
	void pilates_particular() {
		precio(false);

		CoberturaParaOferta r = servicio.resolver(actor, PERSONA, OFERTA, HOY);

		assertThat(r.condicionSugerida()).isEqualTo(Condicion.PARTICULAR);
		assertThat(r.aplicables()).isEmpty();
		assertThat(r.noAplicables()).extracting(CoberturaParaOferta.NoAplicable::motivo)
				.containsOnly(Motivo.OFERTA_NO_ADMITE_OBRA_SOCIAL);
		verifyNoInteractions(aranceles);
	}

	@Test
	@DisplayName("oferta sin practicas declaradas: no hay contra que resolver -> OFERTA_SIN_PRACTICAS")
	void oferta_sin_practicas() {
		declarar();

		CoberturaParaOferta r = servicio.resolver(actor, PERSONA, OFERTA, HOY);

		assertThat(r.noAplicables()).extracting(CoberturaParaOferta.NoAplicable::motivo)
				.containsOnly(Motivo.OFERTA_SIN_PRACTICAS);
		verifyNoInteractions(aranceles);
	}

	@Test
	@DisplayName("oferta con varias practicas: la principal primero; si no resuelve, aplica con la "
			+ "siguiente y el detalle explica las dos")
	void varias_practicas_principal_primero() {
		// La principal es FONO aunque KINE se declaro antes: el orden es DP-11, no el de alta.
		given(practicas.practicasHabilitadas(ORG, SEDE, OFERTA)).willReturn(List.of(
				new PracticaDeOferta(KINE, false), new PracticaDeOferta(FONO, true)));
		convenio(88L, 901L, KINE, 5L);
		given(aranceles.resolver(ORG, SEDE, 88L, 901L, FONO, OFERTA, HOY))
				.willReturn(ResolucionDeArancel.sinArancel(MotivoSinArancel.SIN_ARANCEL_VIGENTE));

		CoberturaParaOferta r = servicio.resolver(actor, PERSONA, OFERTA, HOY);

		assertThat(r.aplicables()).singleElement().satisfies(a -> {
			assertThat(a.practicaId()).isEqualTo(KINE);
			assertThat(a.practicas()).extracting(CoberturaParaOferta.Practica::practicaId)
					.containsExactly(FONO, KINE);
			assertThat(a.practicas().getFirst().motivo()).isEqualTo(Motivo.SIN_ARANCEL_VIGENTE);
		});
	}

	@Test
	@DisplayName("ninguna aplica -> PARTICULAR con el precio del dia; sin precio, null")
	void ninguna_aplica() {
		given(ofertas.precioVigenteEl(ORG, SEDE, OFERTA, HOY))
				.willReturn(Optional.of(new PrecioDeOferta(OFERTA, null, null, true)));

		CoberturaParaOferta r = servicio.resolver(actor, PERSONA, OFERTA, HOY);

		assertThat(r.condicionSugerida()).isEqualTo(Condicion.PARTICULAR);
		assertThat(r.precioParticular()).isNull();
		assertThat(r.moneda()).isNull();
	}

	@Test
	@DisplayName("la oferta se pasa al convenio: el arancel especifico de la oferta puede mandar")
	void pasa_la_oferta_al_convenio() {
		convenio(88L, 901L, KINE, 5L);

		servicio.resolver(actor, PERSONA, OFERTA, HOY);

		org.mockito.Mockito.verify(aranceles)
				.resolver(ORG, SEDE, 88L, 901L, KINE, OFERTA, HOY);
	}

	@Test
	@DisplayName("una oferta de otra sede o tenant es 404")
	void oferta_ajena() {
		given(ofertas.find(ORG, SEDE, OFERTA)).willReturn(Optional.empty());

		assertThatThrownBy(() -> servicio.resolver(actor, PERSONA, OFERTA, HOY))
				.isInstanceOf(OfertaNoAccesibleEnSedeException.class);
	}

	@Test
	@DisplayName("una persona de otra organizacion es 404")
	void persona_ajena() {
		assertThatThrownBy(() -> servicio.resolver(actor, 999L, OFERTA, HOY))
				.isInstanceOf(PersonaNotAccessibleException.class);
	}

	@Test
	@DisplayName("sin sede en el contexto es 403: el convenio es de la sede")
	void sin_sede() {
		assertThatThrownBy(() -> servicio.resolver(
				new OperatingActor(40L, false, ORG, null), PERSONA, OFERTA, HOY))
				.isInstanceOf(AccessDeniedException.class);
	}

	@Test
	@DisplayName("todo motivo del convenio tiene su par en la respuesta")
	void todos_los_motivos_mapean() {
		for (MotivoSinArancel motivo : MotivoSinArancel.values()) {
			assertThat(Motivo.valueOf(motivo.name())).isNotNull();
		}
	}

	// =================================================================================

	private void precio(boolean admiteObraSocial) {
		given(ofertas.precioVigenteEl(ORG, SEDE, OFERTA, HOY)).willReturn(Optional.of(
				new PrecioDeOferta(OFERTA, new BigDecimal("8500.00"), "ARS", admiteObraSocial)));
	}

	private void declarar(long... ids) {
		given(practicas.practicasHabilitadas(ORG, SEDE, OFERTA)).willReturn(Arrays.stream(ids)
				.mapToObj(id -> new PracticaDeOferta(id, id == ids[0])).toList());
	}

	private void convenio(long financiadorId, long planId, long practicaId, long arancelId) {
		given(aranceles.resolver(eq(ORG), eq(SEDE), eq(financiadorId), eq(planId), eq(practicaId),
				any(), any()))
				.willReturn(ResolucionDeArancel.resuelta(new ArancelVigente(
						12L, "CONV-1", "Convenio Sintetico", "POR_PRESTACION", financiadorId, planId,
						practicaId, arancelId, new BigDecimal("12000.00"),
						new BigDecimal("10000.00"), new BigDecimal("2000.00"), "ARS",
						false, false, false, null, LocalDate.of(2027, 1, 1), null,
						LocalDate.of(2027, 1, 1), null, HOY)));
	}

	private static CoberturaPaciente financiada(
			long id, long financiadorId, long planId, boolean principal) {
		CoberturaPaciente cobertura = CoberturaPaciente.financiada(
				ORG, PERSONA,
				new ReferenciaDeCobertura(
						financiadorId, "OS-" + financiadorId, "Financiador " + financiadorId,
						"PREPAGA", planId, "P-" + planId, "Plan " + planId, false, false,
						new BigDecimal("500.00"), "ARS", LocalDate.of(2027, 1, 1), Instant.now()),
				"AF-" + id, null, HOY.minusMonths(1), null, principal, null);
		ReflectionTestUtils.setField(cobertura, "id", id);
		return cobertura;
	}
}
