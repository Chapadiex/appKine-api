package com.akine.notification.application;

import com.akine.identity.domain.TipoTokenVerificacion;
import com.akine.identity.infrastructure.IdentityProperties;
import com.akine.identity.infrastructure.IdentitySecureLinkResolver;
import com.akine.identity.infrastructure.SecureLinkVault;
import com.akine.identity.infrastructure.TokenVerificacionRepository;
import com.akine.notification.NotificationFixtures;
import com.akine.notification.domain.NotificationOutboxEntry;
import com.akine.notification.domain.OutboxStatus;
import com.akine.notification.domain.OutboxWorkerSettings;
import com.akine.notification.domain.exception.EmailDeliveryException;
import com.akine.notification.domain.port.EmailSender;
import com.akine.notification.domain.port.JitterSource;
import com.akine.notification.domain.port.NotificationClock;
import com.akine.notification.domain.port.NotificationOutboxRepositoryPort;
import com.akine.notification.spi.NotificationType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.akine.identity.IdentityFixtures.token;
import static com.akine.notification.NotificationFixtures.DESTINATARIO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * El reintento de una notificacion CON enlace seguro, de punta a punta y contra la fila.
 *
 * <h2>El bug que fija</h2>
 *
 * <p>El worker resuelve el enlace ANTES de llamar al adaptador de correo. Mientras el vault lo
 * borraba al leerlo, el primer fallo transitorio —el relay caido, exactamente el caso para el
 * que existe el backoff— dejaba al segundo intento sin enlace: {@code resolveLink} devolvia
 * vacio y la fila terminaba FALLIDA con el motivo "el token referenciado ya no es valido",
 * mintiendo, porque el token estaba vivo. Toda notificacion con enlace —activacion,
 * recuperacion, invitacion— tenia un presupuesto real de UN intento.
 *
 * <p>Con {@code LogEmailSender} el bug era invisible: ese adaptador nunca falla. Aparecio con
 * el SMTP real. Por eso este test <b>hace fallar el canal en el primer intento</b> en vez de
 * confiar en que el metodo no explote.
 *
 * <h2>Sobre que se afirma</h2>
 *
 * <p>Sobre el estado de la fila del outbox tras cada tick —REINTENTABLE con un intento, despues
 * ENVIADA— y sobre el mail que efectivamente salio. El retorno de {@code runOnce()} solo dice
 * cuantas se intentaron: contaria 1 tambien en el escenario roto.
 *
 * <p>Las piezas son reales, no mocks: {@link OutboxDispatchService} sobre un repositorio en
 * memoria, {@link IdentitySecureLinkResolver} sobre un {@link SecureLinkVault} de verdad. Lo
 * unico simulado es el canal de correo —que es lo que tiene que fallar— y el reloj.
 */
@ExtendWith(MockitoExtension.class)
class ReintentoConEnlaceSeguroTest {

	private static final long ENTRY_ID = 900L;
	private static final long TOKEN_ID = 500L;
	private static final String TOKEN_REF = String.valueOf(TOKEN_ID);
	private static final String ENLACE = "https://app.akine.test/activar?token=SINTETICO";

	@Mock
	private TokenVerificacionRepository tokenRepository;

	private final RepositorioEnMemoria repositorio = new RepositorioEnMemoria();
	private final SecureLinkVault vault = vaultDePrueba();
	private final CanalConPrimerFallo canal = new CanalConPrimerFallo();

	private Instant ahora = NotificationFixtures.AHORA;

	private static SecureLinkVault vaultDePrueba() {
		IdentityProperties properties = new IdentityProperties();
		properties.getLinks().setTtl(Duration.ofMinutes(30));
		return new SecureLinkVault(properties);
	}

	private OutboxDispatcher worker() {
		NotificationClock reloj = () -> ahora;
		JitterSource jitter = () -> 0.5;
		OutboxDispatchService servicio = new OutboxDispatchService(
				repositorio, reloj, OutboxWorkerSettings.porDefecto(), jitter);
		EmailSender emailSender = canal;
		return new OutboxDispatcher(
				servicio, emailSender, new IdentitySecureLinkResolver(tokenRepository, vault));
	}

