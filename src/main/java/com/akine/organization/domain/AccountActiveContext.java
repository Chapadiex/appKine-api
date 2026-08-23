package com.akine.organization.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Ultima seleccion de contexto de una cuenta (RF-M01-005, ADR-0009).
 *
 * <p><b>Es un puntero, no una autoridad.</b> El contexto efectivo de un request es el del
 * token de ESE request, revalidado contra la base; esta fila solo sirve para preseleccionar
 * al arrancar. La distincion resuelve el caso del usuario que cambia de contexto en otra
 * pestana mientras confirma una operacion: la confirmacion sigue operando sobre el contexto
 * de su token, no sobre un estado compartido que se movio abajo suyo.
 *
 * <p>Quien lea este puntero DEBE revalidarlo: puede estar apuntando a una membership revocada
 * o a una organizacion cancelada. Si ya no es valido, se trata como "sin seleccion previa".
 *
 * <p>Sin baja logica y sin {@code created_at}: es reemplazable por definicion. El historial de
 * cambios de contexto no vive aca sino en {@code audit_event}, que si es append-only.
 *
 * <p>{@code UNIQUE(account_id)} es una excepcion documentada al unique con alcance tenant: el
 * sujeto es la cuenta, que es cross-org por naturaleza. La fila igual lleva
 * {@code organization_id} para poder auditarla.
 */
@Entity
@Table(name = "account_active_context")
public class AccountActiveContext {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "account_id", nullable = false, updatable = false)
	private Long accountId;

	@Column(name = "organization_id", nullable = false)
	private Long organizationId;

	@Column(name = "consultorio_id", nullable = false)
	private Long consultorioId;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected AccountActiveContext() {
		// Requerido por JPA.
	}

	public AccountActiveContext(Long accountId, Long organizationId, Long consultorioId) {
		this.accountId = accountId;
		this.organizationId = organizationId;
		this.consultorioId = consultorioId;
	}

	/**
	 * Reapunta el contexto activo.
	 *
	 * @return {@code true} si el puntero efectivamente cambio. El llamador lo usa para no
	 *         emitir un evento de auditoria cuando el usuario reselecciona lo mismo: un PUT
	 *         repetido no puede generar ruido en el historial.
	 */
	public boolean pointTo(Long organizationId, Long consultorioId) {
		boolean cambio = !organizationId.equals(this.organizationId)
				|| !consultorioId.equals(this.consultorioId);
		this.organizationId = organizationId;
		this.consultorioId = consultorioId;
		return cambio;
	}

	@PrePersist
	@PreUpdate
	void marcarInstante() {
		this.updatedAt = Instant.now();
	}

	public Long getId() {
		return id;
	}

	public Long getAccountId() {
		return accountId;
	}

	public Long getOrganizationId() {
		return organizationId;
	}

	public Long getConsultorioId() {
		return consultorioId;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
