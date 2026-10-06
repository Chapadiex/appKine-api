package com.akine.billing.application;

import com.akine.billing.domain.EstadoItemPresentacion;
import com.akine.billing.domain.EstadoPresentacion;
import com.akine.billing.domain.Obligacion;
import com.akine.billing.domain.Presentacion;
import com.akine.billing.domain.PresentacionItem;
import com.akine.billing.domain.Responsable;
import com.akine.billing.domain.exception.FacturaDuplicadaException;
import com.akine.billing.domain.exception.ImporteDeDebitoInvalidoException;
import com.akine.billing.domain.exception.ItemNoDebitableException;
import com.akine.billing.domain.exception.ObligacionNoPresentableException;
import com.akine.billing.domain.exception.ObligacionYaPresentadaException;
import com.akine.billing.domain.exception.PresentacionConHallazgosException;
import com.akine.billing.domain.exception.PresentacionEstadoInvalidoException;
import com.akine.billing.domain.exception.PresentacionItemNotAccessibleException;
import com.akine.billing.domain.exception.PresentacionNoConciliaException;
import com.akine.billing.domain.exception.PresentacionNoEditableException;
import com.akine.billing.domain.exception.PresentacionNotAccessibleException;
import com.akine.billing.domain.exception.PresentacionSaldoInsuficienteException;
import com.akine.billing.domain.exception.PresentacionVaciaException;
import com.akine.billing.domain.port.CobroRepositoryPort;
import com.akine.billing.domain.port.ObligacionRepositoryPort;
import com.akine.billing.domain.port.PresentacionItemRepositoryPort;
import com.akine.billing.domain.port.PresentacionNumeradorPort;
import com.akine.billing.domain.port.PresentacionRepositoryPort;
import com.akine.contracting.spi.FinanciadorSnapshot;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Las reglas de M21 que cuestan caro si se rompen.
 *
 * <h2>Que decide la correctitud de este servicio</h2>
 *
 * <p>Tres cosas, y ninguna es CRUD. <b>Primera:</b> que las cuatro cosas sigan siendo cuatro
 * —prestado, presentado, facturado y cobrado—, o sea que ningun comando salde deuda y mueva caja a
 * la vez, y que las obligaciones se salden en un unico momento, al conciliar. <b>Segunda:</b> que
 * una prestacion que no se puede reclamar sea rechazada <b>con motivo</b> y antes de consumir un
 * numero de la serie del financiador. <b>Tercera:</b> que cero filas de un {@code UPDATE}
 * condicional se traduzca al motivo correcto, porque "no hay saldo" y "el lote se cerro" mandan al
 * administrativo a lugares distintos.
 *
 * <h2>Lo que estos tests NO pueden decir</h2>
 *
 * <p>Son unitarios contra dobles: <b>no prueban atomicidad ni aislamiento</b>. Que el
 * {@code UPDATE ... WHERE saldo >= :importe} realmente serialice dos debitos concurrentes, que la
 * columna generada {@code ocupa_marca} y su unique impidan de verdad que una obligacion viva en dos
 * lotes, que los {@code CHECK} de V56 rechacen un saldo negativo, y que {@code READ_COMMITTED} vea
 * lo que la otra transaccion acaba de cometer: eso lo contesta <b>MySQL</b>, y esta en
 * {@code docs/tests-diferidos.md}. Aca el cero de filas se simula; lo que se fija es <b>como se
 * traduce</b>.
 *
 * <p>Tampoco prueban que {@code @Transactional} este puesto ni que la auditoria quede dentro de la
 * transaccion del negocio: solo que se escriba, con el evento y el motivo correctos.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PresentacionService")
class PresentacionServiceTest {

	private static final long ACCOUNT_ID = 40L;
	private static final long ORG_ID = 10L;
	private static final long SEDE_ID = 20L;
	private static final long FINANCIADOR_ID = 31L;
	private static final long PRESENTACION_ID = 1204L;

	@Mock
	private PresentacionRepositoryPort presentaciones;

	@Mock
	private PresentacionItemRepositoryPort items;

	@Mock
	private ObligacionRepositoryPort obligaciones;

	@Mock
	private CobroRepositoryPort cobros;

	@Mock
	private PresentacionNumeradorPort numerador;

	@Mock
	private PresentacionNumeradorIniciador numeradorIniciador;

	@Mock
	private PresentacionAcceso acceso;

	@Mock
	private AuditTrail auditTrail;

	private PresentacionService service;

	private final OperatingActor actor = new OperatingActor(ACCOUNT_ID, false, ORG_ID, SEDE_ID);

	@BeforeEach
	void setUp() {
		service = new PresentacionService(
				presentaciones, items, obligaciones, cobros, numerador, numeradorIniciador,
				acceso, auditTrail);

		given(acceso.exigirSedeDelTenant(anyLong(), anyLong()))
				.willReturn(new ConsultorioSnapshot(
						SEDE_ID, ORG_ID, "Sede Centro", "America/Argentina/Cordoba", true));
	}

	@Test
	@DisplayName("conciliar con residual se rechaza y nombra el numero que falta explicar")
	void conciliarConResidual() {
		Presentacion lote = confirmada(new BigDecimal("100000.00"), new BigDecimal("15000.00"));
		given(presentaciones.findByIdInScope(ORG_ID, SEDE_ID, PRESENTACION_ID))
				.willReturn(Optional.of(lote));

		assertThatThrownBy(() -> service.conciliar(actor, SEDE_ID, PRESENTACION_ID))
				.isInstanceOf(PresentacionNoConciliaException.class)
				.hasMessageContaining("15000.00");

		// Y lo que importa tanto como el rechazo: no salda NADA. Un cierre con diferencia dejaria
		// obligaciones marcadas como pagadas por plata que nunca entro.
		verify(cobros, never()).descontarSaldo(anyLong(), anyLong(), any());
		assertThat(lote.getEstado()).isEqualTo(EstadoPresentacion.FACTURADA);
	}

	@Test
	@DisplayName("conciliar con saldo cero salda solo las prestaciones aceptadas, no las debitadas")
	void conciliarSaldaLoAceptado() {
		Presentacion lote = confirmada(new BigDecimal("100000.00"), BigDecimal.ZERO.setScale(2));
		given(presentaciones.findByIdInScope(ORG_ID, SEDE_ID, PRESENTACION_ID))
				.willReturn(Optional.of(lote));

		PresentacionItem vivo = item(55010L, 9001L, new BigDecimal("85000.00"));
		PresentacionItem debitado = item(55011L, 9002L, new BigDecimal("15000.00"));
		debitado.debitar(new BigDecimal("15000.00"), "Falta autorizacion", Instant.now(), ACCOUNT_ID);
		given(items.findDeLaPresentacion(PRESENTACION_ID)).willReturn(List.of(vivo, debitado));
		given(cobros.descontarSaldo(ORG_ID, 9001L, new BigDecimal("85000.00"))).willReturn(1);

		service.conciliar(actor, SEDE_ID, PRESENTACION_ID);

		assertThat(vivo.getEstado()).isEqualTo(EstadoItemPresentacion.ACEPTADO);
		assertThat(debitado.getEstado()).isEqualTo(EstadoItemPresentacion.DEBITADO);
		assertThat(lote.getEstado()).isEqualTo(EstadoPresentacion.CONCILIADA);

		// RN-M21-004: rechazar una prestacion no perdona la deuda. La debitada NO se salda.
		verify(cobros).descontarSaldo(ORG_ID, 9001L, new BigDecimal("85000.00"));
		verify(cobros).actualizarEstadoPorSaldo(ORG_ID, 9001L);
		verify(cobros, never()).descontarSaldo(ORG_ID, 9002L, new BigDecimal("15000.00"));
	}

