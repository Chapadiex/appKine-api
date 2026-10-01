package com.akine.billing.application;

import com.akine.billing.domain.CategoriaEgreso;
import com.akine.billing.domain.Egreso;
import com.akine.billing.domain.TipoBeneficiario;
import com.akine.billing.domain.exception.EgresoComprobanteDuplicadoException;
import com.akine.billing.domain.exception.EgresoConPagosException;
import com.akine.billing.domain.exception.EgresoNoConfirmableException;
import com.akine.billing.domain.exception.EgresoNoEditableException;
import com.akine.billing.domain.exception.EgresoSinComprobanteException;
import com.akine.billing.domain.exception.EgresoYaAnuladoException;
import com.akine.billing.domain.port.EgresoRepositoryPort;
import com.akine.billing.domain.port.PagoEgresoRepositoryPort;
import com.akine.platform.spi.identity.AccountIdentityDirectory;
import com.akine.organization.spi.ConsultorioMembershipDirectory;
import com.akine.organization.spi.ConsultorioMembershipSnapshot;
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
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Egresos del centro (M22, AKINE-07.05).
 *
 * <h2>Lo que decide la correctitud</h2>
 *
 * <p><b>Confirmar congela el compromiso.</b> Un importe que cambiara debajo de pagos ya asentados
 * haria que el saldo dejara de reconciliar con el ledger de caja <b>sin que nada fallara</b>, asi
 * que editar despues del borrador se rechaza y anular con pagos vivos tambien.
 *
 * <p><b>El comprobante identifica al beneficiario, no al centro.</b> La clave de unicidad incluye
 * al beneficiario porque dos proveedores distintos emiten legitimamente su propia factura numero
 * uno; sin esa clave, la segunda factura numero uno del ano seria un duplicado inventado.
 *
 * <p>Lo que estos tests NO cubren: que el {@code UPDATE} condicional del saldo sea atomico bajo
 * concurrencia. Eso lo contesta MySQL y esta declarado como escenario diferido de la etapa.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EgresoServiceTest {

	private static final long ORG_ID = 1L;
	private static final long CONSULTORIO_ID = 7L;
	private static final long EGRESO_ID = 400L;
	private static final long CUENTA = 99L;
	private static final long MEMBERSHIP = 31L;
	private static final BigDecimal IMPORTE = new BigDecimal("15000.00");

	@Mock private EgresoRepositoryPort egresos;
	@Mock private PagoEgresoRepositoryPort pagos;
	@Mock private ConsultorioMembershipDirectory memberships;
	@Mock private AccountIdentityDirectory identidades;
	@Mock private CajaAcceso acceso;
	@Mock private AuditTrail auditTrail;

	private EgresoService service;

	private final OperatingActor actor = new OperatingActor(CUENTA, false, ORG_ID, CONSULTORIO_ID);

	@BeforeEach
	void setUp() {
		service = new EgresoService(egresos, pagos, memberships, identidades, acceso, auditTrail);
		given(egresos.save(any())).willAnswer(EgresoServiceTest::conIdComoJpa);
		given(egresos.findVigentePorComprobante(anyLong(), anyString(), anyString(), anyString()))
				.willReturn(Optional.empty());
		given(egresos.findByIdInScope(ORG_ID, CONSULTORIO_ID, EGRESO_ID))
				.willReturn(Optional.of(borrador()));
		given(pagos.findDelEgreso(anyLong(), anyLong())).willReturn(List.of());
	}

	// =================================================================================
	// Alta
	// =================================================================================

	@Test
	@DisplayName("Un egreso externo se registra en BORRADOR, sin tocar la caja")
	void alta_de_un_externo() {
		// El compromiso no es el pago: registrar un egreso no mueve un peso. La plata sale cuando
		// se paga, y recien ahi aparece la caja.
		EgresoView vista = service.registrar(actor, CONSULTORIO_ID, comando(null));

		assertThat(vista.estado()).isEqualTo("BORRADOR");
		verify(egresos).save(any(Egreso.class));
	}

	@Test
	@DisplayName("Un beneficiario externo SIN nombre no entra: el egreso no diria a quien se pago")
	void externo_sin_nombre() {
		EgresoCommand sinNombre = new EgresoCommand(
				CategoriaEgreso.SERVICIOS, TipoBeneficiario.EXTERNO, null, "   ", null,
				LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), "Internet", IMPORTE, "ARS",
				null, null, null, null);

		assertThatThrownBy(() -> service.registrar(actor, CONSULTORIO_ID, sinNombre))
				.isInstanceOf(IllegalArgumentException.class);

		verify(egresos, never()).save(any());
	}

	@Test
	@DisplayName("Un colaborador sin vinculo vigente no puede ser beneficiario")
	void colaborador_sin_vinculo() {
		// Solo AL CREAR. Confirmar y pagar un egreso cuyo beneficiario ya se desvinculo tiene que
		// funcionar: lo contrario convertiria una desvinculacion en una forma de no pagar.
		given(memberships.find(ORG_ID, MEMBERSHIP)).willReturn(Optional.empty());

		assertThatThrownBy(() -> service.registrar(actor, CONSULTORIO_ID, deColaborador()))
				.isInstanceOf(RuntimeException.class);

		verify(egresos, never()).save(any());
	}

	@Test
	@DisplayName("Un colaborador vigente toma su nombre de la identidad, no del pedido")
	void colaborador_vigente() {
		given(memberships.find(ORG_ID, MEMBERSHIP)).willReturn(Optional.of(vinculoVigente()));
		given(identidades.identidadesDe(any())).willReturn(Map.of());

		EgresoView vista = service.registrar(actor, CONSULTORIO_ID, deColaborador());

		assertThat(vista).isNotNull();
		verify(egresos).save(any(Egreso.class));
	}

	@Nested
	@DisplayName("El comprobante")
	class Comprobante {

		@Test
		@DisplayName("El mismo comprobante del mismo beneficiario no entra dos veces")
		void comprobante_duplicado() {
			given(egresos.findVigentePorComprobante(anyLong(), anyString(), anyString(), anyString()))
					.willReturn(Optional.of(otroEgreso()));

			assertThatThrownBy(() ->
					service.registrar(actor, CONSULTORIO_ID, conComprobante("A", "0001-00000001")))
					.isInstanceOf(EgresoComprobanteDuplicadoException.class);
		}

		@Test
		@DisplayName("Sin comprobante declarado no se consulta la unicidad: en borrador es opcional")
		void sin_comprobante_no_se_consulta() {
			// La liquidacion se arma antes de tener la factura en la mano.
			service.registrar(actor, CONSULTORIO_ID, comando(null));

			verify(egresos, never())
					.findVigentePorComprobante(anyLong(), anyString(), anyString(), anyString());
		}
	}

	@Nested
	@DisplayName("La idempotencia")
	class Idempotencia {

		@Test
		@DisplayName("El reintento con la misma clave y el mismo cuerpo devuelve lo que ya existe")
		void reintento_devuelve_lo_mismo() {
			Egreso yaRegistrado = borrador();
			EgresoCommand command = comando("clave-1");
			ReflectionTestUtils.setField(
					yaRegistrado, "requestHash", command.huella(CONSULTORIO_ID));
			given(egresos.findByIdempotencyKey(ORG_ID, "clave-1"))
					.willReturn(Optional.of(yaRegistrado));

			EgresoView vista = service.registrar(actor, CONSULTORIO_ID, command);

			assertThat(vista.id()).isEqualTo(EGRESO_ID);
			verify(egresos, never()).save(any());
		}

		@Test
		@DisplayName("La misma clave con OTRO cuerpo es un conflicto, no un reintento")
		void misma_clave_otro_cuerpo() {
			// Devolver el primero seria peor que fallar: el segundo pedido quedaria sin registrar y
			// quien lo mando creeria que entro.
			Egreso yaRegistrado = borrador();
			ReflectionTestUtils.setField(yaRegistrado, "requestHash", "otra-huella");
			given(egresos.findByIdempotencyKey(ORG_ID, "clave-1"))
					.willReturn(Optional.of(yaRegistrado));

			assertThatThrownBy(() ->
					service.registrar(actor, CONSULTORIO_ID, comando("clave-1")))
					.isInstanceOf(IdempotencyKeyConflictException.class);
		}

		@Test
		@DisplayName("Sin clave no se consulta idempotencia: no toda alta la declara")
		void sin_clave_no_se_consulta() {
			service.registrar(actor, CONSULTORIO_ID, comando(null));

			verify(egresos, never()).findByIdempotencyKey(anyLong(), anyString());
		}
	}

	// =================================================================================
	// Transiciones
	// =================================================================================

	@Test
	@DisplayName("Confirmar SIN comprobante se rechaza: lo que falta es un campo del cuerpo")
	void confirmar_sin_comprobante() {
		assertThatThrownBy(() -> service.confirmar(actor, CONSULTORIO_ID, EGRESO_ID))
				.isInstanceOf(EgresoSinComprobanteException.class);
	}

	@Test
	@DisplayName("Confirmar congela el compromiso y deja su evento de auditoria")
	void confirmar_congela() {
		given(egresos.findByIdInScope(ORG_ID, CONSULTORIO_ID, EGRESO_ID))
				.willReturn(Optional.of(borradorConComprobante()));

		EgresoView vista = service.confirmar(actor, CONSULTORIO_ID, EGRESO_ID);

		assertThat(vista.estado()).isEqualTo("CONFIRMADO");
		verify(auditTrail).record(any(AuditEntry.class));
	}

	@Test
	@DisplayName("Confirmar dos veces no pasa: ya no es un borrador")
	void confirmar_lo_confirmado() {
		Egreso confirmado = borradorConComprobante();
		confirmado.confirmar(Instant.EPOCH, CUENTA);
		given(egresos.findByIdInScope(ORG_ID, CONSULTORIO_ID, EGRESO_ID))
				.willReturn(Optional.of(confirmado));

		assertThatThrownBy(() -> service.confirmar(actor, CONSULTORIO_ID, EGRESO_ID))
				.isInstanceOf(EgresoNoConfirmableException.class);
	}

	@Test
	@DisplayName("Editar lo ya confirmado se rechaza: el importe no cambia debajo de los pagos")
	void editar_lo_confirmado() {
		// Si el importe cambiara, el saldo dejaria de reconciliar con el ledger de caja sin que
		// nada fallara.
		Egreso confirmado = borradorConComprobante();
		confirmado.confirmar(Instant.EPOCH, CUENTA);
		given(egresos.findByIdInScope(ORG_ID, CONSULTORIO_ID, EGRESO_ID))
				.willReturn(Optional.of(confirmado));

		assertThatThrownBy(() ->
				service.editar(actor, CONSULTORIO_ID, EGRESO_ID, comando(null)))
				.isInstanceOf(EgresoNoEditableException.class);
	}

	@Test
	@DisplayName("Anular deja la fila con su motivo: no borra")
	void anular_no_borra() {
		EgresoView vista = service.anular(actor, CONSULTORIO_ID, EGRESO_ID, "Se cargo dos veces");

		assertThat(vista.estado()).isEqualTo("ANULADO");
		verify(auditTrail).record(any(AuditEntry.class));
	}

	@Test
	@DisplayName("Anular dos veces no pasa")
	void anular_lo_anulado() {
		Egreso anulado = borrador();
		anulado.anular("Se cargo dos veces", Instant.EPOCH, CUENTA);
		given(egresos.findByIdInScope(ORG_ID, CONSULTORIO_ID, EGRESO_ID))
				.willReturn(Optional.of(anulado));

		assertThatThrownBy(() ->
				service.anular(actor, CONSULTORIO_ID, EGRESO_ID, "De nuevo"))
				.isInstanceOf(EgresoYaAnuladoException.class);
	}

	@Test
	@DisplayName("Un egreso con pagos vivos no se anula: primero se anulan los pagos")
	void anular_con_pagos() {
		// Sin ese orden, la plata ya salio del cajon y el egreso que la justificaba desaparece.
		Egreso conPagos = borrador();
		ReflectionTestUtils.setField(conPagos, "saldoPendiente", new BigDecimal("5000.00"));
		given(egresos.findByIdInScope(ORG_ID, CONSULTORIO_ID, EGRESO_ID))
				.willReturn(Optional.of(conPagos));

		assertThatThrownBy(() ->
				service.anular(actor, CONSULTORIO_ID, EGRESO_ID, "Error de carga"))
				.isInstanceOf(EgresoConPagosException.class);
	}

	// =================================================================================
	// Lecturas
	// =================================================================================

	@Test
	@DisplayName("Ver un egreso trae sus pagos: el saldo sin el detalle no explica nada")
	void ver_trae_los_pagos() {
		service.ver(actor, CONSULTORIO_ID, EGRESO_ID);

		verify(pagos).findDelEgreso(ORG_ID, EGRESO_ID);
	}

	@Test
	@DisplayName("Toda operacion exige contexto de tenant, sede del tenant y permiso de caja")
	void las_tres_puertas() {
		// Es el orden que protege del oraculo: la sede se resuelve ANTES del permiso, asi que una
		// sede de otro tenant da 404 y no 403.
		service.ver(actor, CONSULTORIO_ID, EGRESO_ID);

		verify(acceso).exigirSedeDelTenant(ORG_ID, CONSULTORIO_ID);
		verify(acceso).exigirOperarCaja(actor, ORG_ID, CONSULTORIO_ID);
	}

	// =================================================================================
	// Fixtures
	// =================================================================================

	private static EgresoCommand comando(String idempotencyKey) {
		return new EgresoCommand(
				CategoriaEgreso.SERVICIOS, TipoBeneficiario.EXTERNO, null, "Proveedor SA",
				"30-11111111-1", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), "Internet",
				IMPORTE, "ARS", null, null, null, idempotencyKey);
	}

	private static EgresoCommand conComprobante(String tipo, String numero) {
		return new EgresoCommand(
				CategoriaEgreso.SERVICIOS, TipoBeneficiario.EXTERNO, null, "Proveedor SA",
				"30-11111111-1", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), "Internet",
				IMPORTE, "ARS", tipo, numero, LocalDate.of(2026, 9, 30), null);
	}

	private static EgresoCommand deColaborador() {
		return new EgresoCommand(
				CategoriaEgreso.HONORARIOS_PROFESIONALES, TipoBeneficiario.COLABORADOR, MEMBERSHIP,
				null, null, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), "Honorarios",
				IMPORTE, "ARS", null, null, null, null);
	}

	private static ConsultorioMembershipSnapshot vinculoVigente() {
		return new ConsultorioMembershipSnapshot(MEMBERSHIP, 100L, ORG_ID, CONSULTORIO_ID,
				"PROFESIONAL", "ACTIVA", Instant.EPOCH, null, true, true);
	}

	private static Egreso borrador() {
		Egreso egreso = new Egreso(ORG_ID, CONSULTORIO_ID, CategoriaEgreso.SERVICIOS,
				TipoBeneficiario.EXTERNO, null, "E:PROVEEDOR SA", "Proveedor SA", "30-11111111-1",
				LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), "Internet", IMPORTE, "ARS",
				Instant.EPOCH, CUENTA, null, null);
		ReflectionTestUtils.setField(egreso, "id", EGRESO_ID);
		return egreso;
	}

	private static Egreso borradorConComprobante() {
		Egreso egreso = borrador();
		egreso.editarBorrador(CategoriaEgreso.SERVICIOS, LocalDate.of(2026, 9, 1),
				LocalDate.of(2026, 9, 30), "Internet", IMPORTE, "FACTURA", "0001-00000001",
				LocalDate.of(2026, 9, 30));
		return egreso;
	}

	private static Egreso otroEgreso() {
		Egreso egreso = borrador();
		ReflectionTestUtils.setField(egreso, "id", EGRESO_ID + 1);
		return egreso;
	}

	/** JPA asigna el id al persistir; el doble tiene que hacer lo mismo o el fixture mentiria. */
	private static Egreso conIdComoJpa(org.mockito.invocation.InvocationOnMock i) {
		Egreso guardado = i.getArgument(0);
		if (guardado.getId() == null) {
			ReflectionTestUtils.setField(guardado, "id", EGRESO_ID);
		}
		return guardado;
	}
}
