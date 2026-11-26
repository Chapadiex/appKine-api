package com.akine.platform.infrastructure.modelo.agente;

import static org.assertj.core.api.Assertions.assertThat;

import com.akine.platform.spi.modelo.agente.LlamadaAHerramienta;
import com.akine.platform.spi.modelo.agente.MensajeDelAgente;
import com.akine.platform.spi.modelo.agente.PedidoAlAgente;
import com.akine.platform.spi.modelo.agente.TurnoDelAgente;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProveedorDeAgenteDePruebaTest {

	@Test
	void devuelveLaLlamadaPrimeroYElTextoDespues() {
		var llamada = new TurnoDelAgente(null, List.of(new LlamadaAHerramienta("1", "turnos_del_dia", "{}")), null, null);
		var texto = new TurnoDelAgente("Hay 3 turnos", List.of(), null, null);
		var proveedor = new ProveedorDeAgenteDePrueba(Map.of("turnos", List.of(llamada, texto)));
		var pedido = new PedidoAlAgente("s", List.of(),
				List.of(new MensajeDelAgente.DelUsuario("Turnos de hoy?")), Duration.ofSeconds(5));

		var primero = proveedor.responder(pedido);
		var segundo = proveedor.responder(pedido);

		assertThat(primero.terminado()).isFalse();
		assertThat(primero.llamadas()).hasSize(1);
		assertThat(segundo.terminado()).isTrue();
		assertThat(segundo.texto()).isEqualTo("Hay 3 turnos");
	}

	@Test
	void sinGuionResponde() {
		var proveedor = new ProveedorDeAgenteDePrueba(Map.of());
		var pedido = new PedidoAlAgente("s", List.of(),
				List.of(new MensajeDelAgente.DelUsuario("hola")), Duration.ofSeconds(1));
		assertThat(proveedor.responder(pedido).texto()).isEqualTo("Sin guion");
	}
}
