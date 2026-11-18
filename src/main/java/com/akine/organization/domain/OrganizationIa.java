package com.akine.organization.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Funciones de IA que el centro decidio usar. Que esten prendidas aca no alcanza: el despliegue
 * tambien tiene que ofrecerlas. La fila nace en la primera edicion; sin fila, todo prendido.
 */
@Entity
@Table(name = "organization_ia")
public class OrganizationIa extends TimestampedEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "asistente", nullable = false)
	private boolean asistente = true;

	@Column(name = "evolucion", nullable = false)
	private boolean evolucion = true;

	@Column(name = "sugerencias", nullable = false)
	private boolean sugerencias = true;

	@Column(name = "biblioteca", nullable = false)
	private boolean biblioteca = true;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected OrganizationIa() {
	}

	public void reemplazar(boolean asistente, boolean evolucion, boolean sugerencias, boolean biblioteca) {
		this.asistente = asistente;
		this.evolucion = evolucion;
		this.sugerencias = sugerencias;
		this.biblioteca = biblioteca;
	}

	public Long getOrganizationId() {
		return organizationId;
	}

	public boolean isAsistente() {
		return asistente;
	}

	public boolean isEvolucion() {
		return evolucion;
	}

	public boolean isSugerencias() {
		return sugerencias;
	}

	public boolean isBiblioteca() {
		return biblioteca;
	}

	public long getVersion() {
		return version;
	}
}
