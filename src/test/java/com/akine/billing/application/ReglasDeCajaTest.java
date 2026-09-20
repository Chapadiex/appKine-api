package com.akine.billing.application;

import com.akine.billing.domain.CobroMedio;
import com.akine.billing.domain.JornadaCaja;
import com.akine.billing.domain.MedioDePago;
import com.akine.billing.domain.MovimientoCaja;
import com.akine.billing.domain.TipoMovimiento;
import com.akine.billing.domain.exception.CajaDiferenciaSinMotivoException;
import com.akine.billing.domain.exception.CajaNoAbiertaException;
import com.akine.billing.domain.exception.CajaSaldoInsuficienteException;
import com.akine.billing.domain.port.JornadaCajaRepositoryPort;
import com.akine.billing.domain.port.MovimientoCajaRepositoryPort;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionDecision;
import com.akine.organization.spi.PermissionGuard;
import com.akine.platform.spi.audit.AuditTrail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.assertj.core.groups.Tuple;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Las tres reglas de AKINE-07.03 que si se rompen rompen el negocio, y nada mas.
 *
 * <ul>
 *   <li><b>{@code cobro != caja}, y la relacion no es uno a uno.</b> Es la regla por la que existe
 *       la etapa: un cobro con tarjeta se registra pero <b>no mueve el cajon</b> —esa plata liquida
 *       a 18 dias en una cuenta que el sistema no modela—, y sumarla al saldo arqueable haria que
 *       el conteo de billetes no cuadre nunca. Una caja que nunca cuadra deja de ser un control.
 *   <li><b>El saldo nunca queda negativo.</b> No por una regla de negocio sino por una del mundo
 *       fisico: un cajon no puede tener menos de cero pesos. Lo decide el {@code UPDATE}
 *       condicional, y cero filas afectadas tiene que terminar en un 409 explicado — no en una
 *       fila de ledger que afirme un egreso que la base no hizo.
 *   <li><b>Toda diferencia de arqueo queda justificada</b> (RN-M20-004). Cerrar con diferencia y
 *       sin motivo es un rechazo, y el cierre no se intenta: un arqueo que no cuadra y que nadie
 *       explica es exactamente el registro que una auditoria viene a buscar.
 * </ul>
 *
 * <p>Lo que la concurrencia real decide —dos egresos peleandose por el ultimo peso, el cobro que
 * entra entre el conteo y el cierre— no se puede probar con un mock que devuelve cero filas: eso
 * queda anotado en {@code docs/tests-diferidos.md} y se prueba contra MySQL.
 */
@DisplayName("Caja diaria (M20, AKINE-07.03)")
class ReglasDeCajaTest {

	private static final long ORG = 7L;
	private static final long SEDE = 20L;
	private static final long JORNADA = 900L;
	private static final long COBRO = 5501L;
	private static final String MONEDA = "ARS";
	private static final LocalDate HOY = LocalDate.of(2027, 4, 8);

	private final JornadaCajaRepositoryPort jornadas = mock(JornadaCajaRepositoryPort.class);
	private final MovimientoCajaRepositoryPort movimientos = mock(MovimientoCajaRepositoryPort.class);
	private final ConsultorioDirectory consultorios = mock(ConsultorioDirectory.class);
	private final PermissionGuard permissionGuard = mock(PermissionGuard.class);
	private final AuditTrail auditTrail = mock(AuditTrail.class);

	private final ConsultorioSnapshot sede =
			new ConsultorioSnapshot(SEDE, ORG, "Sede Centro", "America/Argentina/Cordoba", true);
	private final OperatingActor administrativo = new OperatingActor(31L, false, ORG, SEDE);

	private MovimientoCajaService movimientoService;
	private CajaDeCobro cajaDeCobro;
	private CajaService cajaService;

	@BeforeEach
	void setUp() {
		CajaAcceso acceso = new CajaAcceso(consultorios, permissionGuard);
		movimientoService = new MovimientoCajaService(movimientos, jornadas, acceso, auditTrail);
		cajaDeCobro = new CajaDeCobro(jornadas, movimientoService);
		cajaService = new CajaService(jornadas, movimientos, acceso, auditTrail);

		given(consultorios.find(ORG, SEDE)).willReturn(Optional.of(sede));
		given(permissionGuard.requirePermission(any()))
				.willReturn(PermissionDecision.concedida("CONSULTORIO", false));
		given(movimientos.save(any())).willAnswer(invocacion -> {
			MovimientoCaja movimiento = invocacion.getArgument(0);
			ReflectionTestUtils.setField(movimiento, "id", 4001L);
			return movimiento;
		});
	}

