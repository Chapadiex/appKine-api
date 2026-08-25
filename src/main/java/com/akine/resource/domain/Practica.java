package com.akine.resource.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Prestacion clinica asociada a una especialidad (RF-M06-002).
 *
 * <h2>La regla de alcance que la clave foranea no puede expresar</h2>
 *
 * <p>Una practica <b>GLOBAL solo puede colgar de una especialidad GLOBAL</b>. Si colgara de la
 * especialidad de un tenant, ese tenant decidiria por si solo el destino de un concepto que
 * ven todos los demas: darla de baja dejaria huerfana una practica de plataforma. Al reves si
 * vale — una practica contextual puede colgar de una especialidad global o de una propia.
 *
 * <p>Una FK compara ids, no alcances, asi que la comprobacion vive en el servicio de
 * aplicacion, que es el unico que tiene las dos filas a la vista dentro de la misma
 * transaccion. Aca queda declarado el invariante para que quien lea la entidad lo encuentre.
 *
 * <h2>Por que la especialidad no se puede cambiar</h2>
 *
 * <p>{@code updatable = false}: mover una practica de especialidad cambiaria el significado de
 * todos los hechos historicos que la referencian —una sesion registrada bajo Kinesiologia
 * pasaria a decir que fue de Fonoaudiologia— y RN-M06-002 lo prohibe. Una practica que cambia
 * de especialidad es una que se da de baja y otra que se crea.
 */
@Entity
@Table(name = "practica")
public class Practica extends CatalogoConcepto {

	@Column(name = "especialidad_id", nullable = false, updatable = false)
	private Long especialidadId;

	protected Practica() {
		// Requerido por JPA.
	}

	public Practica(
			Long organizationId,
			Long especialidadId,
			String codigo,
			String name,
			String descripcion,
			Instant validFrom,
			Instant validUntil) {

		super(organizationId, codigo, name, descripcion, validFrom, validUntil);
		if (especialidadId == null) {
			throw new IllegalArgumentException(
					"Toda practica pertenece a una especialidad (RF-M06-002)");
		}
		this.especialidadId = especialidadId;
	}

	public Long getEspecialidadId() {
		return especialidadId;
	}
}
