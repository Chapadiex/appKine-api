package com.akine.identity.application;

import com.akine.identity.IdentityFixtures;
import com.akine.identity.domain.Cuenta;
import com.akine.identity.domain.port.CuentaRepositoryPort;
import com.akine.organization.spi.DirectMembershipCommand;
import com.akine.organization.spi.MembershipProvisioning;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * El alta directa de colaboradores, del lado en que se decide.
 *
 * <p>Tres cosas se prueban aca y no en el slice HTTP, porque las tres son decisiones de
 * {@code application} y contra un servicio mockeado se estaria verificando el mock:
 *
 * <ol>
 *   <li><b>El permiso se exige ANTES de tocar el email.</b> Es el orden lo que evita que
 *       cualquier cuenta autenticada convierta el endpoint en un oraculo abierto: si algun dia
 *       alguien "optimiza" resolviendo el email primero para fallar rapido, este test se cae.
 *       Se verifica con {@link InOrder} y no mirando el resultado, porque el resultado es el
 *       mismo en los dos ordenes: lo que cambia es quien puede preguntar.</li>
 *   <li><b>El intento fallido queda auditado.</b> Es una de las dos mitigaciones que hacen
 *       aceptable el 404 del 24/08/2026, y ademas <b>se confirma antes de lanzar</b>: el
 *       {@code TransactionTemplate} propio es lo que evita que el rollback del error se lleve
 *       puesto el rastro.</li>
 *   <li><b>El id que llega a {@code organization} lo resolvio este servicio</b>, no el cliente.
 *       Ese es el motivo por el que el endpoint vive en {@code identity}.</li>
 * </ol>
 */
class DirectMembershipServiceTest {

	private static final long ACTOR_ID = 99L;
	private static final long ORG_ID = 10L;
	private static final long CONSULTORIO_ID = 20L;
	private static final long CUENTA_ID = 77L;
	private static final long MEMBERSHIP_ID = 42L;

	private static final String EMAIL_TIPEADO = "Kine@Centro.Test";
	private static final String EMAIL_NORMALIZADO = "kine@centro.test";
	private static final String MOTIVO = "Incorporacion del kinesiologo de la sede centro";

	private CuentaRepositoryPort cuentaRepository;
	private MembershipProvisioning membershipProvisioning;
	private PermissionGuard permissionGuard;
	private AuditTrail auditTrail;
	private DirectMembershipService servicio;

	private final DirectMembershipService.Actor actor =
			new DirectMembershipService.Actor(ACTOR_ID, ORG_ID, false);

	@BeforeEach
	void setUp() {
		cuentaRepository = mock(CuentaRepositoryPort.class);
		membershipProvisioning = mock(MembershipProvisioning.class);
		permissionGuard = mock(PermissionGuard.class);
		auditTrail = mock(AuditTrail.class);
		servicio = new DirectMembershipService(
				cuentaRepository, membershipProvisioning, permissionGuard, auditTrail,
				transactionManagerDePrueba());
	}

	/**
	 * Gestor de transacciones minimo.
	 *
	 * <p>No hay base en este test: lo que importa es que el {@code TransactionTemplate} ejecute
	 * su bloque, que es donde se escribe el evento. Que ese bloque corra en una transaccion
	 * propia y sobreviva al rollback del 404 es una propiedad del motor, no de este servicio.
	 */
	private static PlatformTransactionManager transactionManagerDePrueba() {
		PlatformTransactionManager gestor = mock(PlatformTransactionManager.class);
		given(gestor.getTransaction(any(TransactionDefinition.class)))
				.willReturn(new SimpleTransactionStatus());
		return gestor;
	}

	/**
	 * Cuenta real, no un mock.
	 *
	 * <p>Lo que se prueba incluye que el id que viaja a {@code organization} salga de la fila
	 * encontrada; con una entity mockeada, ese id lo estaria eligiendo el test.
	 */
	private void conCuenta() {
		Cuenta cuenta = IdentityFixtures.conId(
				new Cuenta(EMAIL_TIPEADO, "Ana", "Gomez", "{hash}fake"), CUENTA_ID);
		given(cuentaRepository.findByEmailNormalizado(EMAIL_NORMALIZADO))
				.willReturn(Optional.of(cuenta));
	}