	@Test
	@DisplayName("un relay caido no quema el enlace: el segundo intento lo encuentra y el mail sale")
	void el_reintento_encuentra_el_enlace_todavia_disponible() {
		NotificationOutboxEntry fila = NotificationFixtures.conId(
				new NotificationOutboxEntry(
						NotificationType.ACTIVACION_CUENTA,
						DESTINATARIO,
						"activacion-cuenta:" + TOKEN_REF,
						NotificationFixtures.ORG_ID,
						TOKEN_REF,
						NotificationFixtures.payloadCompleto(),
						5,
						ahora),
				ENTRY_ID);
		repositorio.save(fila);
		// El vault vive con el reloj real: el del outbox es un fake para saltar el backoff.
		vault.guardar(TOKEN_REF, ENLACE, Instant.now());
		given(tokenRepository.findById(TOKEN_ID))
				.willReturn(Optional.of(token(TipoTokenVerificacion.ACTIVACION, "plano")));

		// --- Intento 1: el enlace se resuelve y el relay esta caido. ---
		worker().runOnce();

		assertThat(fila.getEstado())
				.as("un relay caido es transitorio: la fila vuelve a la cola, no muere")
				.isEqualTo(OutboxStatus.REINTENTABLE);
		assertThat(fila.getIntentos()).isEqualTo(1);
		assertThat(canal.enviados()).isEmpty();

		// --- Intento 2, pasado el backoff: el enlace TIENE que seguir ahi. ---
		ahora = ahora.plus(Duration.ofHours(1));
		worker().runOnce();

		assertThat(fila.getEstado())
				.as("si leer consumiera el enlace, esta fila estaria FALLIDA con motivo '%s'",
						fila.getErrorSanitizado())
				.isEqualTo(OutboxStatus.ENVIADA);
		assertThat(canal.enviados())
				.singleElement()
				.satisfies(cuerpo -> assertThat(cuerpo).contains(ENLACE));

		// Y recien ahora el enlace se solto: no antes del envio, pero tampoco nunca.
		assertThat(vault.leer(TOKEN_REF, Instant.now()))
				.as("tras el exito el enlace deja de vivir en memoria")
				.isEmpty();
	}

	@Test
	@DisplayName("un fallo permanente tambien suelta el enlace: no queda vivo hasta el reinicio")
	void un_fallo_permanente_suelta_el_enlace() {
		NotificationOutboxEntry fila = NotificationFixtures.conId(
				new NotificationOutboxEntry(
						NotificationType.ACTIVACION_CUENTA,
						DESTINATARIO,
						"activacion-cuenta:permanente",
						NotificationFixtures.ORG_ID,
						TOKEN_REF,
						NotificationFixtures.payloadCompleto(),
						5,
						ahora),
				ENTRY_ID);
		repositorio.save(fila);
		// El vault vive con el reloj real: el del outbox es un fake para saltar el backoff.
		vault.guardar(TOKEN_REF, ENLACE, Instant.now());
		given(tokenRepository.findById(TOKEN_ID))
				.willReturn(Optional.of(token(TipoTokenVerificacion.ACTIVACION, "plano")));
		canal.rechazarSiempreComoPermanente();

		worker().runOnce();

		assertThat(fila.getEstado()).isEqualTo(OutboxStatus.FALLIDA);
		assertThat(vault.leer(TOKEN_REF, Instant.now()))
				.as("una fila que murio no puede dejar su credencial en el heap")
				.isEmpty();
	}

	/** Canal que falla transitoriamente la primera vez y despues entrega. */
	private static final class CanalConPrimerFallo implements EmailSender {

		private final List<String> enviados = new ArrayList<>();
		private boolean permanente;
		private int llamadas;

		void rechazarSiempreComoPermanente() {
			this.permanente = true;
		}

		List<String> enviados() {
			return enviados;
		}

		@Override
		public String nombre() {
			return "canal-de-prueba";
		}

		@Override
		public void send(com.akine.notification.domain.EmailMessage mensaje) {
			llamadas++;
			if (permanente) {
				throw EmailDeliveryException.permanente("destinatario rechazado por el relay");
			}
			if (llamadas == 1) {
				throw EmailDeliveryException.transitorio(
						"conexion rechazada por el relay", new java.io.IOException("connect"));
			}
			enviados.add(mensaje.cuerpo());
		}
	}

	/**
	 * Repositorio en memoria con la semantica que el worker necesita: reclamar solo lo vencido y
	 * bloquear nada, porque aca no hay concurrencia.
	 */
	private final class RepositorioEnMemoria implements NotificationOutboxRepositoryPort {

		private final List<NotificationOutboxEntry> filas = new ArrayList<>();

		@Override
		public NotificationOutboxEntry save(NotificationOutboxEntry entry) {
			if (!filas.contains(entry)) {
				filas.add(entry);
			}
			return entry;
		}

		@Override
		public Optional<NotificationOutboxEntry> findById(Long id) {
			return filas.stream().filter(f -> id.equals(f.getId())).findFirst();
		}

		@Override
		public Optional<NotificationOutboxEntry> findByClaveIdempotente(String clave) {
			return filas.stream().filter(f -> clave.equals(f.getClaveIdempotente())).findFirst();
		}

		@Override
		public List<NotificationOutboxEntry> reclamarLote(Instant momento, int limite) {
			return filas.stream()
					.filter(f -> f.getEstado().esReclamable())
					.filter(f -> !f.getProximaEjecucionEn().isAfter(momento))
					.limit(limite)
					.toList();
		}

		@Override
		public List<NotificationOutboxEntry> reclamarLeasesVencidos(Instant limite, int maximo) {
			return List.of();
		}
	}
}