	@Test
	@DisplayName("un debito mayor que el saldo se rechaza con el saldo disponible")
	void debitoQueNoEntra() {
		Presentacion lote = confirmada(new BigDecimal("100000.00"), new BigDecimal("5000.00"));
		given(presentaciones.findByIdInScope(ORG_ID, SEDE_ID, PRESENTACION_ID))
				.willReturn(Optional.of(lote));
		given(items.findByIdEnLaPresentacion(ORG_ID, PRESENTACION_ID, 55010L))
				.willReturn(Optional.of(item(55010L, 9001L, new BigDecimal("85000.00"))));
		// Cero filas: es lo que devuelve el UPDATE condicional cuando el saldo no alcanza porque
		// otra transaccion —el pago— se lo llevo primero.
		given(presentaciones.registrarDebito(ORG_ID, PRESENTACION_ID, new BigDecimal("15000.00")))
				.willReturn(0);

		assertThatThrownBy(() -> service.debitar(
				actor, SEDE_ID, PRESENTACION_ID, 55010L,
				new PresentacionCommands.Debito(new BigDecimal("15000.00"), "Rechazo parcial")))
				.isInstanceOf(PresentacionSaldoInsuficienteException.class)
				.hasMessageContaining("5000.00");
	}

	@Test
	@DisplayName("una prestacion viva en otro lote no se puede agregar, y el 409 dice en cual esta")
	void obligacionYaPresentada() {
		Presentacion borrador = borrador();
		given(presentaciones.findByIdInScope(ORG_ID, SEDE_ID, PRESENTACION_ID))
				.willReturn(Optional.of(borrador));
		given(obligaciones.findByIdInScope(ORG_ID, SEDE_ID, 9001L))
				.willReturn(Optional.of(obligacionDeFinanciador()));

		PresentacionItem enOtroLote = item(77000L, 9001L, new BigDecimal("85000.00"));
		ReflectionTestUtils.setField(enOtroLote, "presentacionId", 999L);
		given(items.findVivoDeLaObligacion(ORG_ID, 9001L)).willReturn(Optional.of(enOtroLote));

		assertThatThrownBy(() -> service.agregarItem(actor, SEDE_ID, PRESENTACION_ID, 9001L))
				.isInstanceOf(ObligacionYaPresentadaException.class)
				.hasMessageContaining("999");

		verify(items, never()).save(any());
	}

	@Test
	@DisplayName("confirmar asigna el numero del numerador y no toca ninguna obligacion")
	void confirmarNoCobra() {
		Presentacion borrador = borrador();
		given(presentaciones.findByIdInScope(ORG_ID, SEDE_ID, PRESENTACION_ID))
				.willReturn(Optional.of(borrador));
		given(items.findDeLaPresentacion(PRESENTACION_ID))
				.willReturn(List.of(item(55010L, 9001L, new BigDecimal("85000.00"))));
		given(obligaciones.findByIdInScope(ORG_ID, SEDE_ID, 9001L))
				.willReturn(Optional.of(obligacionDeFinanciador()));
		given(items.sumarPresentado(PRESENTACION_ID)).willReturn(new BigDecimal("85000.00"));
		given(numerador.leerUltimo(ORG_ID, SEDE_ID, FINANCIADOR_ID)).willReturn(48);

		PresentacionView vista = service.confirmar(actor, SEDE_ID, PRESENTACION_ID);

		assertThat(vista.numero()).isEqualTo(48);
		assertThat(vista.estado()).isEqualTo(EstadoPresentacion.PRESENTADA);
		assertThat(vista.saldo()).isEqualByComparingTo("85000.00");

		// La fila del numerador se asegura ANTES de bloquearla: crearla dentro de la transaccion
		// que la bloquea produce deadlock, y el try/catch no salva.
		verify(numeradorIniciador).asegurar(ORG_ID, SEDE_ID, FINANCIADOR_ID);
		verify(numerador).incrementar(ORG_ID, SEDE_ID, FINANCIADOR_ID);

		// RN-M21-001: presentado no es cobrado. Confirmar no mueve un peso.
		verify(cobros, never()).descontarSaldo(anyLong(), anyLong(), any());
	}

	// =================================================================================
	// Precondiciones: contexto y alcance
	// =================================================================================

	@Test
	@DisplayName("sin contexto de trabajo la operacion es 403 y nunca 401")
	void sinContextoEs403() {
		// Un 401 hace que el interceptor del frontend borre el token y entre en bucle de login: el
		// administrativo queda sin poder trabajar por una sede que no eligio, no por no estar logueado.
		OperatingActor sinContexto = new OperatingActor(ACCOUNT_ID, false, null, SEDE_ID);

		assertThatThrownBy(() -> service.detalle(sinContexto, SEDE_ID, PRESENTACION_ID))
				.isInstanceOf(AccessDeniedException.class);
	}

	@Test
	@DisplayName("un lote de otro tenant responde 404 y no 403")
	void loteDeOtroTenantEs404() {
		// Un 403 confirma que el id existe: bastaria probar ids consecutivos para enumerar los lotes
		// del SaaS entero y saber cuantos financiadores tiene cada centro de la competencia.
		given(presentaciones.findByIdInScope(ORG_ID, SEDE_ID, PRESENTACION_ID))
				.willReturn(Optional.empty());

		assertThatThrownBy(() -> service.detalle(actor, SEDE_ID, PRESENTACION_ID))
				.isInstanceOf(PresentacionNotAccessibleException.class);
	}

	// =================================================================================
	// Armado del lote
	// =================================================================================

