package com.akine.assistant.application;

import com.akine.assistant.application.RespuestaDelAsistente.Resultado;
import com.akine.assistant.domain.AsistenteAjustes;
import com.akine.assistant.spi.ActorDelAsistente;
import com.akine.assistant.spi.ContextoDeConsulta;
import com.akine.assistant.spi.HerramientaDelAgente;
import com.akine.assistant.spi.PedidoDeAccion;
import com.akine.assistant.spi.ResultadoDeHerramienta;
import com.akine.organization.spi.PermissionEvaluator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@code proponer_accion} con el nombre dicho y sin {@code persona_id}: lo resuelve buscar_persona. */
class ProponerAccionHerramientaTest {

	private static final ActorDelAsistente ACTOR = new ActorDelAsistente(11L, false, 7L, 3L, "ADMINISTRATIVO");
	private static final ContextoDeConsulta CTX = new ContextoDeConsulta(LocalDate.of(2026, 11, 23),
			ZoneId.of("America/Argentina/Cordoba"), null);

	private static HerramientaDelAgente herramienta(String nombre, List<Map<String, Object>> filas) {
		return new HerramientaDelAgente() {
			@Override
			public String nombre() {
				return nombre;
			}

			@Override
			public String descripcion() {
				return nombre;
			}

			@Override
			public String esquemaEntrada() {
				return "{\"type\":\"object\"}";
			}

			@Override
			public ResultadoDeHerramienta ejecutar(ActorDelAsistente actor, Map<String, Object> entrada,
					ContextoDeConsulta ctx) {
				return new ResultadoDeHerramienta(filas, List.of(), null, false);
			}
		};
	}

	@Test
	@SuppressWarnings("unchecked")
	void un_nombre_con_una_sola_coincidencia_arma_la_tarjeta_de_esa_persona() {
		AccionesDelAsistente acciones = mock(AccionesDelAsistente.class);
		when(acciones.proponerPedido(any(), any(), any())).thenReturn(new RespuestaDelAsistente(
				AccionesDelAsistente.TEXTO_PROPUESTA, Interpretacion.FUERA_DE_ALCANCE, List.of(), List.of(), null,
				List.of(), Resultado.PROPUESTA));
		Map<String, Object> pedro = Map.of("persona_id", 42L, "nombre", "Quiroga, Pedro");
		ObjectProvider<CatalogoDeHerramientas> proveedor = mock(ObjectProvider.class);
		when(proveedor.getIfAvailable()).thenReturn(new CatalogoDeHerramientas(List.of(
				herramienta(ProponerAccionHerramienta.BUSCAR_PERSONA, List.of(pedro)),
				herramienta(ProponerAccionHerramienta.DATOS_PERSONA, List.of(pedro)))));
		ProponerAccionHerramienta proponer = new ProponerAccionHerramienta(
				new AsistenteAjustes(true, Duration.ofSeconds(5), false, true, Duration.ofMinutes(15)),
				mock(PermissionEvaluator.class), acciones, proveedor);

		RespuestaDelAsistente r = proponer.proponer(ACTOR, CTX, "Pedro Quiroga pagó 15.000 en efectivo",
				Map.of("tipo", "PAGO", "persona", "Pedro Quiroga", "monto", 15000, "medio", "EFECTIVO"));

		assertThat(r.resultado()).isEqualTo(Resultado.PROPUESTA);
		ArgumentCaptor<PedidoDeAccion> pedido = ArgumentCaptor.forClass(PedidoDeAccion.class);
		verify(acciones).proponerPedido(eq(ACTOR), eq(CTX.zona()), pedido.capture());
		assertThat(pedido.getValue().personaId()).isEqualTo(42L);
		assertThat(pedido.getValue().personaNombre()).isEqualTo("Quiroga, Pedro");
	}
}
