package com.akine.contracting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Una fila por sede cuyo unico proposito es ser bloqueada. <b>No guarda estado.</b>
 *
 * <p>MySQL 8.4 no tiene exclusion constraints —son de PostgreSQL— asi que el no-solapamiento de
 * dos convenios se valida en aplicacion (RN-M16-002). Y bloquear las filas de {@code convenio} que
 * YA existen no impide que otra transaccion INSERTE una en el hueco, que es exactamente el caso a
 * evitar. Hace falta una fila que siempre exista y que todas las escrituras de la sede se
 * disputen.
 *
 * <p>Es la misma solucion que {@code agenda_sede} le dio al solapamiento de turnos en 05.02 y que
 * {@code consultorio_calendario} le dio a la disponibilidad en 02.04. <b>No se reusa ninguna de las
 * dos</b> porque pertenecen a {@code scheduling} y a {@code resource}: un modulo no bloquea las
 * tablas de otro, y ArchUnit lo verifica.
 *
 * <p><b>Una fila por SEDE y no por (financiador, plan).</b> Serializa todas las escrituras de
 * convenios y aranceles de una sede entre si, que son unas pocas por mes en un centro de
 * kinesiologia — muy lejos del volumen de una agenda. Correctitud sobre paralelismo, mismo
 * criterio que {@code AgendaSede}. Con granularidad mas fina habria que tomar el lock del convenio
 * y el de su alcance, y dos locks exigen un orden total entre ellos para no deadlockear.
 */
@Entity
@Table(name = "convenio_lock")
public class ConvenioLock {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "consultorio_id", nullable = false, updatable = false)
	private Long consultorioId;

	protected ConvenioLock() {
		// Requerido por JPA.
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
}