	@Test
	@DisplayName("crear con prestaciones las incluye, congela el total y audita el alta")
	void crearConPrestaciones() {
		given(presentaciones.save(any())).willAnswer(invocacion -> {
			Presentacion guardada = invocacion.getArgument(0);
			// El servicio usa el id de lo guardado para colgarle los items: si el doble no lo asigna,
			// el test prueba un camino que en produccion no existe.
			ReflectionTestUtils.setField(guardada, "id", PRESENTACION_ID);
			return guardada;
		});
		given(items.save(any())).willAnswer(invocacion -> invocacion.getArgument(0));
		given(obligaciones.findByIdInScope(ORG_ID, SEDE_ID, 9001L))
				.willReturn(Optional.of(obligacionDeFinanciador()));
		given(items.sumarPresentado(PRESENTACION_ID)).willReturn(new BigDecimal("85000.00"));
		given(acceso.exigirFinanciador(ORG_ID, FINANCIADOR_ID)).willReturn(financiador("OSDE"));

		PresentacionView vista = service.crear(actor, SEDE_ID, alta(List.of(9001L)));

		assertThat(vista.estado()).isEqualTo(EstadoPresentacion.BORRADOR);
		assertThat(vista.numero()).isNull();
		assertThat(vista.totalPresentado()).isEqualByComparingTo("85000.00");

		// El nombre sale de la lectura viva que ya se hizo para decidir, no de una segunda consulta
		// al spi: una pantalla no deberia pagar dos viajes para mostrar el mismo texto.
		assertThat(vista.financiadorNombre()).isEqualTo("OSDE");
		verify(acceso, never()).nombreDe(anyLong(), anyLong());

		verify(auditTrail).record(any());
		assertThat(auditado().eventType()).isEqualTo(AuditEvents.PRESENTACION_CREADA);
	}

	@Test
	@DisplayName("crear sin prestaciones es un camino legitimo y deja el lote en cero")
	void crearVacio() {
		given(presentaciones.save(any())).willAnswer(invocacion -> {
			Presentacion guardada = invocacion.getArgument(0);
			ReflectionTestUtils.setField(guardada, "id", PRESENTACION_ID);
			return guardada;
		});
		given(items.sumarPresentado(PRESENTACION_ID)).willReturn(BigDecimal.ZERO.setScale(2));
		given(acceso.exigirFinanciador(ORG_ID, FINANCIADOR_ID)).willReturn(financiador("OSDE"));

		PresentacionView vista = service.crear(actor, SEDE_ID, alta(List.of()));

		// Armar el lote a mano, una prestacion por vez, es el flujo real de un administrativo que
		// revisa cada caso: exigir que el alta traiga la lista completa lo obligaria a decidir todo
		// antes de ver nada.
		assertThat(vista.totalPresentado()).isEqualByComparingTo("0.00");
		verify(items, never()).save(any());
	}

	@Test
	@DisplayName("una deuda anulada no se le reclama a nadie")
	void obligacionAnuladaNoSePresenta() {
		assertNoPresentable(obligacionCon("anuladaEn", Instant.parse("2026-08-20T10:00:00Z")),
				HallazgoDeValidacion.OBLIGACION_ANULADA);
	}

	@Test
	@DisplayName("una deuda con saldo cero no se presenta: seria reclamarla dos veces")
	void obligacionSinSaldoNoSePresenta() {
		// Ya se cobro por otra via —el paciente la pago en mostrador—. Presentarla igual le reclama
		// al financiador plata que el centro ya tiene, y eso es lo que termina en un debito masivo.
		assertNoPresentable(obligacionCon("saldo", BigDecimal.ZERO.setScale(2)),
				HallazgoDeValidacion.SIN_SALDO);
	}

	@Test
	@DisplayName("una deuda de otro financiador no entra en el lote")
	void obligacionDeOtroFinanciadorNoSePresenta() {
		// Un lote se le manda a uno solo. Mezclarlos hace que la obra social reciba y rechace
		// prestaciones que no le corresponden, y el rechazo se lo come el centro.
		assertNoPresentable(obligacionCon("financiadorId", 99L),
				HallazgoDeValidacion.FINANCIADOR_DISTINTO);
	}

	@Test
	@DisplayName("una deuda del paciente no se le reclama al financiador")
	void obligacionDelPacienteNoSePresenta() {
		Obligacion delPaciente = new Obligacion(
				ORG_ID, SEDE_ID, 501L, 128L, Responsable.PACIENTE,
				new BigDecimal("85000.00"), "ARS", 42L, "Sesion 8",
				Instant.parse("2026-08-14T15:00:00Z"));
		ReflectionTestUtils.setField(delPaciente, "id", 9001L);

		// El responsable se mira ANTES que el financiador: una obligacion del paciente no tiene
		// financiador, y sin este control pasaria el equals contra null y se reclamaria igual.
		// Y tiene su propio hallazgo: el remedio es el opuesto al de FINANCIADOR_DISTINTO —esta
		// deuda se le cobra al paciente; aquella va al lote de otro financiador—.
		assertNoPresentable(delPaciente, HallazgoDeValidacion.DEUDA_DEL_PACIENTE);
	}

	@Test
	@DisplayName("una prestacion de otra sede no entra: el convenio es contextual a la sede")
	void obligacionDeOtraSedeNoSePresenta() {
		assertNoPresentable(obligacionCon("consultorioId", 77L), HallazgoDeValidacion.SEDE_DISTINTA);
	}

	@Test
	@DisplayName("una deuda en otra moneda no entra: el total sumaria pesos con dolares")
	void obligacionEnOtraMonedaNoSePresenta() {
		assertNoPresentable(obligacionCon("moneda", "USD"), HallazgoDeValidacion.MONEDA_DISTINTA);
	}

	@Test
	@DisplayName("a un lote confirmado no se le agrega una prestacion: se abre otro")
	void agregarSobreLoteConfirmado() {
		given(presentaciones.findByIdInScope(ORG_ID, SEDE_ID, PRESENTACION_ID))
				.willReturn(Optional.of(confirmada(new BigDecimal("85000.00"), new BigDecimal("85000.00"))));

		// El total presentado esta congelado y ya viajo al financiador. Agregarle una prestacion
		// despues haria que el lote impreso y el lote guardado dejen de ser el mismo documento.
		assertThatThrownBy(() -> service.agregarItem(actor, SEDE_ID, PRESENTACION_ID, 9001L))
				.isInstanceOf(PresentacionNoEditableException.class)
				.hasMessageContaining("FACTURADA");

		verify(items, never()).save(any());
	}

	@Test
	@DisplayName("quitar una prestacion del borrador recalcula el total del lote")
	void quitarItemRecalcula() {
		Presentacion lote = borrador();
		lote.recalcularTotal(new BigDecimal("100000.00"));
		given(presentaciones.findByIdInScope(ORG_ID, SEDE_ID, PRESENTACION_ID))
				.willReturn(Optional.of(lote));
		PresentacionItem item = item(55010L, 9001L, new BigDecimal("15000.00"));
		given(items.findByIdEnLaPresentacion(ORG_ID, PRESENTACION_ID, 55010L))
				.willReturn(Optional.of(item));
		given(items.sumarPresentado(PRESENTACION_ID)).willReturn(new BigDecimal("85000.00"));

		service.quitarItem(actor, SEDE_ID, PRESENTACION_ID, 55010L);

		// El total se relee de la suma de los items y no se resta en memoria: restar a mano deja el
		// encabezado y el detalle contando cosas distintas en cuanto aparezca el segundo camino.
		verify(items).borrarDelBorrador(item);
		assertThat(lote.getTotalPresentado()).isEqualByComparingTo("85000.00");
		assertThat(lote.getSaldo()).isEqualByComparingTo("85000.00");
	}

