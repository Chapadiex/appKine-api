package com.akine.resource.domain;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;

import java.time.Instant;

/**
 * Marcas temporales comunes a las entidades mutables de {@code resource}.
 *
 * <p>Existe para que {@code created_at} y {@code updated_at} no dependan de que cada servicio se
 * acuerde de setearlas: un olvido en un solo camino de escritura deja filas sin fecha de
 * modificacion y arruina la trazabilidad justo cuando hace falta.
 *
 * <p>Se persisten como instantes UTC (AGENT.md seccion 5). La zona local se aplica en las reglas
 * de negocio con la zona IANA de la sede, nunca en la base.
 *
 * <p><b>{@code organization.domain.TimestampedEntity} hace exactamente lo mismo y no se puede
 * reusar:</b> es de otro modulo y ArchUnit lo rechaza. Compartirla exigiria una clase base
 * global en {@code platform}, que es justamente la "capa global" que la regla 3 de AGENT.md
 * seccion 4 prohibe. Cuatro campos duplicados cuestan menos que abrir esa puerta.
 *
 * <p>No la heredarian las tablas append-only que este modulo pudiera necesitar mas adelante:
 * esas filas no se actualizan, asi que un {@code updated_at} ahi seria una invitacion a hacerlo.
 */
@MappedSuperclass
public abstract class MarcaTemporal {

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

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

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