	// =================================================================================
	// cobro != caja
	// =================================================================================

	@Test
	@DisplayName("un cobro con efectivo y tarjeta asienta DOS movimientos y solo el efectivo mueve el cajon")
	void el_cobro_no_es_la_caja() {
		given(jornadas.findAbierta(ORG, SEDE)).willReturn(Optional.of(jornadaAbierta()));
		given(jornadas.sumarAlSaldo(anyLong(), anyLong(), any())).willReturn(1);

		cajaDeCobro.registrarIngresos(ORG, sede, COBRO, MONEDA, List.of(
						medio(MedioDePago.EFECTIVO, "4000.00"),
						medio(MedioDePago.TARJETA_CREDITO, "6000.00")),
				Instant.now(), administrativo.accountId());

		ArgumentCaptor<MovimientoCaja> asentados = ArgumentCaptor.forClass(MovimientoCaja.class);
		verify(movimientos, times(2)).save(asentados.capture());

		// Los dos se registran: sin la linea de tarjeta la pantalla mentiria sobre el dia, y
		// RF-M20-004 pide listar la operatoria, no listar efectivo.
		assertThat(asentados.getAllValues())
				.extracting(MovimientoCaja::getMedio, MovimientoCaja::afectaArqueo)
				.containsExactly(
						Tuple.tuple(MedioDePago.EFECTIVO, true),
						Tuple.tuple(MedioDePago.TARJETA_CREDITO, false));

		// Pero el cajon se mueve UNA sola vez y por los 4.000 en billetes. Los 6.000 de tarjeta
		// liquidan en 18 dias a una cuenta que nadie va a contar al arquear.
		verify(jornadas).sumarAlSaldo(ORG, JORNADA, new BigDecimal("4000.00"));
	}

	@Test
	@DisplayName("un cobro integramente con tarjeta entra SIN caja abierta y se asienta sin jornada")
	void la_tarjeta_no_necesita_caja_abierta() {
		given(jornadas.findAbierta(ORG, SEDE)).willReturn(Optional.empty());

		cajaDeCobro.registrarIngresos(ORG, sede, COBRO, MONEDA,
				List.of(medio(MedioDePago.TARJETA_DEBITO, "9500.00")),
				Instant.now(), administrativo.accountId());

		ArgumentCaptor<MovimientoCaja> asentado = ArgumentCaptor.forClass(MovimientoCaja.class);
		verify(movimientos).save(asentado.capture());

		// Esa plata nunca toco el cajon: no hay nada que exigir y nada que arquear. Rechazar el
		// cobro aca ataria poder cobrar a que alguien se hubiera acordado de abrir la caja.
		assertThat(asentado.getValue().getJornadaCajaId()).isNull();
		assertThat(asentado.getValue().afectaArqueo()).isFalse();
		verify(jornadas, never()).sumarAlSaldo(anyLong(), anyLong(), any());
	}

	@Test
	@DisplayName("un cobro con efectivo y sin caja abierta se RECHAZA: la plata entra al cajon igual")
	void el_efectivo_si_exige_caja_abierta() {
		given(jornadas.findAbierta(ORG, SEDE)).willReturn(Optional.empty());

		assertThatThrownBy(() -> cajaDeCobro.registrarIngresos(ORG, sede, COBRO, MONEDA, List.of(
						medio(MedioDePago.EFECTIVO, "1500.00"),
						medio(MedioDePago.TRANSFERENCIA, "500.00")),
				Instant.now(), administrativo.accountId()))
				.isInstanceOf(CajaNoAbiertaException.class);

		// Es el unico caso donde el mundo fisico obliga: si el sistema no sabe a que jornada
		// pertenece ese billete, el arqueo del dia no cuadra contra nada. Y no se asienta NADA:
		// el asiento va en la transaccion del cobro, que se rechaza entera.
		verify(movimientos, never()).save(any());
	}

