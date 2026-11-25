package com.akine.billing.infrastructure;

import com.akine.assistant.spi.ActorDelAsistente;
import com.akine.assistant.spi.ContextoDeConsulta;
import com.akine.assistant.spi.ResultadoDeHerramienta;
import com.akine.billing.application.Cotizacion;
import com.akine.billing.application.CotizadorDeAtencion;
import com.akine.contracting.spi.ArancelDirectory;
import com.akine.contracting.spi.ArancelVigente;
import com.akine.contracting.spi.CoberturaCatalogoDirectory;
import com.akine.contracting.spi.ConvenioDeLaSede;
import com.akine.contracting.spi.MotivoSinArancel;
import com.akine.contracting.spi.PlanCoberturaSnapshot;
import com.akine.contracting.spi.ResolucionDeArancel;
import com.akine.offering.spi.OfertaDirectory;
import com.akine.offering.spi.OfertaSnapshot;
import com.akine.offering.spi.PracticaDeOferta;
import com.akine.offering.spi.PracticasDeOfertaDirectory;
import com.akine.offering.spi.PrecioDeOferta;
import com.akine.organization.spi.PermissionGuard;
import com.akine.person.spi.PacienteDirectory;
import com.akine.person.spi.PacienteSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ArancelEnElAsistenteTest {

	private static final long ORG = 7L;
	private static final long SEDE = 3L;
	private static final LocalDate HOY = LocalDate.of(2026, 11, 23);
	private static final ActorDelAsistente ACTOR = new ActorDelAsistente(11L, false, ORG, SEDE, "ADMINISTRATIVO");
	private static final ContextoDeConsulta CTX = new ContextoDeConsulta(HOY,
			ZoneId.of("America/Argentina/Cordoba"), null);

	private final CotizadorDeAtencion cotizador = mock(CotizadorDeAtencion.class);
	private final OfertaDirectory ofertas = mock(OfertaDirectory.class);
	private final PracticasDeOfertaDirectory practicas = mock(PracticasDeOfertaDirectory.class);
	private final ArancelDirectory aranceles = mock(ArancelDirectory.class);
	private final CoberturaCatalogoDirectory catalogo = mock(CoberturaCatalogoDirectory.class);
	private final PacienteDirectory pacientes = mock(PacienteDirectory.class);
	private final PermissionGuard permisos = mock(PermissionGuard.class);
	private final ArancelEnElAsistente herramienta = new ArancelEnElAsistente(cotizador, ofertas, practicas,
			aranceles, catalogo, pacientes, permisos);

	private static OfertaSnapshot oferta(long id, String nombre) {
		return new OfertaSnapshot(id, ORG, SEDE, 1L, nombre, 45, 1, false, true, false, false, true,
				LocalDate.of(2020, 1, 1), null, true);
	}

	private static ArancelVigente arancel(long convenioId, long planId, String importe) {
		return new ArancelVigente(convenioId, "C" + convenioId, "Convenio " + convenioId, "POR_PRESTACION", 50L,
				planId, 900L, 1L, new BigDecimal(importe), new BigDecimal(importe), BigDecimal.ZERO, "ARS", false,
				false, false, 8, HOY, null, HOY, null, HOY, 42L);
	}

	private void conKinesiologia(BigDecimal precio, boolean admite) {
		when(ofertas.activasDeLaSede(ORG, SEDE)).thenReturn(List.of(oferta(42L, "Kinesiología"),
				oferta(43L, "Kinesiología deportiva"), oferta(44L, "Yoga")));
		when(ofertas.find(ORG, SEDE, 42L)).thenReturn(Optional.of(oferta(42L, "Kinesiología")));
		when(ofertas.precioVigenteEl(ORG, SEDE, 42L, HOY)).thenReturn(Optional.of(
				new PrecioDeOferta(42L, precio, "ARS", admite)));
	}

	@Test
	void describe_la_herramienta() {
		assertThat(herramienta.nombre()).isEqualTo("arancel_vigente");
		assertThat(herramienta.descripcion()).isNotBlank();
		assertThat(herramienta.esquemaEntrada()).contains("oferta_id");
	}

	@Test
	void sin_persona_da_el_particular_y_un_plan_por_convenio_con_coseguro() {
		conKinesiologia(new BigDecimal("10000"), true);
		when(practicas.practicasHabilitadas(ORG, SEDE, 42L)).thenReturn(List.of(new PracticaDeOferta(900L, true)));
		when(aranceles.conveniosActivos(ORG, SEDE)).thenReturn(List.of(
				new ConvenioDeLaSede(1L, "C1", "Sede", 50L, 60L, false, null),
				new ConvenioDeLaSede(2L, "C2", "Plan", 50L, 60L, true, null),
				new ConvenioDeLaSede(3L, "C3", "Otro", 50L, 61L, true, null)));
		when(aranceles.resolver(ORG, SEDE, 50L, 60L, 900L, 42L, HOY))
				.thenReturn(ResolucionDeArancel.resuelta(arancel(1L, 60L, "7000")));
		when(aranceles.resolver(ORG, SEDE, 50L, 61L, 900L, 42L, HOY))
				.thenReturn(ResolucionDeArancel.sinArancel(MotivoSinArancel.SIN_CONVENIO_VIGENTE));
		when(catalogo.findPlan(ORG, 60L)).thenReturn(Optional.of(new PlanCoberturaSnapshot(60L, ORG, 50L, "OSDE",
				true, "210", "OSDE 210", HOY, null, false, false, BigDecimal.ZERO, "ARS", true)));

		ResultadoDeHerramienta r = herramienta.ejecutar(ACTOR, Map.of("oferta", "kinesiologia"), CTX);

		assertThat(r.filas()).extracting(f -> f.get("fila")).containsExactly("PARTICULAR", "PLAN");
		assertThat(r.filas().get(1)).containsEntry("plan", "OSDE 210").containsEntry("alcance_convenio", "SEDE")
				.containsEntry("coseguro", new BigDecimal("3000"));
		assertThat(r.advertencias()).singleElement().asString().startsWith("1 plan(es)");
		assertThat(r.enlace()).isEqualTo("/servicios/ofertas/42/precios");
	}

	@Test
	void corta_en_el_tope_contando_la_fila_particular() {
		conKinesiologia(new BigDecimal("10000"), true);
		when(practicas.practicasHabilitadas(ORG, SEDE, 42L)).thenReturn(List.of(new PracticaDeOferta(900L, true)));
		List<ConvenioDeLaSede> convenios = new ArrayList<>();
		for (long plan = 1; plan <= 55; plan++) {
			convenios.add(new ConvenioDeLaSede(plan, "C", "C", 50L, plan, false, null));
			when(aranceles.resolver(ORG, SEDE, 50L, plan, 900L, 42L, HOY))
					.thenReturn(ResolucionDeArancel.resuelta(arancel(plan, plan, "100")));
		}
		when(aranceles.conveniosActivos(ORG, SEDE)).thenReturn(convenios);
		when(catalogo.findPlan(eq(ORG), anyLong())).thenReturn(Optional.empty());

		ResultadoDeHerramienta r = herramienta.ejecutar(ACTOR, Map.of("oferta_id", 42), CTX);

		assertThat(r.filas()).hasSize(ArancelEnElAsistente.TOPE);
		assertThat(r.advertencias()).contains("Hay 56 resultados; muestro los primeros 50");
	}

	@Test
	void sin_precio_ni_obra_social_avisa_las_dos_cosas() {
		conKinesiologia(null, false);

		ResultadoDeHerramienta r = herramienta.ejecutar(ACTOR, Map.of("oferta", "Kinesiología"), CTX);

		assertThat(r.filas()).singleElement().satisfies(f -> assertThat(f).containsEntry("precio_particular", null));
		assertThat(r.advertencias()).hasSize(2);
	}

	@Test
	void sin_practica_principal_no_se_factura_a_obra_social() {
		conKinesiologia(new BigDecimal("10000"), true);
		when(practicas.practicasHabilitadas(ORG, SEDE, 42L)).thenReturn(List.of(new PracticaDeOferta(900L, false)));

		ResultadoDeHerramienta r = herramienta.ejecutar(ACTOR, Map.of("oferta", "Kinesiología"), CTX);

		assertThat(r.advertencias()).singleElement().asString().contains("práctica principal");
	}

	@Test
	void con_persona_devuelve_la_cotizacion() {
		when(ofertas.find(ORG, SEDE, 42L)).thenReturn(Optional.of(oferta(42L, "Kinesiología")));
		when(pacientes.find(ORG, 20L)).thenReturn(Optional.of(mock(PacienteSnapshot.class)));
		when(cotizador.cotizar(ORG, SEDE, 20L, 42L, null, HOY)).thenReturn(new Cotizacion(42L, "Kinesiología",
				new BigDecimal("10000"), "ARS", Cotizacion.COBERTURA, null, 5L, "OSDE", "OSDE 210", "Convenio",
				"PLAN", new BigDecimal("7000"), new BigDecimal("3000"), new BigDecimal("3000"), 8, 2, HOY, null,
				List.of("paso")));

		ResultadoDeHerramienta r = herramienta.ejecutar(ACTOR, Map.of("oferta_id", 42, "persona_id", 20), CTX);

		assertThat(r.filas()).singleElement().satisfies(f -> {
			assertThat(f).containsEntry("fila", "COTIZACION").containsEntry("a_cobrar_al_paciente",
					new BigDecimal("3000"));
			assertThat((List<?>) f.get("donde_se_carga")).hasSize(2);
		});
	}

	@Test
	void con_persona_ajena_no_la_encuentra() {
		when(ofertas.find(ORG, SEDE, 42L)).thenReturn(Optional.of(oferta(42L, "Kinesiología")));
		when(pacientes.find(ORG, 20L)).thenReturn(Optional.empty());

		assertThat(herramienta.ejecutar(ACTOR, Map.of("oferta_id", 42, "persona_id", 20), CTX).advertencias())
				.containsExactly("No encontré esa persona");
	}

	@Test
	void la_actividad_ambigua_inexistente_o_ausente_es_un_error_legible() {
		conKinesiologia(new BigDecimal("1"), false);

		assertThatThrownBy(() -> herramienta.ejecutar(ACTOR, Map.of("oferta", "kine"), CTX))
				.hasMessageContaining("Hay varias actividades");
		assertThatThrownBy(() -> herramienta.ejecutar(ACTOR, Map.of("oferta", "zumba"), CTX))
				.hasMessageContaining("No encontré la actividad 'zumba'");
		assertThatThrownBy(() -> herramienta.ejecutar(ACTOR, Map.of(), CTX))
				.hasMessageContaining("Falta la actividad");
		when(ofertas.find(ORG, SEDE, 99L)).thenReturn(Optional.empty());
		assertThatThrownBy(() -> herramienta.ejecutar(ACTOR, Map.of("oferta_id", 99), CTX))
				.hasMessageContaining("99");
	}

	@Test
	void sin_permiso_o_sin_sede_rechaza() {
		when(permisos.requirePermission(any())).thenThrow(new AccessDeniedException("sin convenio:read"));
		assertThatThrownBy(() -> herramienta.ejecutar(ACTOR, Map.of("oferta_id", 42), CTX))
				.isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> herramienta.ejecutar(new ActorDelAsistente(11L, false, ORG, null, "X"),
				Map.of("oferta_id", 42), CTX)).isInstanceOf(AccessDeniedException.class);
	}
}
