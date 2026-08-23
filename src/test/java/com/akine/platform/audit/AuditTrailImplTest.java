package com.akine.platform.audit;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import com.akine.platform.domain.AuditEvent;
import com.akine.platform.infrastructure.AuditEventRepository;
import com.akine.platform.infrastructure.audit.AuditTrailImpl;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Verifica las dos garantias de la auditoria (decision T-2): que se escriba DENTRO de la
 * transaccion del llamador, y que no se le cuele un secreto ni contenido clinico.
 *
 * <p>No usa base ni Testcontainers: levanta un contexto Spring minimo con
 * {@code @EnableTransactionManagement} y un gestor de transacciones de mentira. Lo que hay que
 * probar es la <b>propagacion</b>, y eso lo decide el proxy transaccional, no el motor de base.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = AuditTrailImplTest.ContextoDePrueba.class)
class AuditTrailImplTest {

	private static final Instant CUANDO = Instant.parse("2026-08-22T12:00:00Z");

	@Autowired
	private AuditTrail auditTrail;

	@Autowired
	private AuditEventRepository auditEventRepository;

	@Autowired
	private PlatformTransactionManager transactionManager;

	/**
	 * El contexto Spring se comparte entre los metodos de test, y con el, el mock. Sin este
	 * reset las verificaciones arrastran las invocaciones de los tests anteriores.
	 */
	@BeforeEach
	void reiniciarElMock() {
		Mockito.reset(auditEventRepository);
	}

	// =================================================================================
	// Propagacion MANDATORY
	// =================================================================================

	@Test
	@DisplayName("Invocar la auditoria FUERA de una transaccion falla: MANDATORY no abre una propia")
	void sin_transaccion_falla() {
		// Con REQUIRED, la auditoria abriria su propia transaccion y commitearia sola: quedaria
		// un registro diciendo que algo paso aunque la operacion de negocio se hubiera
		// revertido. Una auditoria que miente es peor que no tenerla, porque se le cree.
		assertThatThrownBy(() -> auditTrail.record(entrada(Map.of())))
				.isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);

