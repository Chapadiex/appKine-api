package com.akine.activity.application;

import com.akine.activity.domain.PaseServicio;
import com.akine.activity.domain.ProductoServicio;
import com.akine.activity.domain.exception.PaseConCobrosException;
import com.akine.activity.domain.exception.PaseNoAjustableException;
import com.akine.activity.domain.port.ActivityRepositoryPorts.EstadoDePase;
import com.akine.activity.domain.port.ActivityRepositoryPorts.MovimientoPaseRepositoryPort;
import com.akine.activity.domain.port.ActivityRepositoryPorts.PaseServicioRepositoryPort;
import com.akine.billing.spi.DevengoDeVentas;
import com.akine.billing.spi.DevengoDeVentas.AnulacionDeDeuda;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionGuard;
import com.akine.platform.spi.audit.AuditTrail;
import com.akine.platform.spi.identity.AccountIdentityDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Las reglas del ajuste y la anulacion que no necesitan un motor: lo que se rechaza <b>no escribe
 * nada</b>. El ledger, la version y la concurrencia se prueban contra MySQL en
 * {@code PaseAbonoAjusteYVencimientoIT} y {@code DevolucionYAjusteConcurrenteIT}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AjusteDePaseService")
class AjusteDePaseServiceTest {

	private static final long ORG = 1L;
	private static final long SEDE = 10L;
	private static final long PASE = 77L;
	private static final OperatingActor ACTOR = new OperatingActor(99L, false, ORG, SEDE);
	private static final Clock RELOJ = Clock.fixed(Instant.parse("2026-10-14T15:00:00Z"), ZoneOffset.UTC);

	@Mock private PaseServicioRepositoryPort pases;
	@Mock private MovimientoPaseRepositoryPort movimientos;
	@Mock private DevengoDeVentas ventas;
	@Mock private ConsultorioDirectory consultorios;
	@Mock private PermissionGuard permissionGuard;
	@Mock private AuditTrail auditTrail;
	@Mock private AccountIdentityDirectory identidades;

	private AjusteDePaseService service;

	@BeforeEach
	void setUp() {
		service = new AjusteDePaseService(pases, movimientos, ventas, consultorios, permissionGuard,
				auditTrail, new LecturaDePases(pases, movimientos, ventas, identidades), RELOJ);
		given(consultorios.find(ORG, SEDE)).willReturn(Optional.of(
				new ConsultorioSnapshot(SEDE, ORG, "Sede", "UTC", true)));
		given(pases.lockByIdInScope(ORG, SEDE, PASE)).willReturn(Optional.of(pase()));
		given(movimientos.ajusteSobreVersion(anyLong(), anyLong(), anyLong())).willReturn(Optional.empty());
	}

