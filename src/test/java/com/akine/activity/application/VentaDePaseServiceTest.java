package com.akine.activity.application;

import com.akine.activity.domain.MovimientoPase;
import com.akine.activity.domain.PaseServicio;
import com.akine.activity.domain.ProductoServicio;
import com.akine.activity.domain.TipoMovimientoPase;
import com.akine.activity.domain.exception.PersonaDadaDeBajaException;
import com.akine.activity.domain.exception.ProductoNoVendibleException;
import com.akine.activity.domain.port.ActivityRepositoryPorts.MovimientoPaseRepositoryPort;
import com.akine.activity.domain.port.ActivityRepositoryPorts.PaseServicioRepositoryPort;
import com.akine.activity.domain.port.ActivityRepositoryPorts.ProductoServicioRepositoryPort;
import com.akine.billing.spi.DevengoDeVentas;
import com.akine.billing.spi.DevengoDeVentas.DeudaDeVenta;
import com.akine.billing.spi.DevengoDeVentas.VentaDePase;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionGuard;
import com.akine.person.spi.PacienteDirectory;
import com.akine.person.spi.PacienteSnapshot;
import com.akine.platform.spi.audit.AuditTrail;
import com.akine.platform.spi.identity.AccountIdentity;
import com.akine.platform.spi.identity.AccountIdentityDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("VentaDePaseService")
class VentaDePaseServiceTest {

	private static final long ORG = 1L;
	private static final long SEDE = 10L;
	private static final long PRODUCTO = 12L;
	private static final long PERSONA = 512L;
	private static final OperatingActor ACTOR = new OperatingActor(99L, false, ORG, SEDE);
	private static final Clock RELOJ = Clock.fixed(Instant.parse("2026-10-14T15:00:00Z"), ZoneOffset.UTC);

	@Mock private PaseServicioRepositoryPort pases;
	@Mock private ProductoServicioRepositoryPort productos;
	@Mock private MovimientoPaseRepositoryPort movimientos;
	@Mock private DevengoDeVentas devengo;
	@Mock private PacienteDirectory personas;
	@Mock private ConsultorioDirectory consultorios;
	@Mock private PermissionGuard permissionGuard;
	@Mock private AuditTrail auditTrail;
	@Mock private AccountIdentityDirectory identidades;

	private VentaDePaseService service;

	@BeforeEach
	void setUp() {
		service = new VentaDePaseService(pases, productos, movimientos, devengo, personas, consultorios,
				permissionGuard, auditTrail, new LecturaDePases(pases, movimientos, devengo, identidades), RELOJ);
		given(consultorios.find(ORG, SEDE)).willReturn(Optional.of(
				new ConsultorioSnapshot(SEDE, ORG, "Sede", "UTC", true)));
		given(productos.lockByIdInScope(ORG, SEDE, PRODUCTO)).willReturn(Optional.of(producto()));
		given(personas.find(ORG, PERSONA)).willReturn(Optional.of(persona(true)));
		given(pases.saveAndFlush(any())).willAnswer(llamada -> {
			PaseServicio pase = llamada.getArgument(0);
			ReflectionTestUtils.setField(pase, "id", 77L);
			return pase;
		});
		given(devengo.devengarPorVentaDePase(any())).willReturn(
				new DeudaDeVenta(9001L, new BigDecimal("40000.00"), new BigDecimal("40000.00"), "ARS",
						"PENDIENTE", true));
	}

	@Test
	@DisplayName("La venta crea el pase con snapshot, el movimiento COMPRA y una obligacion por el precio congelado")
	void venta() {
		ResultadoDeVenta venta = service.vender(ACTOR, SEDE, new VenderPaseCommand(PRODUCTO, PERSONA, "k-1"));

		assertThat(venta.creada()).isTrue();
		assertThat(venta.pase().creditosDisponibles()).isEqualTo(8);
		assertThat(venta.pase().vigenciaDesde()).isEqualTo(LocalDate.of(2026, 10, 14));
		assertThat(venta.pase().vigenciaHasta()).isEqualTo(LocalDate.of(2026, 12, 12));
		assertThat(venta.deuda().obligacionId()).isEqualTo(9001L);

		ArgumentCaptor<MovimientoPase> movimiento = ArgumentCaptor.forClass(MovimientoPase.class);
		verify(movimientos).registrar(movimiento.capture());
		assertThat(movimiento.getValue().getTipo()).isEqualTo(TipoMovimientoPase.COMPRA);
		assertThat(movimiento.getValue().getCantidad()).isEqualTo(8);

		ArgumentCaptor<VentaDePase> devengada = ArgumentCaptor.forClass(VentaDePase.class);
		verify(devengo).devengarPorVentaDePase(devengada.capture());
		assertThat(devengada.getValue().importe()).isEqualByComparingTo("40000.00");
		assertThat(devengada.getValue().paseId()).isEqualTo(77L);
	}

	@Test
	@DisplayName("El reintento con la misma clave devuelve la venta sin escribir pase ni ledger")
	void reintento_idempotente() {
		PaseServicio existente = PaseServicio.vender(producto(), PERSONA, LocalDate.of(2026, 10, 14), "k-1",
				new VenderPaseCommand(PRODUCTO, PERSONA, "k-1").huella(SEDE), 99L, Instant.now());
		ReflectionTestUtils.setField(existente, "id", 77L);
		given(pases.findByIdempotencyKey(ORG, "k-1")).willReturn(Optional.of(existente));

		ResultadoDeVenta venta = service.vender(ACTOR, SEDE, new VenderPaseCommand(PRODUCTO, PERSONA, "k-1"));

		assertThat(venta.creada()).isFalse();
		verify(pases, never()).saveAndFlush(any());
		verify(movimientos, never()).registrar(any());
		verify(productos, never()).findByIdInScope(ORG, SEDE, PRODUCTO);
	}

