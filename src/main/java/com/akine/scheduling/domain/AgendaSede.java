package com.akine.scheduling.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Una fila por sede cuyo unico proposito es ser bloqueada. <b>No guarda estado.</b>
 *
 * <p>MySQL 8.4 no tiene exclusion constraints —son de PostgreSQL— asi que el solapamiento de dos
 * turnos se valida en aplicacion. Y bloquear las filas de {@code turno} que YA existen no impide
 * que otra transaccion INSERTE una en el hueco, que es exactamente el caso a evitar. Hace falta
 * una fila que siempre exista y que todas las reservas de la sede se disputen.
 *
 * <p>Es la misma solucion que {@code consultorio_calendario} le dio a la disponibilidad en 02.04.
 * <b>No se reusa aquella fila</b> porque pertenece al modulo {@code resource}: un modulo no escribe
 * ni bloquea las tablas de otro, y ArchUnit lo verifica.
 *
 * <p><b>Una fila por SEDE y no por profesional.</b> Serializa todas las reservas de una sede entre
 * si, que para un centro de kinesiologia son unas pocas por minuto. Se eligio correctitud sobre
 * paralelismo: con granularidad por recurso habria que tomar DOS locks —profesional y espacio— y
 * dos locks exigen un orden total entre ellos para no deadlockear. Si el volumen algun dia lo pide,
 * esta tabla admite filas por recurso sin migrar un solo turno.
 */
@Entity
@Table(name = "agenda_sede")
public class AgendaSede {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "consultorio_id", nullable = false, updatable = false)
	private Long consultorioId;

	protected AgendaSede() {
		// Requerido por JPA.
	}

	public AgendaSede(long organizationId, long consultorioId) {
		this.organizationId = organizationId;
		this.consultorioId = consultorioId;
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
