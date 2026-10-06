package com.akine.person.application;

import com.akine.contracting.spi.ReferenciaDeCobertura;
import com.akine.organization.spi.PermissionEvaluator;
import com.akine.person.domain.CoberturaPaciente;
import com.akine.person.domain.Persona;
import com.akine.person.domain.TipoDocumento;
import com.akine.person.domain.port.PersonRepositoryPorts.AdjuntoRepositoryPort;
import com.akine.person.domain.port.PersonRepositoryPorts.CoberturaPacienteRepositoryPort;
import com.akine.person.domain.port.PersonRepositoryPorts.PerfilPacienteRepositoryPort;
import com.akine.person.domain.port.PersonRepositoryPorts.PersonaRepositoryPort;
import com.akine.person.spi.AporteDeResumen;
import com.akine.person.spi.ConsultaDeResumen;
import com.akine.person.spi.HitoDeResumen;
import com.akine.person.spi.IndicadorDeResumen;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * Lo que la cobertura del paciente aporta al Paciente 360 (B-5).
 *
 * <p>Los casos que importan: solo las vigentes hoy, la principal primero, el afiliado enmascarado,
 * el aislamiento de tenant, y que un actor sin ningun permiso la ve igual —porque el modulo duenio
 * sirve las coberturas por pertenencia y el 360 no puede ser mas estricto que el—.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CoberturasEnElResumenDePersona")
class CoberturasEnElResumenDePersonaTest {

	private static final long ORG_ID = 10L;
	private static final long OTRA_ORG_ID = 11L;
	private static final long SEDE_ID = 20L;
	private static final long PERSONA_ID = 500L;
	private static final LocalDate HOY = LocalDate.of(2026, 10, 6);

	@Mock
	private CoberturaPacienteRepositoryPort coberturas;

	@Test
	@DisplayName("declara la seccion coberturas y ningun permiso: se lee por pertenencia")
	void declara_seccion_y_permiso() {
		assertThat(contribuyente().seccion()).isEqualTo("coberturas");
		assertThat(contribuyente().permisoRequerido()).isNull();
	}

	@Test
	@DisplayName("solo las vigentes hoy, la principal primero, con afiliado enmascarado")
	void solo_vigentes_principal_primero() {
		CoberturaPaciente secundaria = financiada(1L, "OSDE", "210", "123456789012",
				HOY.minusYears(1), null, false, null);
		CoberturaPaciente principal = financiada(2L, "Swiss Medical", "SMG20", "98765",
				HOY.minusMonths(2), HOY.plusMonths(3), true, null);
		CoberturaPaciente vencida = financiada(3L, "IOSFA", "Unico", "555566667777",
				HOY.minusYears(2), HOY.minusDays(1), false, null);
		CoberturaPaciente deBaja = financiada(4L, "PAMI", "Unico", "111122223333",
				HOY.minusYears(1), null, false, null);
		deBaja.deactivate(Instant.now(), "baja sintetica");
		given(coberturas.historial(ORG_ID, PERSONA_ID))
				.willReturn(List.of(secundaria, principal, vencida, deBaja));

		AporteDeResumen aporte = contribuyente().aportar(consulta(ORG_ID, 5));

		assertThat(aporte.seccion()).isEqualTo("coberturas");
		assertThat(indicador(aporte, "coberturas-vigentes").cantidad()).isEqualTo(2L);
		assertThat(indicador(aporte, "credenciales-vencidas").cantidad()).isZero();
		assertThat(aporte.hitos()).extracting(HitoDeResumen::referencia).containsExactly(2L, 1L);

		HitoDeResumen primero = aporte.hitos().get(0);
		assertThat(primero.origen()).isEqualTo("coberturas");
		assertThat(primero.tipo()).isEqualTo("COBERTURA_PRINCIPAL");
		assertThat(primero.estado()).isEqualTo("VIGENTE");
		assertThat(primero.ocurrioEn())
				.isEqualTo(HOY.minusMonths(2).atStartOfDay(ZoneOffset.UTC).toInstant());
		assertThat(primero.titulo())
				.isEqualTo("Swiss Medical · SMG20 · Afiliado ···8765 · hasta " + HOY.plusMonths(3));

		HitoDeResumen segundo = aporte.hitos().get(1);
		assertThat(segundo.tipo()).isEqualTo("COBERTURA");
		assertThat(segundo.titulo())
				.isEqualTo("OSDE · 210 · Afiliado ···9012 · sin vencimiento")
				.doesNotContain("12345678");
	}

	@Test
	@DisplayName("la credencial vencida no invalida la cobertura: se cuenta y se marca")
	void credencial_vencida_es_alerta() {
		given(coberturas.historial(ORG_ID, PERSONA_ID)).willReturn(List.of(
				financiada(1L, "OSDE", "210", "1234", HOY.minusYears(1), null, true,
						HOY.minusDays(3))));

		AporteDeResumen aporte = contribuyente().aportar(consulta(ORG_ID, 5));

		assertThat(indicador(aporte, "coberturas-vigentes").cantidad()).isEqualTo(1L);
		assertThat(indicador(aporte, "credenciales-vencidas").cantidad()).isEqualTo(1L);
		assertThat(aporte.hitos()).singleElement().satisfies(hito -> {
			assertThat(hito.estado()).isEqualTo("CREDENCIAL_VENCIDA");
			// Cuatro digitos o menos se tapan enteros: mostrarlos seria mostrarlos completos.
			assertThat(hito.titulo()).contains("Afiliado ···").doesNotContain("1234");
		});
	}