	@Test
	@DisplayName("La misma clave con otro titular es 409, no una segunda venta")
	void clave_reusada_con_otro_pedido() {
		PaseServicio existente = PaseServicio.vender(producto(), PERSONA, LocalDate.of(2026, 10, 14), "k-1",
				new VenderPaseCommand(PRODUCTO, PERSONA, "k-1").huella(SEDE), 99L, Instant.now());
		given(pases.findByIdempotencyKey(ORG, "k-1")).willReturn(Optional.of(existente));

		assertThatThrownBy(() -> service.vender(ACTOR, SEDE, new VenderPaseCommand(PRODUCTO, 513L, "k-1")))
				.isInstanceOf(IdempotencyKeyConflictException.class);
		verify(devengo, never()).devengarPorVentaDePase(any());
	}

	@Test
	@DisplayName("Un producto dado de baja no se vende y no devenga")
	void producto_de_baja() {
		ProductoServicio deBaja = producto();
		deBaja.darDeBaja("Se discontinua", Instant.now());
		given(productos.lockByIdInScope(ORG, SEDE, PRODUCTO)).willReturn(Optional.of(deBaja));

		assertThatThrownBy(() -> service.vender(ACTOR, SEDE, new VenderPaseCommand(PRODUCTO, PERSONA, null)))
				.isInstanceOf(ProductoNoVendibleException.class);
		verify(devengo, never()).devengarPorVentaDePase(any());
	}

	@Test
	@DisplayName("A una ficha dada de baja no se le vende")
	void persona_de_baja() {
		given(personas.find(ORG, PERSONA)).willReturn(Optional.of(persona(false)));

		assertThatThrownBy(() -> service.vender(ACTOR, SEDE, new VenderPaseCommand(PRODUCTO, PERSONA, null)))
				.isInstanceOf(PersonaDadaDeBajaException.class);
	}

	@Test
	@DisplayName("El listado resuelve renovadoPorPaseId y el nombre de quien autorizo en una consulta cada uno, no una por pase")
	void listado_con_renovacion_en_lote() {
		PaseServicio viejo = vendido(77L);
		PaseServicio nuevo = vendido(78L);
		PaseServicio sinRenovacion = vendido(79L);
		ReflectionTestUtils.setField(viejo, "renovacionAutorizadaPorCuentaId", 99L);
		ReflectionTestUtils.setField(nuevo, "renovacionAutorizadaPorCuentaId", 99L);
		given(pases.findDePersona(ORG, PERSONA)).willReturn(List.of(nuevo, viejo, sinRenovacion));
		given(pases.renovacionesDe(eq(ORG), any())).willReturn(Map.of(77L, 78L));
		given(identidades.identidadesDe(any())).willReturn(
				Map.of(99L, new AccountIdentity(99L, "Ana Gomez", "ana@example.test")));

		List<PaseView> lista = service.deLaPersona(ACTOR, SEDE, PERSONA);

		assertThat(lista).extracting(PaseView::id, PaseView::renovadoPorPaseId, PaseView::renovacionAutorizadaPor)
				.containsExactly(
						tuple(78L, null, "Ana Gomez"),
						tuple(77L, 78L, "Ana Gomez"),
						tuple(79L, null, null));
		verify(pases, times(1)).renovacionesDe(eq(ORG), any());
		verify(identidades, times(1)).identidadesDe(List.of(99L));
		verify(pases, never()).renovacionDe(anyLong(), anyLong());
	}

	@Test
	@DisplayName("Una cuenta que no resuelve deja el nombre ausente, no inventado")
	void autorizador_que_no_resuelve() {
		PaseServicio pase = vendido(77L);
		ReflectionTestUtils.setField(pase, "renovacionAutorizadaPorCuentaId", 404L);
		given(pases.findDePersona(ORG, PERSONA)).willReturn(List.of(pase));
		given(identidades.identidadesDe(any())).willReturn(Map.of());

		assertThat(service.deLaPersona(ACTOR, SEDE, PERSONA))
				.singleElement()
				.satisfies(v -> assertThat(v.renovacionAutorizadaPor()).isNull());
	}

	private static PaseServicio vendido(long id) {
		PaseServicio pase = PaseServicio.vender(producto(), PERSONA, LocalDate.of(2026, 10, 14), null, null,
				99L, Instant.parse("2026-10-14T12:00:00Z"));
		ReflectionTestUtils.setField(pase, "id", id);
		return pase;
	}

	private static ProductoServicio producto() {
		ProductoServicio producto = ProductoServicio.pack(ORG, SEDE, 45L, "Pack Pilates 8", null, 8,
				new BigDecimal("40000.00"), "ars", 60, LocalDate.of(2026, 10, 1), null);
		ReflectionTestUtils.setField(producto, "id", PRODUCTO);
		return producto;
	}

	private static PacienteSnapshot persona(boolean activa) {
		return new PacienteSnapshot(PERSONA, ORG, "Perez", "Ana", "DNI", "30111222", null, activa, false,
				null, null);
	}
}
