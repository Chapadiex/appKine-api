package com.akine.notification.domain;

import com.akine.notification.spi.NotificationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Una notificacion pendiente de entrega (RN-M26-001, RF-M26-004/005).
 *
 * <p>La fila se inserta en la MISMA transaccion que el negocio que la origina. Si el negocio
 * comitea, la notificacion existe; si el envio falla despues, el negocio no se revierte,
 * porque para entonces la transaccion ya cerro. Ese desacople no es una politica que alguien
 * tenga que respetar: es la forma de la tabla.
 *
 * <p><b>Aca no hay tokens ni enlaces</b> (T-11, RN-M02-003). {@link #referenciaTokenId} es el
 * ID del token de un solo uso —no su valor—, y el enlace se reconstruye en el momento del
 * envio a traves de {@code SecureLinkResolver}. El nombre del campo dice lo que se espera:
 * una <em>referencia</em>, no un token.
 *
 * <p>El estado solo cambia por los metodos de esta clase, y cada uno valida la transicion
 * contra {@link OutboxStateMachine} antes de mutar. No hay setter de estado.
 */
@Entity
@Table(name = "notification_outbox")
public class NotificationOutboxEntry extends TimestampedNotification {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/**
	 * Tenant al que se atribuye, o {@code null} si el hecho es previo al tenant.
	 *
	 * <p>Excepcion parcial a ADR-0004, justificada: la activacion de una cuenta invitada y la
	 * recuperacion de contrasena ocurren cuando la persona todavia no tiene organizacion. No
	 * hay tenant al que atribuir la fila y ponerle uno inventado seria peor que dejarlo nulo.
	 * {@code NULL} = evento de identidad global, operable solo por admin de plataforma.
	 */
	@Column(name = "organization_id", updatable = false)
	private Long organizationId;

	@Enumerated(EnumType.STRING)
	@Column(name = "tipo", nullable = false, length = 40, updatable = false)
	private NotificationType tipo;

	@Column(name = "destinatario", nullable = false, length = 320, updatable = false)
	private String destinatario;

	/** JSON plano y validado. Ver {@link SanitizedPayload}: claves en lista blanca. */
	@Column(name = "payload_sanitizado", nullable = false, columnDefinition = "json", updatable = false)
	private String payloadSanitizado;

	@Enumerated(EnumType.STRING)
	@Column(name = "estado", nullable = false, length = 20)
	private OutboxStatus estado;

	@Column(name = "intentos", nullable = false)
	private int intentos;

	@Column(name = "max_intentos", nullable = false)
	private int maxIntentos;

	@Column(name = "proxima_ejecucion_en", nullable = false)
	private Instant proximaEjecucionEn;

	/** Lease del worker: si vencio, la fila quedo huerfana y vuelve a la cola. */
	@Column(name = "procesando_desde")
	private Instant procesandoDesde;

	@Column(name = "clave_idempotente", nullable = false, length = 120, updatable = false)
	private String claveIdempotente;

	/**
	 * ID OPACO del token de un solo uso que vive en {@code identity}. JAMAS su valor.
	 *
	 * <p>Si aca hubiera un token —o un enlace que lo contenga—, esta tabla pasaria a ser un
	 * deposito de credenciales: se consulta para diagnosticar entregas fallidas, se reintenta
	 * desde una pantalla administrativa y termina en los backups (RN-M02-003, T-11).
	 */
	@Column(name = "referencia_token_id", length = 64, updatable = false)
	private String referenciaTokenId;

	/** Motivo del ultimo fallo, ya pasado por {@link ErrorSanitizer}. Nunca la excepcion cruda. */
	@Column(name = "error_sanitizado", length = ErrorSanitizer.LARGO_MAXIMO)
	private String errorSanitizado;

	@Column(name = "enviado_en")
	private Instant enviadoEn;

	protected NotificationOutboxEntry() {
		// Requerido por JPA.
	}

	/**
	 * Encola una notificacion nueva, siempre en {@link OutboxStatus#PENDIENTE}.
	 *
	 * @param proximaEjecucionEn cuando puede tomarla el worker; normalmente "ahora"
	 */
	public NotificationOutboxEntry(
			NotificationType tipo,
			String destinatario,
			String claveIdempotente,
			Long organizationId,
			String referenciaTokenId,
			SanitizedPayload payload,
			int maxIntentos,
			Instant proximaEjecucionEn) {
		this.tipo = exigir(tipo, "tipo");
		this.destinatario = exigirTexto(destinatario, "destinatario");
		this.claveIdempotente = exigirTexto(claveIdempotente, "claveIdempotente");
		this.organizationId = organizationId;
		this.referenciaTokenId = referenciaTokenId;
		this.payloadSanitizado = exigir(payload, "payload").toJson();
		this.maxIntentos = maxIntentos;
		this.proximaEjecucionEn = exigir(proximaEjecucionEn, "proximaEjecucionEn");
		this.estado = OutboxStatus.ESTADO_INICIAL;
		this.intentos = 0;

		if (tipo.requiereEnlaceSeguro() && (referenciaTokenId == null || referenciaTokenId.isBlank())) {
			throw new IllegalArgumentException("El tipo " + tipo + " necesita una referencia al token "
					+ "(su id, jamas su valor) para poder reconstruir el enlace al enviar");
		}
	}

	/**
	 * La toma el worker. Solo desde PENDIENTE o REINTENTABLE.
	 *
	 * @param ahora instante del claim; queda como lease en {@code procesandoDesde}
	 */
	public void marcarEnProceso(Instant ahora) {
		OutboxStateMachine.assertTransitionAllowed(estado, OutboxStatus.PROCESANDO);
		this.estado = OutboxStatus.PROCESANDO;
		this.procesandoDesde = ahora;
	}

	/**
	 * Entrega exitosa.
	 *
	 * <p>No borra {@code errorSanitizado}: que una notificacion haya entrado despues de tres
	 * fallos es informacion util para diagnosticar, y el motivo ya viene sanitizado.
	 */
	public void marcarEnviada(Instant ahora) {
		OutboxStateMachine.assertTransitionAllowed(estado, OutboxStatus.ENVIADA);
		this.estado = OutboxStatus.ENVIADA;
		this.enviadoEn = ahora;
		this.procesandoDesde = null;
		this.intentos++;
	}

	/**
	 * Fallo transitorio: suma un intento y vuelve a la cola, o se agota.
	 *
	 * @param motivoSanitizado motivo ya pasado por {@link ErrorSanitizer}
	 * @param espera           espera calculada por la politica; vacia = no quedan intentos
	 * @return el estado resultante, REINTENTABLE o AGOTADA
	 */
	public OutboxStatus registrarFalloTransitorio(
			Instant ahora, String motivoSanitizado, Optional<Duration> espera) {
		OutboxStatus destino = espera.isEmpty() ? OutboxStatus.AGOTADA : OutboxStatus.REINTENTABLE;
		// La transicion se valida ANTES de tocar un solo campo: si se validara despues, una
		// llamada sobre una fila que ya no esta PROCESANDO dejaria el intento consumido y el
		// error pisado aunque la excepcion impidiera el cambio de estado.
		OutboxStateMachine.assertTransitionAllowed(estado, destino);
		this.intentos++;
		this.errorSanitizado = ErrorSanitizer.sanitize(motivoSanitizado);
		this.procesandoDesde = null;
		this.estado = destino;
		if (destino == OutboxStatus.REINTENTABLE) {
			this.proximaEjecucionEn = ahora.plus(espera.get());
		}
		return this.estado;
	}

	/**
	 * Fallo permanente: destinatario invalido, template inexistente, token ya consumido.
	 *
	 * <p>No consume la cuota de reintentos porque no hay nada que reintentar: el resultado
	 * seria identico. Sale de aca solo por reintento administrativo.
	 */
	public void registrarFalloPermanente(String motivoSanitizado) {
		OutboxStateMachine.assertTransitionAllowed(estado, OutboxStatus.FALLIDA);
		this.intentos++;
		this.estado = OutboxStatus.FALLIDA;
		this.errorSanitizado = ErrorSanitizer.sanitize(motivoSanitizado);
		this.procesandoDesde = null;
	}

	/**
	 * Recupera una fila cuyo worker murio dejandola PROCESANDO.
	 *
	 * <p>Vuelve a REINTENTABLE y NO a PENDIENTE: asi conserva el contador de intentos y no
	 * puede quedar girando para siempre sin agotarse nunca. No consume un intento —no hubo un
	 * envio fallido, hubo un proceso caido—, pero si respeta el backoff para que una instancia
	 * que se reinicia en loop no queme la cola.
	 */
	public void recuperarLeaseVencido(Instant ahora, Duration espera) {
		OutboxStateMachine.assertTransitionAllowed(estado, OutboxStatus.REINTENTABLE);
		this.estado = OutboxStatus.REINTENTABLE;
		this.procesandoDesde = null;
		this.proximaEjecucionEn = ahora.plus(espera);
		this.errorSanitizado = "Lease vencido: la notificacion quedo tomada por un worker que no la termino";
	}

	/**
	 * Reintento administrativo de una fila FALLIDA o AGOTADA: reinicia el contador y la
	 * devuelve a la cola.
	 */
	public void reintentarManualmente(Instant ahora) {
		OutboxStateMachine.assertTransitionAllowed(estado, OutboxStatus.REINTENTABLE);
		this.estado = OutboxStatus.REINTENTABLE;
		this.intentos = 0;
		this.procesandoDesde = null;
		this.proximaEjecucionEn = ahora;
	}

	/** Indica si el lease del worker vencio y la fila puede recuperarse. */
	public boolean leaseVencido(Instant ahora, Duration duracionLease) {
		return estado == OutboxStatus.PROCESANDO
				&& procesandoDesde != null
				&& procesandoDesde.plus(duracionLease).isBefore(ahora);
	}

	public SanitizedPayload payload() {
		return SanitizedPayload.fromJson(payloadSanitizado);
	}

	public Long getId() {
		return id;
	}

	public Long getOrganizationId() {
		return organizationId;
	}

	public NotificationType getTipo() {
		return tipo;
	}

	public String getDestinatario() {
		return destinatario;
	}

	public String getPayloadSanitizado() {
		return payloadSanitizado;
	}

	public OutboxStatus getEstado() {
		return estado;
	}

	public int getIntentos() {
		return intentos;
	}

	public int getMaxIntentos() {
		return maxIntentos;
	}

	public Instant getProximaEjecucionEn() {
		return proximaEjecucionEn;
	}

	public Instant getProcesandoDesde() {
		return procesandoDesde;
	}

	public String getClaveIdempotente() {
		return claveIdempotente;
	}

	public String getReferenciaTokenId() {
		return referenciaTokenId;
	}

	public String getErrorSanitizado() {
		return errorSanitizado;
	}

	public Instant getEnviadoEn() {
		return enviadoEn;
	}

	private static <T> T exigir(T valor, String nombre) {
		if (valor == null) {
			throw new IllegalArgumentException("La notificacion necesita " + nombre);
		}
		return valor;
	}

	private static String exigirTexto(String valor, String nombre) {
		if (valor == null || valor.isBlank()) {
			throw new IllegalArgumentException("La notificacion necesita " + nombre);
		}
		return valor;
	}
}