	@Test
	@DisplayName("la particular vigente aparece como Particular, sin afiliado")
	void particular() {
		CoberturaPaciente particular = CoberturaPaciente.particular(
				ORG_ID, PERSONA_ID, HOY.minusDays(10), null, true, null);
		ReflectionTestUtils.setField(particular, "id", 7L);
		given(coberturas.historial(ORG_ID, PERSONA_ID)).willReturn(List.of(particular));

		AporteDeResumen aporte = contribuyente().aportar(consulta(ORG_ID, 5));

		assertThat(aporte.hitos()).singleElement().satisfies(hito ->
				assertThat(hito.titulo()).isEqualTo("Particular · sin vencimiento"));
	}

	@Test
	@DisplayName("sin coberturas el aporte trae cero, no una seccion ausente")
	void sin_coberturas() {
		given(coberturas.historial(ORG_ID, PERSONA_ID)).willReturn(List.of());

		AporteDeResumen aporte = contribuyente().aportar(consulta(ORG_ID, 5));

		assertThat(indicador(aporte, "coberturas-vigentes").cantidad()).isZero();
		assertThat(aporte.hitos()).isEmpty();
	}

	@Test
	@DisplayName("respeta el limite de hitos pero cuenta todas las vigentes")
	void respeta_limite() {
		given(coberturas.historial(ORG_ID, PERSONA_ID)).willReturn(List.of(
				financiada(1L, "A", "1", null, HOY.minusDays(1), null, false, null),
				financiada(2L, "B", "2", null, HOY.minusDays(1), null, false, null),
				financiada(3L, "C", "3", null, HOY.minusDays(1), null, false, null)));

		AporteDeResumen aporte = contribuyente().aportar(consulta(ORG_ID, 2));

		assertThat(indicador(aporte, "coberturas-vigentes").cantidad()).isEqualTo(3L);
		assertThat(aporte.hitos()).hasSize(2);
	}

	@Test
	@DisplayName("otro tenant: consulta con SU organizacion y no ve las coberturas ajenas")
	void otro_tenant() {
		given(coberturas.historial(eq(ORG_ID), anyLong())).willReturn(List.of(
				financiada(1L, "OSDE", "210", "123456789", HOY.minusYears(1), null, true, null)));
		given(coberturas.historial(eq(OTRA_ORG_ID), anyLong())).willReturn(List.of());

		AporteDeResumen aporte = contribuyente().aportar(consulta(OTRA_ORG_ID, 5));

		assertThat(indicador(aporte, "coberturas-vigentes").cantidad()).isZero();
		assertThat(aporte.hitos()).isEmpty();
	}

	@Test
	@DisplayName("en el 360, un actor sin ningun permiso ve la seccion y no se declara omitida")
	void en_el_360_sin_permisos_no_se_omite(
			@Mock PersonaRepositoryPort personas,
			@Mock PerfilPacienteRepositoryPort perfiles,
			@Mock AdjuntoRepositoryPort adjuntos,
			@Mock PermissionEvaluator permissionEvaluator) {

		Persona persona = new Persona(ORG_ID, TipoDocumento.DNI, "27888999", "Perez", "Ana",
				null, null, null, null);
		ReflectionTestUtils.setField(persona, "id", PERSONA_ID);
		given(personas.findByIdAndOrganizationId(PERSONA_ID, ORG_ID))
				.willReturn(Optional.of(persona));
		given(perfiles.buscarVigente(ORG_ID, PERSONA_ID)).willReturn(Optional.empty());
		given(adjuntos.contarVigentesPorCategoria(ORG_ID, PERSONA_ID)).willReturn(List.of());
		given(permissionEvaluator.effectivePermissions(anyLong(), anyLong(), anyLong()))
				.willReturn(Set.of());
		given(coberturas.historial(ORG_ID, PERSONA_ID)).willReturn(List.of(
				financiada(1L, "OSDE", "210", "123456789", HOY.minusYears(1), null, true, null)));

		ResumenDePersonaView vista = new ResumenDePersonaService(
				personas, perfiles, adjuntos, permissionEvaluator, List.of(contribuyente()))
				.ver(new OperatingActor(40L, false, ORG_ID, SEDE_ID), PERSONA_ID);

		assertThat(vista.seccionesOmitidas()).isEmpty();
		assertThat(vista.secciones()).singleElement()
				.satisfies(seccion -> assertThat(seccion.seccion()).isEqualTo("coberturas"));
	}

	private CoberturasEnElResumenDePersona contribuyente() {
		return new CoberturasEnElResumenDePersona(
				coberturas, Clock.fixed(HOY.atTime(12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
	}

	private static ConsultaDeResumen consulta(long organizationId, int limite) {
		return new ConsultaDeResumen(organizationId, SEDE_ID, PERSONA_ID, limite);
	}

	private static CoberturaPaciente financiada(
			long id, String financiador, String plan, String afiliado, LocalDate desde,
			LocalDate hasta, boolean principal, LocalDate credencialHasta) {

		ReferenciaDeCobertura referencia = new ReferenciaDeCobertura(
				100L + id, "F" + id, financiador, "PREPAGA", 200L + id, "P" + id, plan,
				false, false, BigDecimal.ZERO, "ARS", desde, Instant.parse("2026-01-01T00:00:00Z"));
		CoberturaPaciente cobertura = CoberturaPaciente.financiada(
				ORG_ID, PERSONA_ID, referencia, afiliado, credencialHasta, desde, hasta, principal,
				null);
		ReflectionTestUtils.setField(cobertura, "id", id);
		return cobertura;
	}

	private static IndicadorDeResumen indicador(AporteDeResumen aporte, String clave) {
		return aporte.indicadores().stream()
				.filter(indicador -> indicador.clave().equals(clave))
				.findFirst()
				.orElseThrow();
	}
}
