package com.akine.person.api.dto;

import com.akine.person.application.PersonaView;
import com.akine.person.application.ResumenDePersonaService.SeccionOmitida;
import com.akine.person.application.ResumenDePersonaView;
import com.akine.person.spi.AporteDeResumen;
import com.akine.person.spi.HitoDeResumen;
import com.akine.person.spi.IndicadorDeResumen;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El mapeo del Paciente 360 al borde HTTP.
 *
 * <p>Lo que se verifica no es "que copie campos" sino las dos propiedades que la pantalla
 * necesita: que <b>las secciones omitidas viajen</b> —sin eso, un recorte por permiso se lee como
 * un vacio de datos— y que el <b>importe conserve su escala decimal</b> al cruzar el borde.
 */
@DisplayName("ResumenDePersonaResponse")
class ResumenDePersonaResponseTest {

	@Test
	@DisplayName("mapea secciones, indicadores, hitos y omitidas sin perder nada")
	void mapea_el_resumen_completo() {
		Instant ocurrioEn = Instant.parse("2026-03-02T12:00:00Z");

		ResumenDePersonaView vista = new ResumenDePersonaView(
				unaPersona(),
				3L,
				Map.of("OTRO", 2L, "CONSENTIMIENTO", 1L),
				List.of(new AporteDeResumen(
						"economia",
						List.of(IndicadorDeResumen.dinero(
								"deuda-total", "Deuda", new BigDecimal("4500.00"), "ARS")),
						List.of(new HitoDeResumen(
								"economia", "OBLIGACION", ocurrioEn, "Sesion", "PENDIENTE", 9L)))),
				List.of(new SeccionOmitida("turnos", "turno:read")));

		ResumenDePersonaResponse respuesta = ResumenDePersonaResponse.from(vista);

		assertThat(respuesta.persona().id()).isEqualTo(500L);
		assertThat(respuesta.adjuntosTotal()).isEqualTo(3L);
		assertThat(respuesta.adjuntosPorCategoria()).containsEntry("OTRO", 2L);

		assertThat(respuesta.secciones()).singleElement().satisfies(seccion -> {
			assertThat(seccion.seccion()).isEqualTo("economia");
			assertThat(seccion.indicadores()).singleElement().satisfies(indicador -> {
				assertThat(indicador.clave()).isEqualTo("deuda-total");
				// La escala se conserva: "4500.00" y no 4500, que es lo que un double devolveria.
				assertThat(indicador.importe()).isEqualTo(new BigDecimal("4500.00"));
				assertThat(indicador.moneda()).isEqualTo("ARS");
				assertThat(indicador.cantidad()).isNull();
			});
			assertThat(seccion.hitos()).singleElement().satisfies(hito -> {
				assertThat(hito.tipo()).isEqualTo("OBLIGACION");
				assertThat(hito.ocurrioEn()).isEqualTo(ocurrioEn);
				assertThat(hito.referencia()).isEqualTo(9L);
			});
		});

		// Sin esto, la pantalla leeria "sin turnos" donde en realidad dice "no podes ver los
		// turnos", y alguien tomaria una decision sobre ese vacio.
		assertThat(respuesta.seccionesOmitidas()).singleElement().satisfies(omitida -> {
			assertThat(omitida.seccion()).isEqualTo("turnos");
			assertThat(omitida.permisoRequerido()).isEqualTo("turno:read");
		});
	}

	@Test
	@DisplayName("un resumen sin contribuyentes sigue siendo un resumen valido")
	void sin_secciones_sigue_siendo_valido() {
		ResumenDePersonaResponse respuesta = ResumenDePersonaResponse.from(
				new ResumenDePersonaView(unaPersona(), 0L, Map.of(), List.of(), List.of()));

		assertThat(respuesta.secciones()).isEmpty();
		assertThat(respuesta.seccionesOmitidas()).isEmpty();
		assertThat(respuesta.persona().esPaciente()).isFalse();
	}

	private static PersonaView unaPersona() {
		return new PersonaView(500L, "DNI", "27888999", "Perez", "Ana", null, null, null, null,
				false, null, null, "ACTIVO", null, null, 0L);
	}
}
