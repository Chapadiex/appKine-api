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
 * Token de un solo uso para activar una cuenta o restablecer una contrasena (RF-M02-003).
 *
 * <p><b>La fila no contiene el token.</b> Contiene su SHA-256 (ver {@link TokenDigest}). El
 * valor plano existe una sola vez, en memoria, el tiempo que tarda en armarse el enlace que
 * se envia; despues no queda en ningun lado desde donde se pueda recuperar.
 *
 * <p>Un token deja de servir por tres motivos distintos, y los tres se conservan en la fila
 * en vez de borrarla: <b>usado</b> (alguien lo consumio), <b>invalidado</b> (se emitio uno
 * nuevo del mismo tipo, asi que hay a lo sumo uno vigente por cuenta) y <b>expirado</b>. Los
 * tres se le responden al cliente de la misma forma —{@code invalid-token}, sin distinguir—:
 * decirle "expirado" en vez de "no existe" le confirmaria a un atacante que acerto un token.
 */
@Entity
@Table(name = "token_verificacion")
public class TokenVerificacion {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "cuenta_id", nullable = false, updatable = false)
	private Long cuentaId;

	@Enumerated(EnumType.STRING)
	@Column(name = "tipo", nullable = false, length = 20, updatable = false)
	private TipoTokenVerificacion tipo;

	@Column(name = "token_hash", nullable = false, length = 64, updatable = false)
	private String tokenHash;

	@Column(name = "expira_en", nullable = false, updatable = false)
	private Instant expiraEn;

	@Column(name = "usado_en")
	private Instant usadoEn;

	@Column(name = "invalidado_en")
	private Instant invalidadoEn;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected TokenVerificacion() {
		// Requerido por JPA.
	}

	/**
	 * Emite un token.
	 *
	 * <p>La vigencia la fija el propio {@link TipoTokenVerificacion}: si el llamador pudiera
	 * elegirla, un descuido convertiria un token de reset en uno de siete dias.
	 *
	 * @param tokenHash SHA-256 del valor plano. Este constructor no acepta el token en claro
	 *                  a proposito
	 */
	public TokenVerificacion(
			Long cuentaId, TipoTokenVerificacion tipo, String tokenHash, Instant emitidoEn) {
		this.cuentaId = cuentaId;
		this.tipo = tipo;
		this.tokenHash = tokenHash;
		this.expiraEn = emitidoEn.plus(tipo.vigencia());
		this.createdAt = emitidoEn;
	}

	@PrePersist
	void alInsertar() {
		if (createdAt == null) {
			createdAt = Instant.now();
		}
	}

	/**
	 * Indica si el token sirve en ese instante: ni usado, ni invalidado, ni vencido.
	 *
	 * <p>Los tres motivos se colapsan en un booleano a proposito: quien pregunta no debe
	 * poder construir una respuesta que distinga entre ellos.
	 */
	public boolean esUtilizableEn(Instant ahora) {
		return usadoEn == null && invalidadoEn == null && ahora.isBefore(expiraEn);
	}

	/**
	 * Marca el token como consumido (RF-M02-003 "invalidar el token utilizado").
	 *
	 * @throws IllegalStateException si ya estaba consumido. No es defensa contra un ataque
	 *         —eso lo resuelve la consulta que solo trae tokens utilizables— sino contra un
	 *         error de programacion que dejaria un token de un solo uso valiendo dos veces
	 */
	public void consumir(Instant ahora) {
		if (usadoEn != null) {
			throw new IllegalStateException("El token ya fue consumido");
		}
		this.usadoEn = ahora;
	}

	/**
	 * Invalida el token porque se emitio uno nuevo del mismo tipo.
	 *
	 * <p>Un token ya consumido no se re-marca: perderiamos la fecha real de consumo, que es
	 * justamente lo que se mira para investigar un incidente.
	 */
	public void invalidar(Instant ahora) {
		if (usadoEn == null && invalidadoEn == null) {
			this.invalidadoEn = ahora;
		}
	}

	public Long getId() {
		return id;
	}

	public Long getCuentaId() {
		return cuentaId;
	}

	public TipoTokenVerificacion getTipo() {
		return tipo;
	}

	public String getTokenHash() {
		return tokenHash;
	}

	public Instant getExpiraEn() {
		return expiraEn;
	}

	public Instant getUsadoEn() {
		return usadoEn;
	}

	public Instant getInvalidadoEn() {
		return invalidadoEn;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
