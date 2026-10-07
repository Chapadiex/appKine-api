package com.akine.billing.application;

import com.akine.billing.domain.EstadoObligacion;
import com.akine.billing.domain.Obligacion;
import com.akine.billing.domain.Responsable;
import com.akine.billing.domain.exception.ConsultorioNoAccesibleException;
import com.akine.billing.domain.exception.ObligacionAnuladaException;
import com.akine.billing.domain.exception.ObligacionConCobrosException;
import com.akine.billing.domain.exception.ObligacionNotAccessibleException;
import com.akine.billing.domain.port.ObligacionRepositoryPort;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionDecision;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.person.spi.AlertasDeConsumo;
import com.akine.person.spi.ConsumoARevisar;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Consulta y anulacion de deuda (M18, {@link ObligacionService}).
 *
 * <h2>Que decide la correctitud aca</h2>
 *
 * <ul>
 *   <li><b>El alcance de la cuenta corriente es la ORGANIZACION, no la sede.</b> Es lo unico de
 *       esta clase que cambia lo que el administrativo ve en pantalla: un paciente atendido en dos
 *       sedes del mismo centro debe una sola cuenta, y si la consulta filtrara por sede el
 *       mostrador tendria que sumar de memoria dos listados parciales. Se verifica por el puerto
 *       que se invoca —{@code findDeLaPersona(organizationId, personaId)}, sin consultorio— y no
 *       por el resultado, porque el resultado lo decide la consulta SQL.
 *   <li><b>Cross-tenant es 404 y falta de contexto es 403.</b> Un 403 ante una sede ajena confirma
 *       que existe, y bastaria probar ids consecutivos para censar los tenants del SaaS. Un 401 por
 *       falta de contexto manda al interceptor del frontend a borrar el token: bucle de login.
 *   <li><b>Anular compite con cobrar, y el que llega segundo se tiene que enterar.</b> La version
 *       esperada viaja en el pedido y el servicio la compara antes de tocar el agregado: sin eso,
 *       una anulacion escrita sobre una lectura vieja borraria un cargo que entretanto se cobro.
 *   <li><b>Una deuda con plata imputada no se anula, y el control mira el SALDO, no el estado.</b>
 *       Anular algo ya cobrado deja dinero en la caja sin deuda que lo justifique. Lo decide el
 *       dominio ({@code Obligacion#anular}); aca se verifica que el servicio lo deje decidir y que
 *       <b>no persista nada</b> cuando el dominio rechaza.
 *   <li><b>El motivo de anulacion no es opcional</b> y queda en la fila: una deuda anulada sin
 *       explicacion es exactamente el registro que una auditoria viene a buscar.
 * </ul>
 *
 * <h2>Lo que estos tests NO pueden decir</h2>
 *
 * <p>Son unitarios con puertos dobles, asi que <b>nada de lo que dependa del motor esta probado</b>:
 *
 * <ul>
 *   <li>Que {@code findByIdInScope} y {@code findDeLaPersona} realmente filtren por
 *       {@code organization_id} en su WHERE. Los dobles obedecen la firma, no la consulta: el
 *       aislamiento de tenant real solo lo prueba un IT con dos organizaciones en la misma base.
 *   <li>Que la anulacion y la imputacion de un cobro se serialicen de verdad. Aca la carrera se
 *       simula comparando dos numeros; en produccion la decide la columna {@code @Version} de
 *       Hibernate, que estos tests ni mueven — el {@code save} doble no avanza la version.
 *   <li>Que el {@code CHECK} de V36 impida el saldo negativo, y que el importe viaje como
 *       {@code DECIMAL(12,2)} hasta la columna. Eso vive en la migracion.
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Deuda: consulta y anulacion (M18, ObligacionService)")
class ObligacionServiceTest {

	private static final long ORG = 7L;
	private static final long SEDE = 20L;
	private static final long OTRA_SEDE = 21L;
	private static final long PERSONA = 4100L;
	private static final long OBLIGACION = 8800L;
	private static final long CUENTA = 31L;
	private static final long SESION = 6600L;
	private static final long OFERTA = 55L;
	private static final String MONEDA = "ARS";

	@Mock private ObligacionRepositoryPort obligaciones;
	@Mock private ConsultorioDirectory consultorios;
	@Mock private PermissionGuard permissionGuard;
	@Mock private AlertasDeConsumo alertasDeConsumo;

	private ObligacionService service;

	private final OperatingActor administrativo = new OperatingActor(CUENTA, false, ORG, SEDE);

	@BeforeEach
	void setUp() {
		service = new ObligacionService(obligaciones, consultorios, permissionGuard, alertasDeConsumo);

		given(consultorios.find(ORG, SEDE)).willReturn(Optional.of(
				new ConsultorioSnapshot(SEDE, ORG, "Sede Centro", "America/Argentina/Cordoba", true)));
		given(permissionGuard.requirePermission(any()))
				.willReturn(PermissionDecision.concedida("CONSULTORIO", false));
		// JPA asigna el id al persistir y el doble tiene que hacer lo mismo: si devolviera la
		// entidad sin id, `ObligacionView.de` reventaria al desempaquetarlo a un long y el test
		// representaria un estado —obligacion guardada sin id— que en produccion no existe.
		given(obligaciones.save(any())).willAnswer(invocacion -> {
			Obligacion guardada = invocacion.getArgument(0);
			if (guardada.getId() == null) {
				ReflectionTestUtils.setField(guardada, "id", OBLIGACION);
			}
			return guardada;
		});
	}

	// =================================================================================
	// Alcance: la deuda es de la organizacion, el permiso es de la sede
	// =================================================================================

	@Nested
	@DisplayName("Cuenta corriente del paciente")
	class CuentaCorriente {

		@Test
		@DisplayName("la cuenta corriente se pide por organizacion y persona, nunca por sede")
		void el_alcance_es_la_organizacion() {
			given(obligaciones.findDeLaPersona(ORG, PERSONA)).willReturn(List.of(
					pendiente(SEDE, "8500.00"),
					pendiente(OTRA_SEDE, "3000.00")));

			List<ObligacionView> cuenta = service.deLaPersona(administrativo, SEDE, PERSONA);

			// Las dos filas vuelven aunque una se devengo en otra sede del mismo centro. Partir la
			// cuenta por sede obligaria al administrativo a sumar dos pantallas de memoria antes de
			// decirle a un paciente cuanto debe, y ese es el numero con el que se cobra.
			assertThat(cuenta).extracting(ObligacionView::consultorioId)
					.containsExactly(SEDE, OTRA_SEDE);
			verify(obligaciones).findDeLaPersona(ORG, PERSONA);
		}

		@Test
		@DisplayName("un paciente sin deuda devuelve lista vacia, no null")
		void sin_deuda_lista_vacia() {
			given(obligaciones.findDeLaPersona(ORG, PERSONA)).willReturn(List.of());

			// Un null aca termina en un NPE en la capa REST y en un 500 donde corresponde un 200
			// con lista vacia: "no debe nada" es una respuesta valida, no un error.
			assertThat(service.deLaPersona(administrativo, SEDE, PERSONA)).isEmpty();
		}

		@Test
		@DisplayName("exige cobro:register con la sede del pedido como alcance")
		void exige_el_permiso_con_la_sede_como_alcance() {
			given(obligaciones.findDeLaPersona(ORG, PERSONA)).willReturn(List.of());

			service.deLaPersona(administrativo, SEDE, PERSONA);

			// El alcance evaluado es la SEDE aunque el dato leido sea de la organizacion: quien
			// atiende en una sede no deberia poder mirar la deuda operando desde otra. Si el
			// consultorio viajara null, el guard evaluaria alcance organizacion y el permiso seria
			// mas amplio que la pantalla.
			ArgumentCaptor<PermissionQuery> consulta = ArgumentCaptor.forClass(PermissionQuery.class);
			verify(permissionGuard).requirePermission(consulta.capture());
			assertThat(consulta.getValue().permissionCode()).isEqualTo("cobro:register");
			assertThat(consulta.getValue().accountId()).isEqualTo(CUENTA);
			assertThat(consulta.getValue().organizationId()).isEqualTo(ORG);
			assertThat(consulta.getValue().consultorioId()).isEqualTo(SEDE);
		}

		@Test
		@DisplayName("una sede de otro tenant es 404 y nunca 403, y no llega a evaluar el permiso")
		void sede_ajena_es_404() {
			given(consultorios.find(ORG, OTRA_SEDE)).willReturn(Optional.empty());

			// Un 403 aca confirmaria que la sede existe: bastaria recorrer ids consecutivos para
			// censar las sedes de los demas centros del SaaS.
			assertThatThrownBy(() -> service.deLaPersona(administrativo, OTRA_SEDE, PERSONA))
					.isInstanceOf(ConsultorioNoAccesibleException.class);

			// Y el orden importa: si el permiso se evaluara primero, el rechazo por permiso (403,
			// con fila de auditoria) le diria al atacante que el recurso existe.
			verify(permissionGuard, never()).requirePermission(any());
			verify(obligaciones, never()).findDeLaPersona(anyLong(), anyLong());
		}

		@Test
		@DisplayName("sin contexto de trabajo activo es 403, nunca 401")
		void sin_contexto_es_403() {
			OperatingActor sinContexto = new OperatingActor(CUENTA, false, null, null);

			// Un 401 haria que el interceptor del frontend borre el token y vuelva al login: el
			// usuario esta autenticado, lo que le falta es elegir con que organizacion trabaja.
			assertThatThrownBy(() -> service.deLaPersona(sinContexto, SEDE, PERSONA))
					.isInstanceOf(AccessDeniedException.class);

			verifyNoInteractions(obligaciones, consultorios, permissionGuard);
		}

		@Test
		@DisplayName("un actor nulo tambien es 403 y no revienta con NPE")
		void actor_nulo_es_403() {
			// Vale la pena fijarlo: un NPE seria un 500, y un 500 en el camino de la deuda le dice
			// al cliente "fallo el servidor" cuando lo que falto fue el contexto.
			assertThatThrownBy(() -> service.deLaPersona(null, SEDE, PERSONA))
					.isInstanceOf(AccessDeniedException.class);
		}
	}

	// =================================================================================
	// Ver una deuda
	// =================================================================================

	@Nested
	@DisplayName("Ver una deuda")
	class Ver {

		@Test
		@DisplayName("la vista expone el importe congelado y el saldo como BigDecimal, sin recalcular nada")
		void la_vista_lleva_el_snapshot() {
			Obligacion obligacion = pendiente(SEDE, "8500.00");
			ReflectionTestUtils.setField(obligacion, "saldo", new BigDecimal("2500.00"));
			ReflectionTestUtils.setField(obligacion, "estado", EstadoObligacion.PARCIAL);
			given(obligaciones.findByIdInScope(ORG, SEDE, OBLIGACION)).willReturn(Optional.of(obligacion));

			ObligacionView vista = service.ver(administrativo, SEDE, OBLIGACION);

			// El snapshot es lo que hace auditable la cuenta: editar el precio de la oferta manana
			// no puede cambiar lo que se debe por una sesion de hoy. Y los importes son BigDecimal
			// de punta a punta: con double, 0.1 + 0.2 no da 0.3 y la cuenta no cuadra por centavos
			// que nadie puede explicar seis meses despues.
			assertThat(vista.importeOriginal()).isEqualByComparingTo("8500.00");
			assertThat(vista.snapshotPrecio()).isEqualByComparingTo("8500.00");
			assertThat(vista.saldo()).isEqualByComparingTo("2500.00");
			assertThat(vista.estado()).isEqualTo("PARCIAL");
			assertThat(vista.responsable()).isEqualTo("PACIENTE");
			// Hoy nadie devenga a nombre del financiador —DP-10 lo recorto—, asi que el campo
			// viaja null. Si algun dia deja de ser null sin que se recable el devengado, es un bug.
			assertThat(vista.financiadorId()).isNull();
		}

		@Test
		@DisplayName("una deuda de otra sede o de otro tenant no se puede ver: 404")
		void fuera_de_alcance_es_404() {
			given(obligaciones.findByIdInScope(ORG, SEDE, OBLIGACION)).willReturn(Optional.empty());

			// El puerto no distingue "no existe" de "es de otro": el servicio tampoco debe. Una
			// respuesta distinta por cada caso seria un oraculo de existencia de ids ajenos.
			assertThatThrownBy(() -> service.ver(administrativo, SEDE, OBLIGACION))
					.isInstanceOf(ObligacionNotAccessibleException.class);
		}

		@Test
		@DisplayName("ver una deuda tambien exige el permiso y la sede del tenant")
		void ver_pasa_por_la_misma_puerta() {
			given(consultorios.find(ORG, OTRA_SEDE)).willReturn(Optional.empty());

			assertThatThrownBy(() -> service.ver(administrativo, OTRA_SEDE, OBLIGACION))
					.isInstanceOf(ConsultorioNoAccesibleException.class);

			// Si la lectura puntual se saltara la puerta, alcanzaria con conocer un id para leer
			// una deuda sin permiso: la cuenta corriente filtrada de a una fila.
			verify(obligaciones, never()).findByIdInScope(anyLong(), anyLong(), anyLong());
		}
	}

	// =================================================================================
	// Anular
	// =================================================================================

	@Nested
	@DisplayName("Anular una deuda")
	class Anular {

		@Test
		@DisplayName("anular deja la deuda en ANULADA con saldo cero, motivo, instante y autor")
		void anula_con_motivo() {
			Obligacion obligacion = pendiente(SEDE, "8500.00");
			given(obligaciones.findByIdInScope(ORG, SEDE, OBLIGACION)).willReturn(Optional.of(obligacion));

			ObligacionView vista = service.anular(
					administrativo, SEDE, OBLIGACION, "Error de carga: la sesion se cerro dos veces", 0L);

			// El saldo baja a cero, no la fila: una deuda que desaparece de la base es una cuenta
			// corriente que no cuadra y que nadie puede auditar seis meses despues.
			assertThat(vista.estado()).isEqualTo("ANULADA");
			assertThat(vista.saldo()).isEqualByComparingTo("0.00");
			// El importe original NO se toca: es lo que permite reconstruir que hubo un cargo de
			// 8.500 y que alguien lo anulo, en vez de que no hubiera habido cargo nunca.
			assertThat(vista.importeOriginal()).isEqualByComparingTo("8500.00");
			// El motivo es obligatorio a diferencia de otras bajas del sistema: sin el, nadie puede
			// saber si fue un error de carga, una cortesia o algo peor.
			assertThat(vista.motivoAnulacion())
					.isEqualTo("Error de carga: la sesion se cerro dos veces");
			assertThat(vista.anuladaEn()).isNotNull();
			assertThat(ReflectionTestUtils.getField(obligacion, "anuladaPorCuentaId"))
					.isEqualTo(CUENTA);
			verify(obligaciones).save(obligacion);
		}

		@Test
		@DisplayName("anular la deuda de una sesion alerta sus consumos de autorizacion (DP-13)")
		void anular_alerta_el_consumo() {
			Obligacion obligacion = pendiente(SEDE, "8500.00");
			given(obligaciones.findByIdInScope(ORG, SEDE, OBLIGACION)).willReturn(Optional.of(obligacion));

			service.anular(administrativo, SEDE, OBLIGACION, "Cortesia", 0L);

			ArgumentCaptor<ConsumoARevisar> hecho = ArgumentCaptor.forClass(ConsumoARevisar.class);
			verify(alertasDeConsumo).consumoARevisar(hecho.capture());
			assertThat(hecho.getValue().organizationId()).isEqualTo(ORG);
			assertThat(hecho.getValue().sesionId()).isEqualTo(SESION);
			assertThat(hecho.getValue().obligacionId()).isEqualTo(OBLIGACION);
			assertThat(hecho.getValue().motivo()).isEqualTo("Cortesia");
			assertThat(hecho.getValue().actorCuentaId()).isEqualTo(CUENTA);
		}

		@Test
		@DisplayName("una version distinta de la leida es 409 de concurrencia y no persiste nada")
		void version_vieja_no_anula() {
			Obligacion obligacion = pendiente(SEDE, "8500.00");
			ReflectionTestUtils.setField(obligacion, "version", 3L);
			given(obligaciones.findByIdInScope(ORG, SEDE, OBLIGACION)).willReturn(Optional.of(obligacion));

			// La anulacion compite con la imputacion de un cobro. Si alguien pago mientras esta
			// pantalla miraba la version 1, anular sobre esa lectura borraria un cargo que ya tiene
			// plata aplicada, y el saldo de la caja quedaria sin deuda que lo justifique.
			assertThatThrownBy(() -> service.anular(administrativo, SEDE, OBLIGACION, "motivo", 1L))
					.isInstanceOf(OptimisticLockingFailureException.class)
					.hasMessageContaining("1")
					.hasMessageContaining("3");

			assertThat(obligacion.getEstado()).isEqualTo(EstadoObligacion.PENDIENTE);
			verify(obligaciones, never()).save(any());
			verifyNoInteractions(alertasDeConsumo);
		}

		@Test
		@DisplayName("una deuda con cobros imputados no se anula: el control mira el saldo, no el estado")
		void con_cobros_no_anula() {
			Obligacion obligacion = pendiente(SEDE, "8500.00");
			// Un cobro parcial ya se imputo: el saldo bajo y el importe original quedo igual. El
			// estado podria ser PARCIAL o incluso PAGADA; lo que lo delata es la diferencia.
			ReflectionTestUtils.setField(obligacion, "saldo", new BigDecimal("6000.00"));
			ReflectionTestUtils.setField(obligacion, "estado", EstadoObligacion.PARCIAL);
			given(obligaciones.findByIdInScope(ORG, SEDE, OBLIGACION)).willReturn(Optional.of(obligacion));

			// Lo que corresponde en este caso es una devolucion, que es M19 y tiene su propio
			// registro: anular dejaria 2.500 en la caja sin ninguna deuda que los explique.
			assertThatThrownBy(() -> service.anular(administrativo, SEDE, OBLIGACION, "motivo", 0L))
					.isInstanceOf(ObligacionConCobrosException.class)
					.extracting(error -> ((ObligacionConCobrosException) error).getYaCobrado())
					.isEqualTo(new BigDecimal("2500.00"));

			verify(obligaciones, never()).save(any());
		}

		@Test
		@DisplayName("anular dos veces la misma deuda es 409, no una segunda anulacion silenciosa")
		void anular_dos_veces_es_conflicto() {
			Obligacion obligacion = pendiente(SEDE, "8500.00");
			given(obligaciones.findByIdInScope(ORG, SEDE, OBLIGACION)).willReturn(Optional.of(obligacion));

			service.anular(administrativo, SEDE, OBLIGACION, "primer motivo", 0L);

			// El segundo pedido no puede pisar el motivo ni el autor del primero: si lo hiciera, la
			// auditoria mostraria como anulador a quien solo reintento, y el motivo real se perderia.
			assertThatThrownBy(
					() -> service.anular(administrativo, SEDE, OBLIGACION, "segundo motivo", 0L))
					.isInstanceOf(ObligacionAnuladaException.class);

			assertThat(obligacion.getMotivoAnulacion()).isEqualTo("primer motivo");
		}

		@Test
		@DisplayName("anular una deuda fuera de alcance es 404 y no persiste nada")
		void fuera_de_alcance_no_anula() {
			given(obligaciones.findByIdInScope(ORG, SEDE, OBLIGACION)).willReturn(Optional.empty());

			assertThatThrownBy(() -> service.anular(administrativo, SEDE, OBLIGACION, "motivo", 0L))
					.isInstanceOf(ObligacionNotAccessibleException.class);

			verify(obligaciones, never()).save(any());
		}

		@Test
		@DisplayName("anular sin contexto de trabajo es 403 y no toca el repositorio")
		void sin_contexto_no_anula() {
			OperatingActor sinContexto = new OperatingActor(CUENTA, false, null, null);

			assertThatThrownBy(() -> service.anular(sinContexto, SEDE, OBLIGACION, "motivo", 0L))
					.isInstanceOf(AccessDeniedException.class);

			// La mutacion no puede empezar a leer antes de autorizar: una lectura previa ya seria
			// una fuga, porque el mensaje de error podria distinguir "no existe" de "no podes".
			verifyNoInteractions(obligaciones);
		}

		@Test
		@DisplayName("anular en una sede de otro tenant es 404 y no persiste nada")
		void sede_ajena_no_anula() {
			given(consultorios.find(ORG, OTRA_SEDE)).willReturn(Optional.empty());

			assertThatThrownBy(() -> service.anular(administrativo, OTRA_SEDE, OBLIGACION, "motivo", 0L))
					.isInstanceOf(ConsultorioNoAccesibleException.class);

			verify(obligaciones, never()).save(any());
		}
	}

	// =================================================================================
	// Fixtures
	// =================================================================================

	/**
	 * Una deuda como la devuelve la base: devengada, pendiente y <b>con id</b>.
	 *
	 * <p>El id va por reflexion porque lo asigna JPA al persistir y la entidad no lo expone para
	 * escritura. Sin el, {@code ObligacionView.de} reventaria al desempaquetarlo a un {@code long}.
	 */
	private static Obligacion pendiente(long consultorioId, String importe) {
		Obligacion obligacion = new Obligacion(
				ORG, consultorioId, SESION, PERSONA, Responsable.PACIENTE,
				new BigDecimal(importe), MONEDA, OFERTA, "Sesion de kinesiologia",
				Instant.parse("2027-04-08T13:00:00Z"));
		ReflectionTestUtils.setField(obligacion, "id", OBLIGACION);
		return obligacion;
	}
}