	@Test
	@DisplayName("quitar un item que no es de este lote responde 404")
	void quitarItemAjeno() {
		given(presentaciones.findByIdInScope(ORG_ID, SEDE_ID, PRESENTACION_ID))
				.willReturn(Optional.of(borrador()));
		given(items.findByIdEnLaPresentacion(ORG_ID, PRESENTACION_ID, 55010L))
				.willReturn(Optional.empty());

		// La busqueda lleva el lote en el WHERE: un itemId valido de OTRA presentacion no se borra
		// desde esta, aunque el actor tenga permiso sobre las dos.
		assertThatThrownBy(() -> service.quitarItem(actor, SEDE_ID, PRESENTACION_ID, 55010L))
				.isInstanceOf(PresentacionItemNotAccessibleException.class);

		verify(items, never()).borrarDelBorrador(any());
	}

	@Test
	@DisplayName("de un lote confirmado no se quita un item: se debita, que deja motivo y actor")
	void quitarSobreLoteConfirmado() {
		given(presentaciones.findByIdInScope(ORG_ID, SEDE_ID, PRESENTACION_ID))
				.willReturn(Optional.of(confirmada(new BigDecimal("85000.00"), new BigDecimal("85000.00"))));

		// Cambiar lo que se reclamo despues de reclamarlo, sin que el financiador se entere, no es
		// una correccion: es reescribir el reclamo y perder la evidencia de que se hizo.
		assertThatThrownBy(() -> service.quitarItem(actor, SEDE_ID, PRESENTACION_ID, 55010L))
				.isInstanceOf(PresentacionNoEditableException.class);

		verify(items, never()).borrarDelBorrador(any());
	}

	// =================================================================================
	// Validacion y confirmacion
	// =================================================================================

	@Test
	@DisplayName("un lote vacio no es confirmable aunque no tenga ningun hallazgo")
	void validarLoteVacio() {
		given(presentaciones.findByIdInScope(ORG_ID, SEDE_ID, PRESENTACION_ID))
				.willReturn(Optional.of(borrador()));
		given(items.findDeLaPresentacion(PRESENTACION_ID)).willReturn(List.of());

		ValidacionDePresentacion validacion = service.validar(actor, SEDE_ID, PRESENTACION_ID);

		// Sin este control, "cero hallazgos" leeria como "listo para enviar" y el centro mandaria un
		// lote vacio que consume un numero de la serie sin reclamar nada.
		assertThat(validacion.confirmable()).isFalse();
		assertThat(validacion.hallazgos()).isEmpty();
	}

	@Test
	@DisplayName("el periodo se evalua contra la fecha congelada del item y no contra la obligacion")
	void validarDetectaFueraDelPeriodo() {
		given(presentaciones.findByIdInScope(ORG_ID, SEDE_ID, PRESENTACION_ID))
				.willReturn(Optional.of(borrador()));
		PresentacionItem deSeptiembre = itemConFecha(
				55010L, 9001L, new BigDecimal("85000.00"), LocalDate.of(2026, 9, 2));
		given(items.findDeLaPresentacion(PRESENTACION_ID)).willReturn(List.of(deSeptiembre));
		given(obligaciones.findByIdInScope(ORG_ID, SEDE_ID, 9001L))
				.willReturn(Optional.of(obligacionDeFinanciador()));

		ValidacionDePresentacion validacion = service.validar(actor, SEDE_ID, PRESENTACION_ID);

		// Es la fecha que el lote va a imprimir. Evaluar la de la obligacion —un Instant UTC— haria
		// que una prestacion de las 21:30 del 31 de agosto en Cordoba caiga en septiembre.
		assertThat(validacion.confirmable()).isFalse();
		assertThat(validacion.hallazgos())
				.extracting(ValidacionDePresentacion.Reparo::hallazgo)
				.containsExactly(HallazgoDeValidacion.FUERA_DEL_PERIODO);
		assertThat(validacion.hallazgos().get(0).obligacionId()).isEqualTo(9001L);
	}

	@Test
	@DisplayName("validar ignora los items debitados y anulados: ya no ocupan su obligacion")
	void validarIgnoraLosQueNoOcupan() {
		given(presentaciones.findByIdInScope(ORG_ID, SEDE_ID, PRESENTACION_ID))
				.willReturn(Optional.of(borrador()));

		PresentacionItem debitado = itemConFecha(
				55010L, 9001L, new BigDecimal("15000.00"), LocalDate.of(2026, 9, 2));
		debitado.debitar(new BigDecimal("15000.00"), "Falta autorizacion", Instant.now(), ACCOUNT_ID);
		PresentacionItem anulado = itemConFecha(
				55011L, 9002L, new BigDecimal("15000.00"), LocalDate.of(2026, 9, 2));
		anulado.anular();
		given(items.findDeLaPresentacion(PRESENTACION_ID)).willReturn(List.of(debitado, anulado));

		ValidacionDePresentacion validacion = service.validar(actor, SEDE_ID, PRESENTACION_ID);

		// Los dos estan fuera del periodo y ninguno genera reparo: reclamarle al administrativo que
		// arregle una prestacion que el financiador ya rechazo lo manda a corregir lo incorregible.
		assertThat(validacion.hallazgos()).isEmpty();
		verify(obligaciones, never()).findByIdInScope(anyLong(), anyLong(), anyLong());
	}

	@Test
	@DisplayName("confirmar un lote vacio no consume un numero de la serie")
	void confirmarLoteVacio() {
		given(presentaciones.findByIdInScope(ORG_ID, SEDE_ID, PRESENTACION_ID))
				.willReturn(Optional.of(borrador()));
		given(items.findDeLaPresentacion(PRESENTACION_ID)).willReturn(List.of());

		assertThatThrownBy(() -> service.confirmar(actor, SEDE_ID, PRESENTACION_ID))
				.isInstanceOf(PresentacionVaciaException.class);

		// El numero se pide DESPUES de validar, por lo mismo que 06.05 evalua la idempotencia antes
		// del numerador: un hueco en la serie de una obra social no se puede explicar seis meses
		// despues, y la serie es lo que el financiador usa para reclamar lo que no recibio.
		verify(numerador, never()).incrementar(anyLong(), anyLong(), anyLong());
		verify(numeradorIniciador, never()).asegurar(anyLong(), anyLong(), anyLong());
	}

