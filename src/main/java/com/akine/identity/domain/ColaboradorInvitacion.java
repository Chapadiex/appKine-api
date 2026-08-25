package com.akine.identity.domain;

import com.akine.identity.domain.exception.InvitacionNoPendienteException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * Invitacion a colaborar en una organizacion (RF-M05-001, RF-M05-002).
 *
 * <h2>Por que vive en {@code identity} y no en {@code organization}</h2>
 *
 * <p>Igual que {@link OnboardingRegistro}: el flujo arranca con un <b>email</b> —dato de
 * {@code identity}— y en el caso mas comun <b>crea la cuenta</b> al aceptarse. La membership la
 * crea {@code organization} por su SPI, que es la unica flecha que ArchUnit admite entre los
 * dos modulos.
 *
 * <h2>La invitacion es al EMAIL, no a la cuenta</h2>
 *
 * <p>No hay {@code cuenta_id} al emitir, y no es un dato faltante: al invitar puede no existir
 * ninguna cuenta con esa direccion. Esa es justamente la diferencia con el alta directa
 * ({@code DirectMembershipService}), que exige que la cuenta ya exista y responde 404 si no.
 * Las dos conviven: el alta directa es un click para quien ya esta en AKINE, la invitacion es
 * el camino para quien todavia no.
 *
 * <h2>El token es la autoridad</h2>
 *
 * <p>Quien presenta el token demostro que llega al buzon de {@code emailNormalizado}, y eso es
 * lo que autoriza a aceptar o rechazar <b>sin sesion</b>. Por eso solo se guarda su SHA-256:
 * una base filtrada no entrega ningun enlace usable. Y por eso el enlace se consume al
 * resolver — no al leerlo, que es la leccion que dejo el enlace de un solo uso de 01.02.
 *
 * <h2>Expirar no es un estado</h2>
 *
 * <p>Ver {@link EstadoInvitacion}. {@link #estaVencida(Instant)} lo deriva del reloj; la
 * columna nunca dice EXPIRADA.
 */
@Entity
@Table(name = "colaborador_invitacion")
public class ColaboradorInvitacion {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** Tenant que invita. Siempre presente: no existen invitaciones de plataforma. */
	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	/** Sede del vinculo propuesto, o {@code null} para alcance ORGANIZACION. */
	@Column(name = "consultorio_id", updatable = false)
	private Long consultorioId;

	@Column(name = "email_normalizado", nullable = false, updatable = false, length = 320)
	private String emailNormalizado;

	/**
	 * Rol con el que quedaria vinculada la persona, como texto.
	 *
	 * <p>Texto y no el enum {@code RoleCode}: ese vive en {@code organization.domain}, que es
	 * privado de su modulo y ArchUnit rechaza importarlo. La validacion la hace
	 * {@code organization} al crear la membership, que es donde el enum es autoridad.
	 */
	@Column(name = "role_code", nullable = false, updatable = false, length = 32)
	private String roleCode;

	/** SHA-256 en hex del token del enlace. El token en claro no se persiste nunca. */
	@Column(name = "token_hash", nullable = false, length = 64)
	private String tokenHash;

	@Column(name = "expira_en", nullable = false)
	private Instant expiraEn;

	@Enumerated(EnumType.STRING)
	@Column(name = "estado", nullable = false, length = 20)
	private EstadoInvitacion estado = EstadoInvitacion.PENDIENTE;

	@Column(name = "resuelta_en")
	private Instant resueltaEn;

	@Column(name = "resolucion_nota", length = 280)
	private String resolucionNota;

	@Column(name = "invitada_por_account_id", nullable = false, updatable = false)
	private Long invitadaPorAccountId;

	@Column(name = "aceptada_por_account_id")
	private Long aceptadaPorAccountId;

	@Column(name = "membership_id")
	private Long membershipId;

	@Version
	@Column(name = "version", nullable = false)
	private Long version;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected ColaboradorInvitacion() {
		// JPA.
	}

	public ColaboradorInvitacion(
			long organizationId,
			Long consultorioId,
			String emailNormalizado,
			String roleCode,
			String tokenHash,
			Instant expiraEn,
			long invitadaPorAccountId) {

		this.organizationId = organizationId;
		this.consultorioId = consultorioId;
		this.emailNormalizado = emailNormalizado;
		this.roleCode = roleCode;
		this.tokenHash = tokenHash;
		this.expiraEn = expiraEn;
		this.invitadaPorAccountId = invitadaPorAccountId;
		this.estado = EstadoInvitacion.PENDIENTE;
	}

	@PrePersist
	void alInsertar() {
		Instant ahora = Instant.now();
		if (createdAt == null) {
			createdAt = ahora;
		}
		updatedAt = ahora;
	}

	@PreUpdate
	void alActualizar() {
		updatedAt = Instant.now();
	}

	/**
	 * {@code true} si el enlace ya no sirve por el reloj.
	 *
	 * <p>Independiente de {@link #estado}: una invitacion aceptada hace un mes tambien tiene la
	 * fecha pasada, y eso no la vuelve vencida en ningun sentido util. Quien pregunte por
	 * "vencida" en una pantalla tiene que preguntar por las dos cosas — ver {@link #estaViva}.
	 */
	public boolean estaVencida(Instant ahora) {
		return expiraEn.isBefore(ahora);
	}

	/** {@code true} si todavia se puede aceptar o rechazar: pendiente y sin vencer. */
	public boolean estaViva(Instant ahora) {
		return estado == EstadoInvitacion.PENDIENTE && !estaVencida(ahora);
	}

	/**
	 * Emite un token nuevo sobre la misma invitacion (reenvio).
	 *
	 * <p><b>El token anterior deja de servir en el mismo acto</b>, porque la columna es una
	 * sola: dos enlaces validos para la misma invitacion dejarian al invitado eligiendo cual
	 * usar y al administrador sin saber cual mando. Reenviar no crea una invitacion nueva:
	 * conserva el id, la autoria y la fecha original, que es lo que hace que el listado siga
	 * diciendo desde cuando esta esperando respuesta.
	 *
	 * @throws InvitacionNoPendienteException si ya fue resuelta
	 */
	public void reenviar(String tokenHashNuevo, Instant expiraEnNueva) {
		exigirPendiente();
		this.tokenHash = tokenHashNuevo;
		this.expiraEn = expiraEnNueva;
	}

	/**
	 * Marca la invitacion como aceptada y la ata a la membership creada.
	 *
	 * @throws InvitacionNoPendienteException si ya fue resuelta
	 */
	public void aceptar(long aceptadaPorAccountId, long membershipId, Instant ahora) {
		exigirPendiente();
		this.estado = EstadoInvitacion.ACEPTADA;
		this.aceptadaPorAccountId = aceptadaPorAccountId;
		this.membershipId = membershipId;
		this.resueltaEn = ahora;
	}

	/**
	 * El invitado dijo que no.
	 *
	 * <p>El motivo es opcional a proposito: a nadie se le exige explicar por que no quiere
	 * entrar a trabajar a un lado. Cancelar, que es la decision del otro lado del mostrador,
	 * si lo exige.
	 *
	 * @throws InvitacionNoPendienteException si ya fue resuelta
	 */
	public void rechazar(String nota, Instant ahora) {
		exigirPendiente();
		this.estado = EstadoInvitacion.RECHAZADA;
		this.resolucionNota = nota;
		this.resueltaEn = ahora;
	}

	/**
	 * El administrador la retira.
	 *
	 * @throws InvitacionNoPendienteException si ya fue resuelta
	 * @throws IllegalArgumentException       si el motivo viene vacio
	 */
	public void cancelar(String motivo, Instant ahora) {
		exigirPendiente();
		if (motivo == null || motivo.isBlank()) {
			throw new IllegalArgumentException("Cancelar una invitacion exige un motivo");
		}
		this.estado = EstadoInvitacion.CANCELADA;
		this.resolucionNota = motivo.strip();
		this.resueltaEn = ahora;
	}

	private void exigirPendiente() {
		if (estado.esTerminal()) {
			throw new InvitacionNoPendienteException(id, estado);
		}
	}

	public Long getId() {
		return id;
	}

	public Long getOrganizationId() {
		return organizationId;
	}

	public Long getConsultorioId() {
		return consultorioId;
	}

	public String getEmailNormalizado() {
		return emailNormalizado;
	}

	public String getRoleCode() {
		return roleCode;
	}

	public String getTokenHash() {
		return tokenHash;
	}

	public Instant getExpiraEn() {
		return expiraEn;
	}

	public EstadoInvitacion getEstado() {
		return estado;
	}

	public Instant getResueltaEn() {
		return resueltaEn;
	}

	public String getResolucionNota() {
		return resolucionNota;
	}

	public Long getInvitadaPorAccountId() {
		return invitadaPorAccountId;
	}

	public Long getAceptadaPorAccountId() {
		return aceptadaPorAccountId;
	}

	public Long getMembershipId() {
		return membershipId;
	}

	public Long getVersion() {
		return version;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