	// =================================================================================
	// El saldo nunca queda negativo
	// =================================================================================

	@Test
	@DisplayName("un egreso que no alcanza es 409 y NO deja fila en el ledger")
	void el_saldo_no_queda_en_rojo() {
		JornadaCaja abierta = jornadaAbierta();
		given(jornadas.findAbierta(ORG, SEDE)).willReturn(Optional.of(abierta));
		given(jornadas.findByIdInScope(ORG, SEDE, JORNADA)).willReturn(Optional.of(abierta));
		// Cero filas: el UPDATE lleva AND saldo_arqueo >= :importe y la base no lo hizo.
		given(jornadas.restarDelSaldo(ORG, JORNADA, new BigDecimal("8000.00"))).willReturn(0);

		assertThatThrownBy(() -> movimientoService.registrarManual(administrativo, SEDE,
				new MovimientoManualCommand(
						TipoMovimiento.EGRESO, MedioDePago.EFECTIVO,
						new BigDecimal("8000.00"), "Compra de insumos", null)))
				.isInstanceOf(CajaSaldoInsuficienteException.class);

		// Lo que se prueba no es el mensaje: es que cero filas NO se convierte en un asiento. Una
		// fila que afirme un egreso que la base no hizo deja el ledger diciendo una cosa y la
		// columna otra, y a partir de ahi ningun arqueo sirve.
		verify(movimientos, never()).save(any());
	}

	// =================================================================================
	// Toda diferencia queda justificada — RN-M20-004
	// =================================================================================

	@Test
	@DisplayName("cerrar con diferencia y sin motivo se rechaza, y el cierre ni se intenta")
	void la_diferencia_exige_motivo() {
		given(jornadas.findByIdInScope(ORG, SEDE, JORNADA))
				.willReturn(Optional.of(jornadaAbierta()));

		assertThatThrownBy(() -> cajaService.cerrar(administrativo, SEDE, JORNADA,
				new BigDecimal("12000.00"), new BigDecimal("11500.00"), "   "))
				.isInstanceOf(CajaDiferenciaSinMotivoException.class);

		// Faltan 500 y nadie escribio por que. El cierre no se intenta: una jornada cerrada no se
		// reabre, asi que dejarla cerrar y pedir la explicacion despues seria no pedirla nunca.
		verify(jornadas, never()).cerrar(
				anyLong(), anyLong(), any(), any(), any(), any(), anyLong());
	}

	@Test
	@DisplayName("con motivo el cierre pasa, y la diferencia la calcula el servidor")
	void la_diferencia_se_registra_y_no_se_ajusta() {
		given(jornadas.findByIdInScope(ORG, SEDE, JORNADA))
				.willReturn(Optional.of(jornadaAbierta()));
		given(jornadas.cerrar(anyLong(), anyLong(), any(), any(), any(), any(), anyLong()))
				.willReturn(1);
		given(movimientos.totalesPorMedio(ORG, JORNADA)).willReturn(List.of());

		cajaService.cerrar(administrativo, SEDE, JORNADA,
				new BigDecimal("12000.00"), new BigDecimal("11500.00"), "Faltante de vuelto");

		// La diferencia se REGISTRA: no se rechaza el cierre —eso dejaria al centro sin poder
		// cerrar el dia en que realmente falta plata— y no se ajusta con un movimiento que la haga
		// desaparecer del ledger. El motivo viaja tal como se escribio.
		verify(jornadas).cerrar(
				eq(ORG), eq(JORNADA),
				eq(new BigDecimal("12000.00")), eq(new BigDecimal("11500.00")),
				eq("Faltante de vuelto"), any(Instant.class), eq(administrativo.accountId()));
	}

	// =================================================================================
	// Datos
	// =================================================================================

	private JornadaCaja jornadaAbierta() {
		JornadaCaja jornada = new JornadaCaja(
				ORG, SEDE, HOY, MONEDA, new BigDecimal("12000.00"), Instant.now(), 31L);
		ReflectionTestUtils.setField(jornada, "id", JORNADA);
		return jornada;
	}

	private static CobroMedio medio(MedioDePago medio, String importe) {
		return new CobroMedio(ORG, medio, new BigDecimal(importe), null);
	}
}
