package com.akine.organization;

import com.akine.TestcontainersConfiguration;
import com.akine.identity.application.AltaDeCentroPlataformaService;
import com.akine.organization.application.ContratacionService;
import com.akine.organization.application.ConsultorioAltaCommand;
import com.akine.organization.application.ConsultorioService;
import com.akine.organization.application.ContratacionView;
import com.akine.organization.application.OperatingActor;
import com.akine.organization.application.PedidoDeCambioDePlanService;
import com.akine.organization.application.PedidoDeCambioDePlanView;
import com.akine.organization.domain.PedidoDeCambioDePlan;
import com.akine.organization.domain.SubscriptionStatus;
import com.akine.organization.domain.exception.PedidoDePlanPendienteException;
import com.akine.organization.domain.exception.PedidoExcedeLimitesException;
import com.akine.organization.domain.exception.PedidoSinCambiosException;
import com.akine.organization.spi.LimitCode;
import com.akine.organization.spi.Adicional;
import com.akine.organization.spi.AltaDeCentro;
import com.akine.organization.spi.Servicio;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pedido de cambio de plan contra la base real (25/11/2026,
 * docs/diseno/AKINE-pedido-de-cambio-de-plan.md): pedir, aprobar aplica la contratacion,
 * rechazar exige motivo y un solo pendiente por centro aunque dos lleguen a la vez.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class PedidoDeCambioDePlanIT {

	/** El PLATFORM_ADMIN sembrado por las migraciones. */
	private static final long PLATAFORMA = 1L;
	private static final long ADMIN_DEL_CENTRO = 1L;

	@Autowired private PedidoDeCambioDePlanService pedidos;
	@Autowired private AltaDeCentroPlataformaService alta;
	@Autowired private ContratacionService contratacion;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private ConsultorioService consultorios;

	@Test
	@DisplayName("aprobar aplica plan y servicios pedidos, sigue en prueba y guarda lo que tenia")
	void aprobar_aplica_la_contratacion() {
		long org = altaEnPrueba();
		PedidoDeCambioDePlanView pedido = pedidos.pedir(org, "CRECIMIENTO",
				EnumSet.of(Servicio.KINESIOLOGIA, Servicio.ACTIVIDADES), EnumSet.of(Adicional.IA),
				"  Sumamos yoga  ", ADMIN_DEL_CENTRO);

		assertThat(pedido.estado()).isEqualTo(PedidoDeCambioDePlan.Estado.PENDIENTE);
		assertThat(pedido.planCodeAnterior()).isEqualTo("BASE");
		assertThat(pedido.serviciosAnteriores()).containsExactly(Servicio.KINESIOLOGIA);
		assertThat(pedido.planNombre()).isEqualTo("Plan Intermedio");
		assertThat(pedido.comentario()).isEqualTo("Sumamos yoga");
		assertThat(pedidos.ultimo(org)).get().extracting(PedidoDeCambioDePlanView::id).isEqualTo(pedido.id());
		assertThat(pedidos.listar(PedidoDeCambioDePlan.Estado.PENDIENTE, "Centro Pedido", PageRequest.of(0, 50))
				.map(PedidoDeCambioDePlanView::id).toList()).contains(pedido.id());

		PedidoDeCambioDePlanView aprobado = pedidos.aprobar(pedido.id(), pedido.version(), PLATAFORMA);

		assertThat(aprobado.estado()).isEqualTo(PedidoDeCambioDePlan.Estado.APROBADO);
		ContratacionView vista = contratacion.ver(org);
		assertThat(vista.planCode()).isEqualTo("CRECIMIENTO");
		assertThat(vista.servicios()).containsExactlyInAnyOrder(Servicio.KINESIOLOGIA, Servicio.ACTIVIDADES);
		assertThat(vista.adicionales()).containsExactly(Adicional.IA);
		assertThat(vista.estado()).as("aprobar no contrata la prueba").isEqualTo(SubscriptionStatus.PRUEBA);
		assertThatThrownBy(() -> pedidos.aprobar(pedido.id(), aprobado.version(), PLATAFORMA))
				.as("ya resuelto")
				.isInstanceOf(OptimisticLockingFailureException.class);
	}

	@Test
	@DisplayName("rechazar deja el motivo sin tocar la contratacion; despues se puede pedir de nuevo")
	void rechazar_y_volver_a_pedir() {
		long org = altaEnPrueba();
		PedidoDeCambioDePlanView pedido = pedidos.pedir(org, "RED", EnumSet.of(Servicio.KINESIOLOGIA),
				EnumSet.noneOf(Adicional.class), null, ADMIN_DEL_CENTRO);

		assertThatThrownBy(() -> pedidos.rechazar(pedido.id(), "  ", pedido.version(), PLATAFORMA))
				.isInstanceOf(IllegalArgumentException.class);
		PedidoDeCambioDePlanView rechazado = pedidos.rechazar(pedido.id(), "Requiere convenio anual",
				pedido.version(), PLATAFORMA);

		assertThat(rechazado.motivoRechazo()).isEqualTo("Requiere convenio anual");
		assertThat(contratacion.ver(org).planCode()).isEqualTo("BASE");
		assertThat(pedidos.pedir(org, "CRECIMIENTO", EnumSet.of(Servicio.KINESIOLOGIA),
				EnumSet.noneOf(Adicional.class), null, ADMIN_DEL_CENTRO).estado())
				.isEqualTo(PedidoDeCambioDePlan.Estado.PENDIENTE);
	}

	@Test
	@DisplayName("igual a lo contratado es 400; con uno pendiente, 409; cancelar lo libera")
	void sin_cambios_pendiente_y_cancelar() {
		long org = altaEnPrueba();
		assertThatThrownBy(() -> pedidos.pedir(org, "BASE", EnumSet.of(Servicio.KINESIOLOGIA),
				EnumSet.noneOf(Adicional.class), null, ADMIN_DEL_CENTRO))
				.isInstanceOf(PedidoSinCambiosException.class);

		PedidoDeCambioDePlanView pedido = pedidos.pedir(org, "CRECIMIENTO", EnumSet.of(Servicio.KINESIOLOGIA),
				EnumSet.noneOf(Adicional.class), null, ADMIN_DEL_CENTRO);
		assertThatThrownBy(() -> pedidos.pedir(org, "RED", EnumSet.of(Servicio.KINESIOLOGIA),
				EnumSet.noneOf(Adicional.class), null, ADMIN_DEL_CENTRO))
				.isInstanceOf(PedidoDePlanPendienteException.class);

		PedidoDeCambioDePlanView cancelado = pedidos.cancelar(org, pedido.id(), pedido.version(), ADMIN_DEL_CENTRO);
		assertThat(cancelado.estado()).isEqualTo(PedidoDeCambioDePlan.Estado.CANCELADO);
		assertThatThrownBy(() -> pedidos.aprobar(pedido.id(), cancelado.version(), PLATAFORMA))
				.as("cancelado no se aprueba")
				.isInstanceOf(OptimisticLockingFailureException.class);
	}

	@Test
	@DisplayName("dos pedidos simultaneos del mismo centro: entra uno y el otro recibe pendiente, nunca 500")
	void dos_pedidos_a_la_vez() throws Exception {
		long org = altaEnPrueba();
		int hilos = 4;
		CountDownLatch largada = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(hilos);
		try {
			List<Future<String>> resultados = new ArrayList<>();
			for (int i = 0; i < hilos; i++) {
				Callable<String> intento = () -> {
					largada.await();
					try {
						pedidos.pedir(org, "CRECIMIENTO", EnumSet.of(Servicio.KINESIOLOGIA),
								EnumSet.noneOf(Adicional.class), null, ADMIN_DEL_CENTRO);
						return "ok";
					} catch (PedidoDePlanPendienteException pendiente) {
						return "pendiente";
					}
				};
				resultados.add(pool.submit(intento));
			}
			largada.countDown();
			List<String> salidas = new ArrayList<>();
			for (Future<String> r : resultados) {
				salidas.add(r.get());
			}
			assertThat(salidas).containsOnlyOnce("ok").hasSize(hilos);
		} finally {
			pool.shutdownNow();
		}
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM pedido_de_cambio_de_plan WHERE organization_id = ? AND estado = 'PENDIENTE'""",
				Integer.class, org)).isEqualTo(1);
	}

	@Test
	@DisplayName("bajar con mas sedes que el tope: el pedido se guarda con el exceso y no se aprueba hasta ajustarse")
	void bajar_de_plan_con_exceso() {
		long org = altaEnPrueba();
		PedidoDeCambioDePlanView subida = pedidos.pedir(org, "CRECIMIENTO", EnumSet.of(Servicio.KINESIOLOGIA),
				EnumSet.noneOf(Adicional.class), null, ADMIN_DEL_CENTRO);
		pedidos.aprobar(subida.id(), subida.version(), PLATAFORMA);
		consultorios.create(new OperatingActor(PLATAFORMA, true, null), org, new ConsultorioAltaCommand(
				"Sede Segunda", null, null, null, null, null, null, null, UUID.randomUUID().toString(), "h"));

		PedidoDeCambioDePlanView bajada = pedidos.pedir(org, "BASE", EnumSet.of(Servicio.KINESIOLOGIA),
				EnumSet.noneOf(Adicional.class), null, ADMIN_DEL_CENTRO);

		assertThat(bajada.excesos()).singleElement().satisfies(e -> {
			assertThat(e.limitCode()).isEqualTo(LimitCode.MAX_CONSULTORIOS);
			assertThat(e.tope()).isEqualTo(1);
			assertThat(e.uso()).isEqualTo(2);
		});
		assertThatThrownBy(() -> pedidos.aprobar(bajada.id(), bajada.version(), PLATAFORMA))
				.isInstanceOf(PedidoExcedeLimitesException.class);
		assertThat(contratacion.ver(org).planCode()).isEqualTo("CRECIMIENTO");
	}

	@Test
	@DisplayName("dejar un servicio lo pasa a solo lectura; volver a sumarlo lo saca")
	void dejar_un_servicio_queda_en_lectura() {
		long org = altaEnPrueba();
		PedidoDeCambioDePlanView suma = pedidos.pedir(org, "BASE", EnumSet.allOf(Servicio.class),
				EnumSet.noneOf(Adicional.class), null, ADMIN_DEL_CENTRO);
		pedidos.aprobar(suma.id(), suma.version(), PLATAFORMA);

		PedidoDeCambioDePlanView deja = pedidos.pedir(org, "BASE", EnumSet.of(Servicio.KINESIOLOGIA),
				EnumSet.noneOf(Adicional.class), null, ADMIN_DEL_CENTRO);
		pedidos.aprobar(deja.id(), deja.version(), PLATAFORMA);
		assertThat(contratacion.ver(org).serviciosEnLectura()).containsExactly(Servicio.ACTIVIDADES);

		PedidoDeCambioDePlanView vuelve = pedidos.pedir(org, "BASE", EnumSet.allOf(Servicio.class),
				EnumSet.noneOf(Adicional.class), null, ADMIN_DEL_CENTRO);
		pedidos.aprobar(vuelve.id(), vuelve.version(), PLATAFORMA);
		assertThat(contratacion.ver(org).serviciosEnLectura()).isEmpty();
	}

	private long altaEnPrueba() {
		String email = "pedido-" + UUID.randomUUID().toString().substring(0, 8) + "@centro.test";
		return alta.darDeAlta(new AltaDeCentro.Comando("Centro Pedido " + UUID.randomUUID().toString().substring(0, 6),
				null, null, "BASE", EnumSet.of(Servicio.KINESIOLOGIA), EnumSet.noneOf(Adicional.class), true,
				null, PLATAFORMA), email).organizationId();
	}
}
