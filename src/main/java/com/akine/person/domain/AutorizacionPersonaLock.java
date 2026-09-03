package com.akine.person.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * La fila que existe unicamente para ser bloqueada (M17).

 * <p>No guarda estado. Serializa las aprobaciones de autorizaciones de un paciente, porque
 * bloquear las filas de {@code autorizacion} que YA existen no impide que otra transaccion inserte
 * una nueva en el hueco — que es exactamente el caso a evitar — y MySQL 8.4 no tiene exclusion
 * constraints.
 *
 * <p><b>No reusa {@code cobertura_persona_lock}</b>, que tiene la misma granularidad: protege otra
 * invariante, y compartirlo ataria dos reglas independientes al mismo punto de contencion sin que
 * ninguna pueda romper a la otra.
 *
 * <p>La fila la crea {@code AutorizacionLockIniciador} en una transaccion aparte. Crearla
 * perezosamente dentro de la que la bloquea produce deadlock: ver el javadoc de esa clase.
 */
@Entity
@Table(name = "autorizacion_persona_lock")
public class AutorizacionPersonaLock {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "persona_id", nullable = false, updatable = false)
	private Long personaId;

	public AutorizacionPersonaLock() {
		// Requerido por JPA. Publico porque la fila la inserta una sentencia nativa
		// —INSERT ... ON DUPLICATE KEY UPDATE— y nadie la construye para persistirla.
	}

	public Long getId() {
		return id;
	}

	public Long getOrganizationId() {
		return organizationId;
	}

	public Long getPersonaId() {
		return personaId;
	}
}