	@Test
	@DisplayName("confirmar revalida del lado del servidor y no consume numero si hay hallazgos")
	void confirmarConHallazgos() {
		given(presentaciones.findByIdInScope(ORG_ID, SEDE_ID, PRESENTACION_ID))
				.willReturn(Optional.of(borrador()));
		given(items.findDeLaPresentacion(PRESENTACION_ID)).willReturn(
				List.of(item(55010L, 9001L, new BigDecimal("85000.00"))));
		given(obligaciones.findByIdInScope(ORG_ID, SEDE_ID, 9001L))
				.willReturn(Optional.of(obligacionCon("moneda", "USD")));

		// El mensaje solo cuenta cuantos son: la lista entera viaja aparte, en getHallazgos, que es
		// lo que el handler publica en el problem detail. Devolver solo el primero obligaria al
		// administrativo a reintentar tantas veces como items rotos haya.
		PresentacionConHallazgosException error = catchThrowableOfType(
				PresentacionConHallazgosException.class,
				() -> service.confirmar(actor, SEDE_ID, PRESENTACION_ID));

		assertThat(error.getHallazgos()).containsExactly("9001:MONEDA_DISTINTA");

		// El frontend nunca es autoridad: que la pantalla haya mostrado el lote como confirmable no
		// prueba que siga siendolo cuando llega el POST.
		verify(numerador, never()).incrementar(anyLong(), anyLong(), anyLong());
	}

	// =================================================================================
	// Factura
	// =================================================================================

	@Test
	@DisplayName("registrar la factura no la genera ni la numera: la copia y pasa a FACTURADA")
	void registrarFactura() {
		Presentacion lote = enviada(new BigDecimal("85000.00"));
		given(presentaciones.findByIdInScope(ORG_ID, SEDE_ID, PRESENTACION_ID))
				.willReturn(Optional.of(lote));
		given(presentaciones.existeFactura(ORG_ID, FINANCIADOR_ID, "0001-00004521")).willReturn(false);

		PresentacionView vista = service.registrarFactura(
				actor, SEDE_ID, PRESENTACION_ID,
				new PresentacionCommands.Factura("0001-00004521", LocalDate.of(2026, 9, 5)));

		// El comprobante se emite fuera de AKINE. Si el sistema lo numerara, el numero del sistema y
		// el del talonario fiscal divergirian en el primer comprobante emitido a mano.
		assertThat(vista.facturaNumero()).isEqualTo("0001-00004521");
		assertThat(vista.estado()).isEqualTo(EstadoPresentacion.FACTURADA);

		// Facturar no cobra: el saldo del lote queda igual que antes.
		assertThat(vista.saldo()).isEqualByComparingTo("85000.00");
		assertThat(auditado().eventType()).isEqualTo(AuditEvents.PRESENTACION_FACTURADA);
	}

	@Test
	@DisplayName("el mismo numero de factura no se asocia a dos lotes del mismo financiador")
	void facturaDuplicada() {
		Presentacion lote = enviada(new BigDecimal("85000.00"));
		given(presentaciones.findByIdInScope(ORG_ID, SEDE_ID, PRESENTACION_ID))
				.willReturn(Optional.of(lote));
		given(presentaciones.existeFactura(ORG_ID, FINANCIADOR_ID, "0001-00004521")).willReturn(true);

		// La consulta previa no es el mecanismo —lo hace cumplir el unique de V56— sino lo que
		// permite responder un 409 legible en vez de dejar reventar la constraint, que ademas deja
		// la transaccion marcada para rollback y hace fallar cualquier consulta posterior.
		assertThatThrownBy(() -> service.registrarFactura(
				actor, SEDE_ID, PRESENTACION_ID,
				new PresentacionCommands.Factura("0001-00004521", LocalDate.of(2026, 9, 5))))
				.isInstanceOf(FacturaDuplicadaException.class);

		assertThat(lote.getEstado()).isEqualTo(EstadoPresentacion.PRESENTADA);
		verify(auditTrail, never()).record(any());
	}

	@Test
	@DisplayName("un borrador no se factura: primero hay que haberlo enviado")
	void facturarBorrador() {
		given(presentaciones.findByIdInScope(ORG_ID, SEDE_ID, PRESENTACION_ID))
				.willReturn(Optional.of(borrador()));

		// Facturar algo que nadie recibio deja un comprobante fiscal emitido contra un reclamo que
		// no existe, y el estado del lote dejaria de decir en que momento del circuito esta.
		assertThatThrownBy(() -> service.registrarFactura(
				actor, SEDE_ID, PRESENTACION_ID,
				new PresentacionCommands.Factura("0001-00004521", LocalDate.of(2026, 9, 5))))
				.isInstanceOf(PresentacionEstadoInvalidoException.class)
				.hasMessageContaining("BORRADOR");
	}

	// =================================================================================
	// Debitos
	// =================================================================================

	@Test
	@DisplayName("un debito deja motivo, actor e instante y no borra la prestacion")
	void debitarRegistraElRechazo() {
		Presentacion lote = confirmada(new BigDecimal("100000.00"), new BigDecimal("100000.00"));
		given(presentaciones.findByIdInScope(ORG_ID, SEDE_ID, PRESENTACION_ID))
				.willReturn(Optional.of(lote));
		PresentacionItem item = item(55010L, 9001L, new BigDecimal("15000.00"));
		given(items.findByIdEnLaPresentacion(ORG_ID, PRESENTACION_ID, 55010L))
				.willReturn(Optional.of(item));
		given(presentaciones.registrarDebito(ORG_ID, PRESENTACION_ID, new BigDecimal("15000.00")))
				.willReturn(1);

		PresentacionItemView vista = service.debitar(
				actor, SEDE_ID, PRESENTACION_ID, 55010L,
				new PresentacionCommands.Debito(new BigDecimal("15000.00"), "Falta autorizacion"));

		// RN-M21-004: el rechazo no elimina la sesion original y no perdona la deuda. La obligacion
		// queda pendiente y liberada para otro lote, y el motivo viaja al registro de auditoria
		// porque es la unica explicacion de por que esa plata nunca entro.
		assertThat(vista.estado()).isEqualTo(EstadoItemPresentacion.DEBITADO);
		assertThat(vista.motivoDebito()).isEqualTo("Falta autorizacion");
		verify(cobros, never()).descontarSaldo(anyLong(), anyLong(), any());

		assertThat(auditado().eventType()).isEqualTo(AuditEvents.PRESENTACION_ITEM_DEBITADO);
		assertThat(auditado().reason()).isEqualTo("Falta autorizacion");
	}

