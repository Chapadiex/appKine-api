package com.akine.organization.domain;

import com.akine.organization.spi.Adicional;
import com.akine.organization.spi.Servicio;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Reglas del pedido de cambio de plan que no necesitan base (26/11/2026). */
class PedidoDeCambioDePlanTest {

	private static final Instant AHORA = Instant.parse("2026-11-26T12:00:00Z");

	private static PedidoDeCambioDePlan pedido() {
		return new PedidoDeCambioDePlan(7, "CRECIMIENTO", Set.of(Servicio.ACTIVIDADES), Set.of(),
				"BASE", Set.of(Servicio.KINESIOLOGIA), Set.of(Adicional.IA), "Sumamos yoga", 3);
	}

	@Test
	@DisplayName("Guarda lo pedido y lo que el centro tenia al pedir")
	void guarda_lo_pedido_y_lo_anterior() {
		PedidoDeCambioDePlan p = pedido();

		assertThat(p.getEstado()).isEqualTo(PedidoDeCambioDePlan.Estado.PENDIENTE);
		assertThat(p.getServicios()).containsExactly(Servicio.ACTIVIDADES);
		assertThat(p.getAdicionales()).isEmpty();
		assertThat(p.getServiciosAnteriores()).containsExactly(Servicio.KINESIOLOGIA);
		assertThat(p.getAdicionalesAnteriores()).containsExactly(Adicional.IA);
		assertThat(p.getPedidoPor()).isEqualTo(3);
		assertThat(p.getResueltoPor()).isNull();
	}

	@Test
	@DisplayName("Sin servicios no hay pedido")
	void sin_servicios() {
		assertThatThrownBy(() -> new PedidoDeCambioDePlan(7, "BASE", Set.of(), Set.of(), "BASE",
				Set.of(Servicio.KINESIOLOGIA), Set.of(), null, 3))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("Rechazar exige motivo y lo guarda sin espacios sobrantes")
	void rechazar_con_motivo() {
		PedidoDeCambioDePlan p = pedido();

		assertThatThrownBy(() -> p.rechazar("  ", 1, AHORA)).isInstanceOf(IllegalArgumentException.class);

		p.rechazar("  Falta el pago ", 1, AHORA);
		assertThat(p.getEstado()).isEqualTo(PedidoDeCambioDePlan.Estado.RECHAZADO);
		assertThat(p.getMotivoRechazo()).isEqualTo("Falta el pago");
		assertThat(p.getResueltoPor()).isEqualTo(1L);
		assertThat(p.getResueltoEn()).isEqualTo(AHORA);
	}

	@Test
	@DisplayName("Un pedido resuelto no se vuelve a resolver")
	void resuelto_no_se_resuelve_otra_vez() {
		PedidoDeCambioDePlan p = pedido();
		p.cancelar(3, AHORA);

		assertThatThrownBy(() -> p.aprobar(1, AHORA)).isInstanceOf(IllegalStateException.class);
		assertThat(p.getEstado()).isEqualTo(PedidoDeCambioDePlan.Estado.CANCELADO);
	}
}