		verifyNoInteractions(auditEventRepository);
	}

	@Test
	@DisplayName("Dentro de la transaccion del llamador, persiste el evento con sus campos")
	void dentro_de_la_transaccion_persiste() {
		Map<String, String> detalle = new LinkedHashMap<>();
		detalle.put("planCode", "BASICO");

		enTransaccion(() -> auditTrail.record(new AuditEntry(
				100L, 200L, 7L,
				"SUBSCRIPTION_TRANSITIONED", "subscription", 55L,
				"ACTIVA", "SUSPENDIDA",
				detalle, "Falta de pago", "trace-abc", CUANDO)));

		AuditEvent guardado = capturarGuardado();
		assertThat(guardado.getOrganizationId()).isEqualTo(100L);
		assertThat(guardado.getConsultorioId()).isEqualTo(200L);
		assertThat(guardado.getActorAccountId()).isEqualTo(7L);
		assertThat(guardado.getEventType()).isEqualTo("SUBSCRIPTION_TRANSITIONED");
		assertThat(guardado.getEntityType()).isEqualTo("subscription");
		assertThat(guardado.getEntityId()).isEqualTo(55L);
		assertThat(guardado.getPreviousState()).isEqualTo("ACTIVA");
		assertThat(guardado.getNewState()).isEqualTo("SUSPENDIDA");
		assertThat(guardado.getDetails()).contains("BASICO");
		assertThat(guardado.getReason()).isEqualTo("Falta de pago");
		assertThat(guardado.getCorrelationId()).isEqualTo("trace-abc");
		assertThat(guardado.getOccurredAt()).isEqualTo(CUANDO);
	}

	@Test
	@DisplayName("Un evento de plataforma sin tenant se acepta con organizationId nulo")
	void evento_de_plataforma_sin_tenant() {
		enTransaccion(() -> auditTrail.record(new AuditEntry(
				null, null, null,
				"ORGANIZATION_CREATED", "organization", 1L,
				null, null, null, null, null, CUANDO)));

		AuditEvent guardado = capturarGuardado();
		assertThat(guardado.getOrganizationId()).isNull();
		// Sin detalle, la columna queda NULL y no con un "{}" que no significa nada.
		assertThat(guardado.getDetails()).isNull();
	}

	// =================================================================================
	// Sanitizacion
	// =================================================================================

	@Test
	@DisplayName("Nunca se persiste una contrasena ni un token, aunque el emisor los mande")
	void redacta_secretos() {
		Map<String, String> detalle = new LinkedHashMap<>();
		detalle.put("password", "sup3rsecreta");
		detalle.put("refreshToken", "eyJhbGciOiJIUzI1NiJ9.payload.firma");
		detalle.put("Authorization", "Bearer abc123");
		detalle.put("email", "persona@ejemplo.com");

		enTransaccion(() -> auditTrail.record(entrada(detalle)));

		String persistido = capturarGuardado().getDetails();
		// La tabla se consulta para investigar incidentes y termina en backups: un secreto ahi
		// es una credencial persistida.
		assertThat(persistido).doesNotContain("sup3rsecreta");
		assertThat(persistido).doesNotContain("eyJhbGciOiJIUzI1NiJ9");
		assertThat(persistido).doesNotContain("abc123");
		assertThat(persistido).contains(AuditTrailImpl.VALOR_REDACTADO);
		// Se conserva la clave: saber que alguien intento auditar un secreto es informacion util.
		assertThat(persistido).contains("password");
		// Y lo que no es sensible sigue registrandose.
		assertThat(persistido).contains("persona@ejemplo.com");
	}

	@Test
	@DisplayName("Nunca se persiste contenido clinico en el detalle")
	void redacta_contenido_clinico() {
		Map<String, String> detalle = new LinkedHashMap<>();
		detalle.put("diagnostico", "lumbalgia mecanica");
		detalle.put("evolucion_sesion", "mejora del rango articular");
		detalle.put("consultorioNombre", "Sede Centro");

		enTransaccion(() -> auditTrail.record(entrada(detalle)));

		String persistido = capturarGuardado().getDetails();
		// La auditoria registra QUE paso, QUIEN y SOBRE QUE. El contenido del dato clinico vive
		// en la historia clinica, con su propio modelo de permisos.
		assertThat(persistido).doesNotContain("lumbalgia");
		assertThat(persistido).doesNotContain("rango articular");
		assertThat(persistido).contains("Sede Centro");
	}

	@Test
	@DisplayName("Un valor desmedido se trunca en vez de romper la operacion de negocio")
	void trunca_valores_largos() {
		Map<String, String> detalle = new LinkedHashMap<>();
		detalle.put("payload", "x".repeat(5_000));

		enTransaccion(() -> auditTrail.record(entrada(detalle)));

		// La auditoria no puede ser el motivo por el que una operacion valida falla por largo.
		assertThat(capturarGuardado().getDetails().length())
				.isLessThan(AuditTrailImpl.LARGO_MAXIMO_VALOR + 100);
	}

	@Test
	@DisplayName("El motivo se trunca al largo de la columna")
	void trunca_el_motivo() {
		enTransaccion(() -> auditTrail.record(new AuditEntry(
				100L, null, 7L, "ALGO", "algo", 1L, null, null, null,
				"m".repeat(1_000), null, CUANDO)));

		assertThat(capturarGuardado().getReason())
				.hasSize(AuditTrailImpl.LARGO_MAXIMO_REASON);
	}

	// =================================================================================
	// Ayudas
	// =================================================================================

	private AuditEntry entrada(Map<String, String> detalle) {
		return new AuditEntry(
				100L, 200L, 7L, "CONTEXT_SELECTED", "membership", 1L,
				null, null, detalle, null, "trace-1", CUANDO);
	}

	private void enTransaccion(Runnable operacion) {
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> operacion.run());
	}

	private AuditEvent capturarGuardado() {
		ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
		Mockito.verify(auditEventRepository).save(captor.capture());
		return captor.getValue();
	}

	/**
	 * Contexto minimo: el proxy transaccional y nada mas. La base no aporta nada a lo que se
	 * quiere verificar y aportaria latencia y motivos de fallo ajenos.
	 */
	@Configuration
	@EnableTransactionManagement
	static class ContextoDePrueba {

		@Bean
		AuditEventRepository auditEventRepository() {
			return Mockito.mock(AuditEventRepository.class);
		}

		@Bean
		AuditTrail auditTrail(AuditEventRepository auditEventRepository) {
			return new AuditTrailImpl(auditEventRepository);
		}

		@Bean
		PlatformTransactionManager transactionManager() {
			return new GestorDeTransaccionDePrueba();
		}
	}

	/**
	 * Gestor de transacciones sin base: lo unico que necesita saber es si hay una transaccion
	 * en curso, porque de eso depende que {@code MANDATORY} acepte o rechace la llamada. La
	 * logica de propagacion la aporta {@code AbstractPlatformTransactionManager}, que es la
	 * misma clase que usa el gestor real de JPA.
	 */
	static class GestorDeTransaccionDePrueba extends AbstractPlatformTransactionManager {

		private final ThreadLocal<Boolean> enCurso = ThreadLocal.withInitial(() -> Boolean.FALSE);

		@Override
		protected Object doGetTransaction() {
			return new Object();
		}

		@Override
		protected boolean isExistingTransaction(Object transaction) {
			return enCurso.get();
		}

		@Override
		protected void doBegin(Object transaction, TransactionDefinition definition) {
			enCurso.set(Boolean.TRUE);
		}

		@Override
		protected void doCommit(DefaultTransactionStatus status) {
			enCurso.set(Boolean.FALSE);
		}

		@Override
		protected void doRollback(DefaultTransactionStatus status) {
			enCurso.set(Boolean.FALSE);
		}
	}
}
