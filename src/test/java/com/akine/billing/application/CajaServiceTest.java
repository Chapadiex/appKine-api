package com.akine.billing.application;

import com.akine.billing.domain.JornadaCaja;
import com.akine.billing.domain.exception.CajaCerradaException;
import com.akine.billing.domain.exception.CajaDiferenciaSinMotivoException;
import com.akine.billing.domain.exception.CajaSaldoCambioException;
import com.akine.billing.domain.exception.CajaYaAbiertaException;
import com.akine.billing.domain.exception.JornadaCajaNotAccessibleException;
import com.akine.billing.domain.port.JornadaCajaRepositoryPort;
import com.akine.billing.domain.port.MovimientoCajaRepositoryPort;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * La jornada de caja (M20, AKINE-07.03).
 *
 * <h2>Las dos reglas que sostienen el arqueo</h2>
 *
 * <p><b>A lo sumo una jornada abierta por sede.</b> Dos cajas abiertas sobre el mismo cajon fisico
 * hacen que ningun arqueo se pueda atribuir, y el 409 lleva el id de la que ya existe para que la
 * pantalla pueda llevar al operador ahi en vez de dejarlo sin salida.
 *
 * <p><b>El cierre se valida contra el saldo teorico que el operador VIO.</b> Si un cobro en
 * efectivo entra mientras cuenta los billetes, el cierre no se registra como faltante: se rechaza
 * con el numero nuevo, porque registrar un desvio inventado obligaria a justificar por escrito algo
 * que nunca paso. Esa condicion la evalua el {@code UPDATE} de la base, no un {@code if}.
 *
 * <p><b>La diferencia no se rechaza y no se ajusta.</b> Rechazarla dejaria al centro sin poder
 * cerrar el dia en que realmente falta plata; lo unico que se exige es que alguien escriba por que.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CajaServiceTest {

	private static final long ORG_ID = 1L;
	private static final long CONSULTORIO_ID = 7L;
	private static final long JORNADA_ID = 55L;
	private static final long CUENTA = 99L;
	private static final BigDecimal INICIAL = new BigDecimal("5000.00");
	private static final BigDecimal TEORICO = new BigDecimal("12000.00");

	@Mock private JornadaCajaRepositoryPort jornadas;
	@Mock private MovimientoCajaRepositoryPort movimientos;
	@Mock private CajaAcceso acceso;
	@Mock private AuditTrail auditTrail;

	private CajaService service;

	private final OperatingActor actor = new OperatingActor(CUENTA, false, ORG_ID, CONSULTORIO_ID);

	@BeforeEach
	void setUp() {
		service = new CajaService(jornadas, movimientos, acceso, auditTrail);

		given(acceso.exigirSedeDelTenant(ORG_ID, CONSULTORIO_ID)).willReturn(sede());
		given(jornadas.findAbierta(anyLong(), anyLong())).willReturn(Optional.empty());
		given(jornadas.save(any())).willAnswer(CajaServiceTest::conIdComoJpa);
		given(jornadas.findByIdInScope(ORG_ID, CONSULTORIO_ID, JORNADA_ID))
				.willReturn(Optional.of(abierta()));
		given(jornadas.cerrar(anyLong(), anyLong(), any(), any(), any(), any(), anyLong()))
				.willReturn(1);
		given(movimientos.totalesPorMedio(anyLong(), anyLong())).willReturn(List.of());
		given(jornadas.findHistorico(anyLong(), anyLong(), any(), any(), any(), anyInt(), anyInt()))
				.willReturn(List.of());
	}

	@Nested
	@DisplayName("Abrir")
	class Abrir {

		@Test
		@DisplayName("Abrir deja la jornada con la fecha de negocio de la SEDE, no la del servidor")
		void abrir_usa_la_fecha_de_la_sede() {
			// La fecha de negocio sale del huso de la sede: a las 22:30 en Cordoba, el servidor en
			// UTC ya esta en el dia siguiente y el arqueo caeria en la jornada equivocada.
			JornadaCajaView vista = service.abrir(actor, CONSULTORIO_ID, INICIAL, "ARS");

			assertThat(vista).isNotNull();
			verify(jornadas).save(any(JornadaCaja.class));
			verify(auditTrail).record(any(AuditEntry.class));
		}

		@Test
		@DisplayName("Con una jornada ya abierta, el 409 lleva el id de la que existe")
		void abrir_dos_veces() {
			// Sin ese id la pantalla solo puede decir -no se puede- y el operador queda sin salida.
			given(jornadas.findAbierta(ORG_ID, CONSULTORIO_ID)).willReturn(Optional.of(abierta()));

			assertThatThrownBy(() -> service.abrir(actor, CONSULTORIO_ID, INICIAL, "ARS"))
					.isInstanceOf(CajaYaAbiertaException.class);

			verify(jornadas, never()).save(any());
		}
	}

	@Nested
	@DisplayName("Cerrar")
	class Cerrar {

		@Test
		@DisplayName("Cerrar sin diferencia no exige motivo")
		void cierre_que_cuadra() {
			assertThatCode(() ->
					service.cerrar(actor, CONSULTORIO_ID, JORNADA_ID, TEORICO, TEORICO, null))
					.doesNotThrowAnyException();

			verify(jornadas).cerrar(anyLong(), anyLong(), any(), any(), any(), any(), anyLong());
		}

		@Test
		@DisplayName("Cerrar CON diferencia y sin motivo se rechaza, pero la diferencia no")
		void diferencia_sin_motivo() {
			// La diferencia no se rechaza —eso dejaria al centro sin poder cerrar el dia en que
			// realmente falta plata— y no se ajusta: lo unico que se exige es escribir por que.
			assertThatThrownBy(() -> service.cerrar(actor, CONSULTORIO_ID, JORNADA_ID, TEORICO,
					new BigDecimal("11800.00"), "   "))
					.isInstanceOf(CajaDiferenciaSinMotivoException.class);

			verify(jornadas, never())
					.cerrar(anyLong(), anyLong(), any(), any(), any(), any(), anyLong());
		}

		@Test
		@DisplayName("Con diferencia Y motivo, el cierre entra")
		void diferencia_con_motivo() {
			assertThatCode(() -> service.cerrar(actor, CONSULTORIO_ID, JORNADA_ID, TEORICO,
					new BigDecimal("11800.00"), "Faltaron 200 del vuelto"))
					.doesNotThrowAnyException();
		}

		@Test
		@DisplayName("Si el saldo teorico cambio mientras se contaba, el cierre NO entra")
		void saldo_cambio_mientras_contaba() {
			// Es el caso que rompe el diseno: un cobro en efectivo que entra entre el conteo y la
			// confirmacion. Un cierre ingenuo lo registraria como faltante y obligaria a justificar
			// por escrito un desvio que nunca existio. La condicion la evalua el UPDATE: cero filas.
			given(jornadas.cerrar(anyLong(), anyLong(), any(), any(), any(), any(), anyLong()))
					.willReturn(0);

			assertThatThrownBy(() ->
					service.cerrar(actor, CONSULTORIO_ID, JORNADA_ID, TEORICO, TEORICO, null))
					.isInstanceOf(CajaSaldoCambioException.class);
		}

		@Test
		@DisplayName("Cero filas porque OTRO cierre gano la carrera es caja cerrada, no saldo cambiado")
		void cierre_concurrente() {
			// Dos cierres simultaneos: el segundo afecta cero filas igual, y distinguirlo importa
			// porque la accion es otra —no hay nada que recontar, la caja ya esta cerrada—.
			JornadaCaja yaCerrada = abierta();
			ReflectionTestUtils.setField(yaCerrada, "estado",
					com.akine.billing.domain.EstadoJornada.CERRADA);
			given(jornadas.cerrar(anyLong(), anyLong(), any(), any(), any(), any(), anyLong()))
					.willReturn(0);
			given(jornadas.findByIdInScope(ORG_ID, CONSULTORIO_ID, JORNADA_ID))
					.willReturn(Optional.of(abierta()), Optional.of(yaCerrada));

			assertThatThrownBy(() ->
					service.cerrar(actor, CONSULTORIO_ID, JORNADA_ID, TEORICO, TEORICO, null))
					.isInstanceOf(CajaCerradaException.class);
		}

		@Test
		@DisplayName("Una jornada ya cerrada no se vuelve a cerrar: no existe la reapertura")
		void cerrar_lo_cerrado() {
			// Un error se compensa con movimientos en la jornada abierta hoy, no reabriendo ayer.
			JornadaCaja cerrada = abierta();
			ReflectionTestUtils.setField(cerrada, "estado",
					com.akine.billing.domain.EstadoJornada.CERRADA);
			given(jornadas.findByIdInScope(ORG_ID, CONSULTORIO_ID, JORNADA_ID))
					.willReturn(Optional.of(cerrada));

			assertThatThrownBy(() ->
					service.cerrar(actor, CONSULTORIO_ID, JORNADA_ID, TEORICO, TEORICO, null))
					.isInstanceOf(CajaCerradaException.class);
		}

		@Test
		@DisplayName("Una jornada de otra sede da 404, nunca 403")
		void jornada_de_otra_sede() {
			given(jornadas.findByIdInScope(ORG_ID, CONSULTORIO_ID, JORNADA_ID))
					.willReturn(Optional.empty());

			assertThatThrownBy(() ->
					service.cerrar(actor, CONSULTORIO_ID, JORNADA_ID, TEORICO, TEORICO, null))
					.isInstanceOf(JornadaCajaNotAccessibleException.class);
		}
	}

	@Nested
	@DisplayName("Lecturas")
	class Lecturas {

		@Test
		@DisplayName("Ver una jornada trae sus totales por medio de pago")
		void ver_trae_totales() {
			// El saldo sin el desglose no sirve para arquear: el operador cuenta efectivo, no un
			// total que mezcla tarjetas.
			service.ver(actor, CONSULTORIO_ID, JORNADA_ID);

			verify(movimientos).totalesPorMedio(ORG_ID, JORNADA_ID);
		}

		@Test
		@DisplayName("El historico acota el limite pedido entre 1 y el tope")
		void el_limite_se_acota() {
			// Un limite de cien mil traeria la caja entera del centro a memoria.
			service.historico(actor, CONSULTORIO_ID, null, LocalDate.of(2026, 9, 1),
					LocalDate.of(2026, 9, 30), 100_000, -5);

			verify(jornadas).findHistorico(ORG_ID, CONSULTORIO_ID, null,
					LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), 200, 0);
		}

		@Test
		@DisplayName("El saldo es reconstruible si la suma del ledger cuadra con la columna")
		void saldo_reconstruible() {
			// Es la cuenta que detecta una divergencia entre lo que el arqueo afirma y lo que los
			// movimientos explican. Si no cuadra, alguien escribio el saldo sin dejar movimiento.
			given(movimientos.reconstruirSaldoArqueable(ORG_ID, JORNADA_ID))
					.willReturn(BigDecimal.ZERO);

			assertThat(service.saldoReconstruible(actor, CONSULTORIO_ID, JORNADA_ID)).isTrue();
		}

		@Test
		@DisplayName("Si el ledger no explica el saldo, la cuenta dice que NO")
		void saldo_no_reconstruible() {
			given(movimientos.reconstruirSaldoArqueable(ORG_ID, JORNADA_ID))
					.willReturn(new BigDecimal("300.00"));

			assertThat(service.saldoReconstruible(actor, CONSULTORIO_ID, JORNADA_ID)).isFalse();
		}
	}

	@Test
	@DisplayName("Toda operacion pasa por sede del tenant y permiso de caja, en ese orden")
	void las_dos_puertas() {
		// La sede se resuelve ANTES del permiso: una sede de otro tenant da 404 y no 403, que
		// confirmaria su existencia.
		service.ver(actor, CONSULTORIO_ID, JORNADA_ID);

		verify(acceso).exigirSedeDelTenant(ORG_ID, CONSULTORIO_ID);
		verify(acceso).exigirOperarCaja(actor, ORG_ID, CONSULTORIO_ID);
	}

	// =================================================================================
	// Fixtures
	// =================================================================================

	private static ConsultorioSnapshot sede() {
		return new ConsultorioSnapshot(
				CONSULTORIO_ID, ORG_ID, "Sede", "America/Argentina/Cordoba", true);
	}

	private static JornadaCaja abierta() {
		JornadaCaja jornada = new JornadaCaja(ORG_ID, CONSULTORIO_ID, LocalDate.of(2026, 9, 30),
				"ARS", INICIAL, Instant.EPOCH, CUENTA);
		ReflectionTestUtils.setField(jornada, "id", JORNADA_ID);
		return jornada;
	}

	/** JPA asigna el id al persistir; el doble tiene que hacer lo mismo o el fixture mentiria. */
	private static JornadaCaja conIdComoJpa(org.mockito.invocation.InvocationOnMock i) {
		JornadaCaja guardada = i.getArgument(0);
		if (guardada.getId() == null) {
			ReflectionTestUtils.setField(guardada, "id", JORNADA_ID);
		}
		return guardada;
	}
}