	@Test
	@DisplayName("Sin pase:adjust no se lee ni se escribe el pase")
	void sin_permiso() {
		willThrow(new AccessDeniedException("no")).given(permissionGuard).requirePermission(any());

		assertThatThrownBy(() -> service.ajustar(ACTOR, SEDE, PASE, new AjustarSaldoCommand(-1, "x", 0), true))
				.isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> service.anular(ACTOR, SEDE, PASE, "x", 0))
				.isInstanceOf(AccessDeniedException.class);
		verifyNoInteractions(pases, movimientos, ventas);
	}

	@Test
	@DisplayName("Un ajuste de cero o sin motivo es 400 y no toca el pase")
	void ajuste_invalido() {
		assertThatThrownBy(() -> service.ajustar(ACTOR, SEDE, PASE, new AjustarSaldoCommand(0, "x", 0), true))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.ajustar(ACTOR, SEDE, PASE, new AjustarSaldoCommand(1, " ", 0), true))
				.isInstanceOf(IllegalArgumentException.class);
		verify(pases, never()).ajustar(anyLong(), anyLong(), anyInt(), anyLong());
	}

	@Test
	@DisplayName("Un saldo final fuera de rango no llega al UPDATE")
	void fuera_de_rango() {
		assertThatThrownBy(() -> service.ajustar(ACTOR, SEDE, PASE, new AjustarSaldoCommand(-9, "x", 0), true))
				.isInstanceOf(PaseNoAjustableException.class)
				.hasFieldOrPropertyWithValue("causa", PaseNoAjustableException.Causa.FUERA_DE_RANGO);
		assertThatThrownBy(() -> service.ajustar(ACTOR, SEDE, PASE, new AjustarSaldoCommand(1, "x", 0), true))
				.isInstanceOf(PaseNoAjustableException.class)
				.hasFieldOrPropertyWithValue("causa", PaseNoAjustableException.Causa.FUERA_DE_RANGO);
		verify(pases, never()).ajustar(anyLong(), anyLong(), anyInt(), anyLong());
	}

	@Test
	@DisplayName("Anular con creditos usados o con cobros no anula el pase")
	void anulacion_rechazada() {
		given(movimientos.consumoNeto(ORG, PASE)).willReturn(-1);
		assertThatThrownBy(() -> service.anular(ACTOR, SEDE, PASE, "x", 0))
				.isInstanceOf(PaseNoAjustableException.class)
				.hasFieldOrPropertyWithValue("causa", PaseNoAjustableException.Causa.CREDITOS_USADOS);
		verifyNoInteractions(ventas);

		given(movimientos.consumoNeto(ORG, PASE)).willReturn(0);
		given(ventas.anularDeudaDeVenta(anyLong(), anyLong(), anyLong(), any(), any())).willReturn(
				new AnulacionDeDeuda(AnulacionDeDeuda.Efecto.CON_COBROS, 9001L, new BigDecimal("40000.00"),
						new BigDecimal("30000.00"), "ARS", "PARCIAL"));
		assertThatThrownBy(() -> service.anular(ACTOR, SEDE, PASE, "x", 0))
				.isInstanceOf(PaseConCobrosException.class)
				.hasFieldOrPropertyWithValue("cobrado", new BigDecimal("10000.00"))
				.hasFieldOrPropertyWithValue("moneda", "ARS");
		verify(pases, never()).anular(anyLong(), anyLong(), anyLong(), anyLong(), any(), any());
	}

	@Test
	@DisplayName("sinMovimientos: el ajuste devuelve el pase sin leer el ledger; sin el parametro, con el ledger")
	void ajuste_sin_movimientos() {
		given(pases.ajustar(ORG, PASE, -1, 0)).willReturn(1);
		given(pases.estadoActual(ORG, PASE)).willReturn(new EstadoDePase(7, "ACTIVO", 1));

		PaseDetalleView sinLedger = service.ajustar(ACTOR, SEDE, PASE, new AjustarSaldoCommand(-1, "x", 0), false);

		assertThat(sinLedger.movimientos()).isEmpty();
		assertThat(sinLedger.pase().creditosDisponibles()).isEqualTo(7);
		assertThat(sinLedger.pase().version()).isEqualTo(1);
		verify(movimientos, never()).historial(ORG, PASE);

		service.ajustar(ACTOR, SEDE, PASE, new AjustarSaldoCommand(-1, "x", 0), true);
		verify(movimientos).historial(ORG, PASE);
	}

	/** Un pack de 8 creditos recien vendido, version 0. */
	private static PaseServicio pase() {
		ProductoServicio producto = ProductoServicio.pack(ORG, SEDE, 42L, "Pack", null, 8,
				new BigDecimal("40000.00"), "ARS", 60, LocalDate.of(2026, 10, 1), null);
		ReflectionTestUtils.setField(producto, "id", 12L);
		PaseServicio pase = PaseServicio.vender(producto, 512L, LocalDate.of(2026, 10, 14), null, null, 99L,
				Instant.parse("2026-10-14T15:00:00Z"));
		ReflectionTestUtils.setField(pase, "id", PASE);
		return pase;
	}
}
