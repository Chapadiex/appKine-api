package com.akine.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Una sesion de refresh viva, del lado del servidor.
 *
 * <p><b>Por que es una fila y no un JWT.</b> Un refresh autocontenido no se puede revocar:
 * bloquear una cuenta dejaria vivas todas sus sesiones hasta que venzan solas. Como bloquear
 * tiene que cortar el acceso de verdad (RF-M02-005), el refresh es un puntero opaco a esta
 * fila y la revocacion es un UPDATE.
 *
 * <p><b>Familia.</b> Todas las rotaciones de una misma sesion comparten {@code familiaId}. Si
 * aparece un refresh ya usado, hay una copia dando vueltas y la respuesta correcta no es
 * rechazar ese token sino <b>revocar la familia entera</b>: no se sabe cual de los dos
 * portadores es el legitimo, y dejar viva la cadena significa dejar dentro al atacante.
 *
 * <p><b>{@code expiraEn} es absoluto y se hereda al rotar.</b> Si cada rotacion lo corriera
 * hacia adelante, una sesion robada que se refresca sola no venceria nunca.
 */
@Entity
@Table(name = "refresh_token")
public class RefreshToken {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "cuenta_id", nullable = false, updatable = false)
	private Long cuentaId;

	@Column(name = "familia_id", nullable = false, length = 36, updatable = false)
	private String familiaId;

	@Column(name = "token_hash", nullable = false, length = 64, updatable = false)
	private String tokenHash;

	@Column(name = "context_organization_id")
	private Long contextOrganizationId;

	@Column(name = "context_consultorio_id")
	private Long contextConsultorioId;

	@Column(name = "emitido_en", nullable = false, updatable = false)
	private Instant emitidoEn;

	@Column(name = "expira_en", nullable = false, updatable = false)
	private Instant expiraEn;

	@Column(name = "usado_en")
	private Instant usadoEn;

	@Column(name = "revocado_en")
	private Instant revocadoEn;

	@Enumerated(EnumType.STRING)
	@Column(name = "motivo_revocacion", length = 40)
	private MotivoRevocacion motivoRevocacion;

	@Column(name = "reemplazado_por_id")
	private Long reemplazadoPorId;

	@Column(name = "ip", length = 45)
	private String ip;

	@Column(name = "user_agent", length = 200)
	private String userAgent;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected RefreshToken() {
		// Requerido por JPA.
	}

	/**
	 * Emite un eslabon de la cadena.
	 *
	 * @param tokenHash SHA-256 del token opaco. El valor plano no entra aca
	 * @param expiraEn  vencimiento ABSOLUTO de la sesion. Al rotar se pasa el mismo valor que
	 *                  traia el token anterior: la rotacion no extiende la sesion
	 */
	public RefreshToken(
			Long cuentaId,
			String familiaId,
			String tokenHash,
			Instant emitidoEn,
			Instant expiraEn,
			Long contextOrganizationId,
			Long contextConsultorioId,
			String ip,
			String userAgent) {
		this.cuentaId = cuentaId;
		this.familiaId = familiaId;
		this.tokenHash = tokenHash;
		this.emitidoEn = emitidoEn;
		this.expiraEn = expiraEn;
		this.contextOrganizationId = contextOrganizationId;
		this.contextConsultorioId = contextConsultorioId;
		this.ip = truncar(ip, 45);
		this.userAgent = truncar(userAgent, 200);
		this.createdAt = emitidoEn;
	}

	@PrePersist
	void alInsertar() {
		if (createdAt == null) {
			createdAt = Instant.now();
		}
	}

	/** Indica si el token se puede canjear ahora: sin usar, sin revocar y sin vencer. */
	public boolean esCanjeableEn(Instant ahora) {
		return usadoEn == null && revocadoEn == null && ahora.isBefore(expiraEn);
	}

	/** Marca el eslabon como rotado y lo enlaza con su sucesor, para poder reconstruir la cadena. */
	public void marcarRotado(Long sucesorId, Instant ahora) {
		this.usadoEn = ahora;
		this.reemplazadoPorId = sucesorId;
	}

	/**
	 * Revoca el token.
	 *
	 * <p>Idempotente: una revocacion posterior no pisa la primera. Importa porque bloquear
	 * una cuenta revoca en masa y no debe reescribir el motivo de algo que ya se habia
	 * revocado por reuso, que es la informacion valiosa de las dos.
	 */
	public void revocar(MotivoRevocacion motivo, Instant ahora) {
		if (revocadoEn == null) {
			this.revocadoEn = ahora;
			this.motivoRevocacion = motivo;
		}
	}

	/**
	 * Actualiza el contexto que la sesion recuerda, para que el proximo refresh re-emita el
	 * access token acotado al mismo lugar donde la persona estaba trabajando.
	 */
	public void recordarContexto(Long organizationId, Long consultorioId) {
		this.contextOrganizationId = organizationId;
		this.contextConsultorioId = consultorioId;
	}

	private static String truncar(String valor, int max) {
		if (valor == null) {
			return null;
		}
		return valor.length() <= max ? valor : valor.substring(0, max);
	}

	public Long getId() {
		return id;
	}

	public Long getCuentaId() {
		return cuentaId;
	}

	public String getFamiliaId() {
		return familiaId;
	}

	public String getTokenHash() {
		return tokenHash;
	}

	public Long getContextOrganizationId() {
		return contextOrganizationId;
	}

	public Long getContextConsultorioId() {
		return contextConsultorioId;
	}

	public Instant getEmitidoEn() {
		return emitidoEn;
	}

	public Instant getExpiraEn() {
		return expiraEn;
	}

	public Instant getUsadoEn() {
		return usadoEn;
	}

	public Instant getRevocadoEn() {
		return revocadoEn;
	}

	public MotivoRevocacion getMotivoRevocacion() {
		return motivoRevocacion;
	}

	public Long getReemplazadoPorId() {
		return reemplazadoPorId;
	}

	public String getIp() {
		return ip;
	}

	public String getUserAgent() {
		return userAgent;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
