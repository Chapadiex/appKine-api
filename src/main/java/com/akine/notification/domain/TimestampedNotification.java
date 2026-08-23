package com.akine.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;

import java.time.Instant;

/**
 * Marcas temporales de las entidades mutables de {@code notification}.
 *
 * <p>Duplica a proposito la clase equivalente de {@code organization}: compartirla obligaria a
 * uno de los dos modulos a importar el modelo del otro, que es exactamente lo que la regla de
 * ownership prohibe. Una clase de ocho lineas repetida es mas barata que un acoplamiento entre
 * modulos; cuando haya un tercer modulo con la misma necesidad, el lugar correcto es
 * {@code platform}, no un import cruzado.
 *
 * <p>Instantes UTC (AGENT.md seccion 5).
 */
@MappedSuperclass
public abstract class TimestampedNotification {

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
