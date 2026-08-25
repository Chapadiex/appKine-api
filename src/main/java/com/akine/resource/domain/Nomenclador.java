package com.akine.resource.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Sistema de codificacion de prestaciones (RF-M06-003): Nomenclador Nacional, NBU, el propio
 * de un financiador, o el interno de un centro.
 *
 * <p><b>El nomenclador no codifica nada por si mismo.</b> Lo que codifica son sus
 * {@link NomencladorItem}, cada uno con su vigencia y su valor de referencia. Separar
 * contenedor de vigencias es lo que permite que el mismo codigo tenga historia sin duplicar el
 * nomenclador entero en cada actualizacion de valores.
 *
 * <p>Es tambien la <b>raiz de bloqueo</b> de este modulo: toda alta de vigencia toma primero
 * el lock exclusivo de la fila del nomenclador y despues consulta los solapamientos. El orden
 * es {@code nomenclador -> nomenclador_item}, y esta explicado en la cabecera de la migracion
 * V20 y en {@code CatalogoService}.
 */
@Entity
@Table(name = "nomenclador")
public class Nomenclador extends CatalogoConcepto {

	protected Nomenclador() {
		// Requerido por JPA.
	}

	public Nomenclador(
			Long organizationId,
			String codigo,
			String name,
			String descripcion,
			Instant validFrom,
			Instant validUntil) {

		super(organizationId, codigo, name, descripcion, validFrom, validUntil);
	}
}