	@Test
	@DisplayName("resuelve el email y delega el alta con el id que resolvio, no con uno del cliente")
	void alta_exitosa() {
		conCuenta();
		given(membershipProvisioning.createDirect(anyLong(), anyBoolean(), anyLong(), any()))
				.willReturn(MEMBERSHIP_ID);

		long creada = servicio.vincular(
				actor, EMAIL_TIPEADO, CONSULTORIO_ID, "KINESIOLOGO", MOTIVO);

		assertThat(creada).isEqualTo(MEMBERSHIP_ID);

		ArgumentCaptor<DirectMembershipCommand> comando =
				ArgumentCaptor.forClass(DirectMembershipCommand.class);
		verify(membershipProvisioning)
				.createDirect(eq(ACTOR_ID), eq(false), eq(ORG_ID), comando.capture());

		assertThat(comando.getValue().accountId()).isEqualTo(CUENTA_ID);
		assertThat(comando.getValue().consultorioId()).isEqualTo(CONSULTORIO_ID);
		assertThat(comando.getValue().roleCode()).isEqualTo("KINESIOLOGO");
		assertThat(comando.getValue().reason()).isEqualTo(MOTIVO);

		// El camino exitoso no escribe auditoria aca: MEMBERSHIP_CREATED lo escribe
		// organization, dentro de la transaccion que crea la fila.
		verify(auditTrail, never()).record(any());
	}

	@Test
	@DisplayName("el permiso se exige ANTES de resolver el email: sin ese orden es un oraculo abierto")
	void permiso_antes_que_email() {
		conCuenta();

		servicio.vincular(actor, EMAIL_TIPEADO, CONSULTORIO_ID, "KINESIOLOGO", MOTIVO);

		InOrder orden = inOrder(permissionGuard, cuentaRepository);
		orden.verify(permissionGuard).requirePermission(any(PermissionQuery.class));
		orden.verify(cuentaRepository).findByEmailNormalizado(EMAIL_NORMALIZADO);

		ArgumentCaptor<PermissionQuery> consulta =
				ArgumentCaptor.forClass(PermissionQuery.class);
		verify(permissionGuard).requirePermission(consulta.capture());

		assertThat(consulta.getValue().permissionCode()).isEqualTo("colaborador:manage");
		assertThat(consulta.getValue().organizationId()).isEqualTo(ORG_ID);
		// La sede evaluada es la del VINCULO pedido: quien administra la sede A no puede dar de
		// alta en la sede B.
		assertThat(consulta.getValue().consultorioId()).isEqualTo(CONSULTORIO_ID);
	}

	@Test
	@DisplayName("email sin cuenta: audita el intento en el tenant y recien despues lanza el 404")
	void email_sin_cuenta_deja_rastro() {
		given(cuentaRepository.findByEmailNormalizado(EMAIL_NORMALIZADO))
				.willReturn(Optional.empty());

		assertThatThrownBy(() ->
				servicio.vincular(actor, EMAIL_TIPEADO, CONSULTORIO_ID, "KINESIOLOGO", MOTIVO))
				.isInstanceOf(EmailSinCuentaException.class);

		ArgumentCaptor<AuditEntry> evento = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail).record(evento.capture());

		AuditEntry registrado = evento.getValue();
		assertThat(registrado.eventType()).isEqualTo("MEMBERSHIP_ALTA_RECHAZADA");
		// En la auditoria DEL TENANT, y con el actor: es lo que convierte un barrido en una
		// racha visible desde la consulta por actor (RF-M24-003).
		assertThat(registrado.organizationId()).isEqualTo(ORG_ID);
		assertThat(registrado.actorAccountId()).isEqualTo(ACTOR_ID);
		assertThat(registrado.consultorioId()).isEqualTo(CONSULTORIO_ID);
		assertThat(registrado.reason()).isEqualTo(MOTIVO);
		// Sin la direccion tipeada, una racha dice "alguien probo cien veces" y no "alguien
		// barrio la base". Va normalizada, igual que se busco.
		assertThat(registrado.details()).containsEntry("email", EMAIL_NORMALIZADO);
		assertThat(registrado.details()).containsEntry("motivoDelRechazo", "EMAIL_SIN_CUENTA");

		verify(membershipProvisioning, never())
				.createDirect(anyLong(), anyBoolean(), anyLong(), any());
	}

	@Test
	@DisplayName("sin contexto de organizacion es 403, y ni siquiera se mira el email")
	void sin_contexto() {
		DirectMembershipService.Actor platformAdmin =
				new DirectMembershipService.Actor(ACTOR_ID, null, true);

		assertThatThrownBy(() -> servicio.vincular(
				platformAdmin, EMAIL_TIPEADO, null, "KINESIOLOGO", MOTIVO))
				.isInstanceOf(AccessDeniedException.class);

		verify(cuentaRepository, never()).findByEmailNormalizado(anyString());
		verify(permissionGuard, never()).requirePermission(any());
	}
}
