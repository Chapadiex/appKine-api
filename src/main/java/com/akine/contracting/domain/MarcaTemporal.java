package com.akine.contracting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;

import java.time.Instant;

/**
 * Marcas temporales comunes a las entidades de {@code contracting}.
 *
 * <p>Existe para que {@code created_at} y {@code updated_at} no dependan de que cada servicio de
 * aplicacion se acuerde de setearlas: un olvido en un solo camino de escritura deja filas sin
 * fecha de modificacion y arruina la trazabilidad justo cuando hace falta.
 *
 * <p>Se persisten como instantes UTC (AGENT.md §5).
 *
 * <p><b>{@code person.domain.MarcaTemporal} y {@code offering.domain.MarcaTemporal} hacen
 * exactamente lo mismo y no se pueden reusar:</b> son de otro modulo y ArchUnit rechaza
 * importarlas ({@code modulos_solo_se_alcanzan_por_su_spi}). Compartir una sola clase exigiria
 * una superclase global en {@code platform}, que es la "capa global" que AGENT.md §4 regla 3
 * prohibe. Cuatro campos duplicados por modulo cuestan menos que abrir esa puerta.
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