	@Test
	@DisplayName("el saldo del lote se mueve antes de marcar el item como debitado")
	void debitarNoMarcaElItemSiElUpdateFalla() {
		Presentacion lote = confirmada(new BigDecimal("100000.00"), new BigDecimal("5000.00"));
		given(presentaciones.findByIdInScope(ORG_ID, SEDE_ID, PRESENTACION_ID))
				.willReturn(Optional.of(lote));
		PresentacionItem item = item(55010L, 9001L, new BigDecimal("15000.00"));
		given(items.findByIdEnLaPresentacion(ORG_ID, PRESENTACION_ID, 55010L))
				.willReturn(Optional.of(item));
		given(presentaciones.registrarDebito(ORG_ID, PRESENTACION_ID, new BigDecimal("15000.00")))
				.willReturn(0);

		assertThatThrownBy(() -> service.debitar(
				actor, SEDE_ID, PRESENTACION_ID, 55010L,
				new PresentacionCommands.Debito(new BigDecimal("15000.00"), "Rechazo parcial")))
				.isInstanceOf(PresentacionSaldoInsuficienteException.class);

		// Es el orden que importa: el UPDATE condicional es lo unico que puede fallar por una
		// condicion del motor, asi que va primero. Al reves quedaria un item marcado como debitado
		// por un debito que el total del lote nunca registro, y los dos numeros no cuadrarian.
		assertThat(item.getEstado()).isEqualTo(EstadoItemPresentacion.INCLUIDO);
		assertThat(item.getImporteDebitado()).isEqualByComparingTo("0.00");
	}

	@Test
	@DisplayName("un debito mayor que lo presentado por esa prestacion se rechaza")
	void debitarMasDeLoPresentadoPorElItem() {
		// El lote tiene saldo de sobra: lo que no entra es el importe DEL ITEM. Son dos limites
		// distintos y los dos tienen que estar, porque debitar 50.000 de una prestacion de 15.000
		// le declararia al financiador un rechazo por plata que nunca se le reclamo por ese concepto.
		Presentacion lote = confirmada(new BigDecimal("100000.00"), new BigDecimal("100000.00"));
		given(presentaciones.findByIdInScope(ORG_ID, SEDE_ID, PRESENTACION_ID))
				.willReturn(Optional.of(lote));
		PresentacionItem item = item(55010L, 9001L, new BigDecimal("15000.00"));
		given(items.findByIdEnLaPresentacion(ORG_ID, PRESENTACION_ID, 55010L))
				.willReturn(Optional.of(item));

		assertThatThrownBy(() -> service.debitar(
				actor, SEDE_ID, PRESENTACION_ID, 55010L,
				new PresentacionCommands.Debito(new BigDecimal("50000.00"), "Rechazo")))
				.isInstanceOf(ImporteDeDebitoInvalidoException.class);

		// El limite del item se valida ANTES del UPDATE condicional: el saldo del lote no se toca.
		// Antes se validaba despues y solo lo salvaba el rollback; y si el importe superaba tambien
		// el saldo del lote, el operador recibia un 409 de saldo en vez del 400 que corresponde.
		verify(presentaciones, never()).registrarDebito(anyLong(), anyLong(), any());
		assertThat(item.getEstado()).isEqualTo(EstadoItemPresentacion.INCLUIDO);
		verify(auditTrail, never()).record(any());
	}

	@Test
	@DisplayName("un item ya debitado se rechaza sin mover el saldo del lote")
	void debitarDosVecesNoMueveElSaldo() {
		Presentacion lote = confirmada(new BigDecimal("100000.00"), new BigDecimal("100000.00"));
		given(presentaciones.findByIdInScope(ORG_ID, SEDE_ID, PRESENTACION_ID))
				.willReturn(Optional.of(lote));
		PresentacionItem item = item(55010L, 9001L, new BigDecimal("15000.00"));
		item.debitar(new BigDecimal("15000.00"), "Primer rechazo", Instant.now(), ACCOUNT_ID);
		given(items.findByIdEnLaPresentacion(ORG_ID, PRESENTACION_ID, 55010L))
				.willReturn(Optional.of(item));

		assertThatThrownBy(() -> service.debitar(
				actor, SEDE_ID, PRESENTACION_ID, 55010L,
				new PresentacionCommands.Debito(new BigDecimal("15000.00"), "Segundo rechazo")))
				.isInstanceOf(ItemNoDebitableException.class);

		verify(presentaciones, never()).registrarDebito(anyLong(), anyLong(), any());
	}

	@Test
	@DisplayName("cero filas sobre un lote ya conciliado se traduce a estado invalido, no a saldo")
	void debitarReleeParaDistinguirElMotivo() {
		Presentacion enCurso = confirmada(new BigDecimal("100000.00"), new BigDecimal("100000.00"));
		Presentacion conciliada = conciliada();
		// La primera lectura es la del comando; la segunda es la relectura de moverSaldo, que ve lo
		// que otra transaccion cometio entre medio.
		given(presentaciones.findByIdInScope(ORG_ID, SEDE_ID, PRESENTACION_ID))
				.willReturn(Optional.of(enCurso), Optional.of(conciliada));
		given(items.findByIdEnLaPresentacion(ORG_ID, PRESENTACION_ID, 55010L))
				.willReturn(Optional.of(item(55010L, 9001L, new BigDecimal("15000.00"))));
		given(presentaciones.registrarDebito(ORG_ID, PRESENTACION_ID, new BigDecimal("15000.00")))
				.willReturn(0);

		// El UPDATE condicional filtra por estado Y por saldo, asi que cero filas tiene dos causas
		// posibles. Sin la relectura el sistema le diria "no hay saldo" a alguien cuyo problema es
		// que el lote se cerro: dos mensajes que mandan al administrativo a lugares distintos.
		assertThatThrownBy(() -> service.debitar(
				actor, SEDE_ID, PRESENTACION_ID, 55010L,
				new PresentacionCommands.Debito(new BigDecimal("15000.00"), "Rechazo parcial")))
				.isInstanceOf(PresentacionEstadoInvalidoException.class)
				.hasMessageContaining("CONCILIADA");
	}

	@Test
	@DisplayName("releer tras cero filas es seguro: la transaccion no quedo marcada para rollback")
	void debitarReleeYNoEncuentraElLote() {
		given(presentaciones.findByIdInScope(ORG_ID, SEDE_ID, PRESENTACION_ID))
				.willReturn(Optional.of(confirmada(new BigDecimal("100000.00"), new BigDecimal("100000.00"))),
						Optional.empty());
		given(items.findByIdEnLaPresentacion(ORG_ID, PRESENTACION_ID, 55010L))
				.willReturn(Optional.of(item(55010L, 9001L, new BigDecimal("15000.00"))));
		given(presentaciones.registrarDebito(ORG_ID, PRESENTACION_ID, new BigDecimal("15000.00")))
				.willReturn(0);

		// Un UPDATE de cero filas no marca la transaccion, a diferencia de un flush fallido por
		// constraint: esa diferencia es la que permite consultar despues del fallo en vez de morir
		// con UnexpectedRollbackException al commitear. Este repositorio ya lo pago cuatro veces.
		assertThatThrownBy(() -> service.debitar(
				actor, SEDE_ID, PRESENTACION_ID, 55010L,
				new PresentacionCommands.Debito(new BigDecimal("15000.00"), "Rechazo parcial")))
				.isInstanceOf(PresentacionNotAccessibleException.class);
	}

