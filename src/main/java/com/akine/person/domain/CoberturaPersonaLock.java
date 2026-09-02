package com.akine.person.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Una fila por persona cuyo unico proposito es ser bloqueada. <b>No guarda estado.</b>
 *
 * <p>MySQL 8.4 no tiene exclusion constraints —son de PostgreSQL— asi que el solapamiento de dos
 * coberturas se valida en aplicacion. Y bloquear las filas de {@code cobertura_paciente} que YA
 * existen no impide que otra transaccion INSERTE una en el hueco, que es exactamente el caso a
 * evitar. Hace falta una fila que siempre exista y que todas las escrituras de coberturas de esa
 * persona se disputen.
 *
 * <p>Es la misma solucion que {@code agenda_sede} le dio a la reserva de turnos en 05.02 y
 * {@code consultorio_calendario} a la disponibilidad en 02.04. <b>No se reusa ninguna de las
 * dos</b> porque pertenecen a otros modulos: un modulo no bloquea las tablas de otro, y ArchUnit
 * lo verifica.
 *
 * <p><b>Granularidad por PERSONA y no por organizacion.</b> Las dos reglas que serializa —dos
 * coberturas activas del mismo plan solapadas, y dos principales solapadas— son invariantes de UN
 * paciente y nunca cruzan de uno a otro. Un lock por organizacion serializaria el padron entero
 * para proteger algo que no lo necesita.
 */
@Entity
@Table(name = "cobertura_persona_lock")
public class CoberturaPersonaLock {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "persona_id", nullable = false, updatable = false)
	private Long personaId;

	public CoberturaPersonaLock() {
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