	// =================================================================================
	// Conciliacion y anulacion
	// =================================================================================

	@Test
	@DisplayName("si la obligacion ya se salde por otra via el item se acepta y no se descuenta nada")
	void conciliarConObligacionYaSaldada() {
		Presentacion lote = confirmada(new BigDecimal("85000.00"), BigDecimal.ZERO.setScale(2));
		given(presentaciones.findByIdInScope(ORG_ID, SEDE_ID, PRESENTACION_ID))
				.willReturn(Optional.of(lote));
		PresentacionItem vivo = item(55010L, 9001L, new BigDecimal("85000.00"));
		given(items.findDeLaPresentacion(PRESENTACION_ID)).willReturn(List.of(vivo));
		// Cero filas del UPDATE condicional: el paciente pago la misma deuda en mostrador mientras
		// el lote estaba en la obra social.
		given(cobros.descontarSaldo(ORG_ID, 9001L, new BigDecimal("85000.00"))).willReturn(0);

		PresentacionView vista = service.conciliar(actor, SEDE_ID, PRESENTACION_ID);

		// No es un error y no puede hacer fallar el cierre: el lote ya esta explicado por completo,
		// y abortarlo dejaria un lote con saldo cero que no se puede cerrar por ningun camino.
		assertThat(vivo.getEstado()).isEqualTo(EstadoItemPresentacion.ACEPTADO);
		assertThat(vista.estado()).isEqualTo(EstadoPresentacion.CONCILIADA);

		// Y no se toca el estado de la obligacion: descontar cero y recalcular igual pondria en
		// PAGADA una deuda por un pago que esta transaccion no hizo.
		verify(cobros, never()).actualizarEstadoPorSaldo(anyLong(), anyLong());
	}

	@Test
	@DisplayName("un borrador no se concilia: nadie lo recibio, no hay nada que explicar")
	void conciliarBorrador() {
		given(presentaciones.findByIdInScope(ORG_ID, SEDE_ID, PRESENTACION_ID))
				.willReturn(Optional.of(borrador()));

		// Es el unico momento en que las obligaciones se saldan. Dejarlo correr sobre un borrador
		// marcaria como cobradas prestaciones que ni se reclamaron.
		assertThatThrownBy(() -> service.conciliar(actor, SEDE_ID, PRESENTACION_ID))
				.isInstanceOf(PresentacionEstadoInvalidoException.class)
				.hasMessageContaining("BORRADOR");

		verify(cobros, never()).descontarSaldo(anyLong(), anyLong(), any());
	}

	@Test
	@DisplayName("anular el borrador no borra: pasa los items a ANULADO y libera sus obligaciones")
	void anularBorrador() {
		Presentacion lote = borrador();
		given(presentaciones.findByIdInScope(ORG_ID, SEDE_ID, PRESENTACION_ID))
				.willReturn(Optional.of(lote));
		PresentacionItem item = item(55010L, 9001L, new BigDecimal("85000.00"));
		given(items.findDeLaPresentacion(PRESENTACION_ID)).willReturn(List.of(item));

		PresentacionView vista = service.anular(actor, SEDE_ID, PRESENTACION_ID, "Lote armado mal");

		// La fila del item queda: el estado ANULADO pone la columna generada ocupa_marca en NULL, lo
		// que libera la obligacion para otro lote, y conserva la evidencia de que se penso
		// presentar eso. Borrarla perderia las dos cosas.
		assertThat(item.getEstado()).isEqualTo(EstadoItemPresentacion.ANULADO);
		verify(items, never()).borrarDelBorrador(any());

		assertThat(vista.estado()).isEqualTo(EstadoPresentacion.ANULADA);
		assertThat(vista.motivoAnulacion()).isEqualTo("Lote armado mal");
		assertThat(auditado().reason()).isEqualTo("Lote armado mal");
	}

	@Test
	@DisplayName("un lote enviado no se anula: el rechazo total son debitos sobre todos sus items")
	void anularLoteEnviado() {
		Presentacion lote = enviada(new BigDecimal("85000.00"));
		given(presentaciones.findByIdInScope(ORG_ID, SEDE_ID, PRESENTACION_ID))
				.willReturn(Optional.of(lote));

		// Una presentacion confirmada existe del otro lado del mostrador. Hacerla desaparecer
		// borraria la unica evidencia de que se reclamo, y con ella el derecho a insistir.
		assertThatThrownBy(() -> service.anular(actor, SEDE_ID, PRESENTACION_ID, "Me equivoque"))
				.isInstanceOf(PresentacionNoEditableException.class);

		assertThat(lote.getEstado()).isEqualTo(EstadoPresentacion.PRESENTADA);
	}

	// =================================================================================
	// Consultas
	// =================================================================================

	@Test
	@DisplayName("la bandeja resuelve un nombre por financiador y no uno por fila")
	void buscarCacheaElNombre() {
		given(presentaciones.buscar(
				ORG_ID, SEDE_ID, "PRESENTADA", null, null, null, 200, 0))
				.willReturn(List.of(
						enviada(new BigDecimal("85000.00")),
						enviada(new BigDecimal("12000.00"))));
		given(acceso.nombreDe(ORG_ID, FINANCIADOR_ID)).willReturn("OSDE");

		List<PresentacionView> bandeja = service.buscar(
				actor, SEDE_ID, "PRESENTADA", null, null, null, 5000, -3);

		assertThat(bandeja).hasSize(2)
				.allSatisfy(vista -> assertThat(vista.financiadorNombre()).isEqualTo("OSDE"));

		// Una bandeja de cien lotes de tres obras sociales haria cien viajes al spi de contracting
		// para responder tres veces lo mismo, y el spi es un borde de modulo, no un campo local.
		verify(acceso, times(1)).nombreDe(ORG_ID, FINANCIADOR_ID);

		// El limite se acota a 200 y el desplazamiento negativo a cero: un cliente no fija el techo
		// de una consulta del servidor.
		verify(presentaciones).buscar(ORG_ID, SEDE_ID, "PRESENTADA", null, null, null, 200, 0);

		// Los listados no arman la lista de items: el detalle la trae y la bandeja no la necesita.
		assertThat(bandeja.get(0).items()).isNull();
	}

	@Test
	@DisplayName("la bandeja de elegibles acota el limite por abajo y el desplazamiento negativo")
	void elegiblesAcotaLaPaginacion() {
		LocalDate desde = LocalDate.of(2026, 8, 1);
		LocalDate hasta = LocalDate.of(2026, 8, 31);

		service.elegibles(actor, SEDE_ID, FINANCIADOR_ID, desde, hasta, 0, -10);

		// Un limite de cero devolveria siempre vacio y pareceria que no hay nada que presentar, que
		// es exactamente el sintoma mas caro de diagnosticar en esta pantalla.
		verify(obligaciones).findElegiblesParaPresentar(
				ORG_ID, SEDE_ID, FINANCIADOR_ID, desde, hasta, 1, 0);
	}

	@Test
	@DisplayName("el detalle trae los items del lote y el nombre vivo del financiador")
	void detalleTraeLosItems() {
		given(presentaciones.findByIdInScope(ORG_ID, SEDE_ID, PRESENTACION_ID))
				.willReturn(Optional.of(confirmada(new BigDecimal("85000.00"), new BigDecimal("85000.00"))));
		given(items.findDeLaPresentacion(PRESENTACION_ID))
				.willReturn(List.of(item(55010L, 9001L, new BigDecimal("85000.00"))));
		// El nombre no se congela en la presentacion: un financiador que cambio de razon social se
		// lee con el nombre de hoy, y los lotes historicos siguen leyendose aunque ya no sea operable.
		given(acceso.nombreDe(ORG_ID, FINANCIADOR_ID)).willReturn("OSDE");

		PresentacionView vista = service.detalle(actor, SEDE_ID, PRESENTACION_ID);

		assertThat(vista.financiadorNombre()).isEqualTo("OSDE");
		assertThat(vista.items()).hasSize(1);
		assertThat(vista.items().get(0).concepto()).isEqualTo("Sesion 8");
	}

	// =================================================================================
	// Fixtures
	// =================================================================================

	/** Intenta incluir la obligacion dada y espera que el 409 nombre el hallazgo. */
	private void assertNoPresentable(Obligacion obligacion, HallazgoDeValidacion esperado) {
		given(presentaciones.findByIdInScope(ORG_ID, SEDE_ID, PRESENTACION_ID))
				.willReturn(Optional.of(borrador()));
		given(obligaciones.findByIdInScope(ORG_ID, SEDE_ID, 9001L)).willReturn(Optional.of(obligacion));

		// El hallazgo viaja con motivo y no como un "no entra" a secas: los seis casos mandan al
		// administrativo a lugares distintos y colapsarlos lo obligaria a adivinar cual es.
		assertThatThrownBy(() -> service.agregarItem(actor, SEDE_ID, PRESENTACION_ID, 9001L))
				.isInstanceOf(ObligacionNoPresentableException.class)
				.hasMessageContaining(esperado.name());

		verify(items, never()).save(any());
	}

	private AuditEntry auditado() {
		ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail).record(captor.capture());
		return captor.getValue();
	}

	private PresentacionCommands.Alta alta(List<Long> obligacionIds) {
		return new PresentacionCommands.Alta(
				FINANCIADOR_ID, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), "ARS",
				obligacionIds);
	}

	private FinanciadorSnapshot financiador(String nombre) {
		return new FinanciadorSnapshot(FINANCIADOR_ID, ORG_ID, "OSD", nombre, "OBRA_SOCIAL",
				"30-11111111-1", true);
	}

	private Presentacion borrador() {
		Presentacion presentacion = new Presentacion(
				ORG_ID, SEDE_ID, FINANCIADOR_ID,
				LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), "ARS",
				Instant.parse("2026-09-01T10:00:00Z"), ACCOUNT_ID);
		ReflectionTestUtils.setField(presentacion, "id", PRESENTACION_ID);
		return presentacion;
	}

	private Presentacion confirmada(BigDecimal presentado, BigDecimal saldo) {
		Presentacion presentacion = borrador();
		presentacion.confirmar(48, Instant.parse("2026-09-02T10:00:00Z"), ACCOUNT_ID);
		presentacion.registrarFactura(
				"0001-00004521", LocalDate.of(2026, 9, 5),
				Instant.parse("2026-09-05T10:00:00Z"), ACCOUNT_ID);
		ReflectionTestUtils.setField(presentacion, "totalPresentado", presentado);
		ReflectionTestUtils.setField(presentacion, "saldo", saldo);
		return presentacion;
	}

	/** PRESENTADA y no FACTURADA: la factura no es un paso obligatorio del circuito. */
	private Presentacion enviada(BigDecimal presentado) {
		Presentacion presentacion = borrador();
		presentacion.confirmar(48, Instant.parse("2026-09-02T10:00:00Z"), ACCOUNT_ID);
		ReflectionTestUtils.setField(presentacion, "totalPresentado", presentado);
		ReflectionTestUtils.setField(presentacion, "saldo", presentado);
		return presentacion;
	}

	private Presentacion conciliada() {
		Presentacion presentacion = confirmada(new BigDecimal("100000.00"), BigDecimal.ZERO.setScale(2));
		presentacion.conciliar(Instant.parse("2026-09-20T10:00:00Z"), ACCOUNT_ID);
		return presentacion;
	}

	/** La obligacion canonica con UN campo cambiado, para aislar cada hallazgo de RF-M21-003. */
	private Obligacion obligacionCon(String campo, Object valor) {
		Obligacion obligacion = obligacionDeFinanciador();
		ReflectionTestUtils.setField(obligacion, campo, valor);
		return obligacion;
	}

	private PresentacionItem itemConFecha(
			long id, long obligacionId, BigDecimal importe, LocalDate fechaPrestacion) {

		Obligacion obligacion = obligacionDeFinanciador();
		ReflectionTestUtils.setField(obligacion, "id", obligacionId);
		ReflectionTestUtils.setField(obligacion, "saldo", importe);

		PresentacionItem item = new PresentacionItem(
				ORG_ID, PRESENTACION_ID, obligacion, fechaPrestacion,
				Instant.parse("2026-09-01T10:00:00Z"));
		ReflectionTestUtils.setField(item, "id", id);
		return item;
	}

	private PresentacionItem item(long id, long obligacionId, BigDecimal importe) {
		Obligacion obligacion = obligacionDeFinanciador();
		ReflectionTestUtils.setField(obligacion, "id", obligacionId);
		ReflectionTestUtils.setField(obligacion, "saldo", importe);

		PresentacionItem item = new PresentacionItem(
				ORG_ID, PRESENTACION_ID, obligacion, LocalDate.of(2026, 8, 14),
				Instant.parse("2026-09-01T10:00:00Z"));
		ReflectionTestUtils.setField(item, "id", id);
		return item;
	}

	private Obligacion obligacionDeFinanciador() {
		Obligacion obligacion = new Obligacion(
				ORG_ID, SEDE_ID, 501L, 128L, Responsable.FINANCIADOR, FINANCIADOR_ID,
				new BigDecimal("85000.00"), "ARS", 42L, "Sesion 8",
				Instant.parse("2026-08-14T15:00:00Z"));
		ReflectionTestUtils.setField(obligacion, "id", 9001L);
		return obligacion;
	}
}
