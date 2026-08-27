package com.akine.offering.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * En que espacio fisico puede prestarse una Oferta (RF-M04-008).
 *
 * <h2>Que un espacio sirva para una oferta NO se infiere del espacio</h2>
 *
 * <p>Es literalmente lo que pide RF-M04-008: "sin inferirlo por el nombre del espacio". Elegir el
 * tipo "Pileta" al dar de alta un espacio no autoriza a hacer hidroterapia ahi; lo autoriza esta
 * fila y nada mas. El tipo de espacio es una clasificacion fisica y esta tabla es una decision del
 * centro, y confundirlas es la clase de regla por nombre que las reglas maestras 15 y 20 prohiben.
 *
 * <h2>Esta tabla es la que acota la capacidad efectiva</h2>
 *
 * <p>{@code capacidadEfectiva = min(capacidad de la oferta, capacidad de los espacios
 * habilitados)}. Sin espacios habilitados la efectiva es la de la oferta: no hay ningun espacio
 * concreto contra el cual acotarla todavia.
 *
 * <p>El calculo <b>no vive aca ni se materializa en ninguna columna</b>: se hace al leer, con el
 * mismo criterio que la disponibilidad efectiva de 02.04. Guardarlo obligaria a recalcularlo cada
 * vez que cambia la capacidad de un espacio —una fila de otro modulo— y la primera vez que alguien
 * olvide hacerlo, la agenda sobrevende un box.
 *
 * <h2>Un espacio habilitado puede darse de baja en {@code resource}</h2>
 *
 * <p>Y esta fila queda apuntandolo. No se resuelve con una FK ni con un trigger: {@code resource}
 * no puede depender de {@code offering} sin cerrar un ciclo, y cascadear la baja borraria el
 * historico que RN-M03-006 protege. Se resuelve <b>al leer</b>: la habilitacion sigue existiendo y
 * la validacion responde que ese espacio no esta en servicio, nombrando la condicion que falla.
 */
@Entity
@Table(name = "oferta_espacio_habilitado")
public class OfertaEspacioHabilitado extends Habilitacion {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "espacio_id", nullable = false)
	private Long espacioId;

	protected OfertaEspacioHabilitado() {
		// Requerido por JPA.
	}

	public OfertaEspacioHabilitado(
			Long organizationId,
			Long consultorioId,
			Long ofertaId,
			Long espacioId,
			Instant validFrom,
			Instant validUntil) {

		super(organizationId, consultorioId, ofertaId, validFrom, validUntil);
		if (espacioId == null) {
			throw new IllegalArgumentException("La habilitacion de un espacio exige el espacio");
		}
		this.espacioId = espacioId;
	}

	public Long getId() {
		return id;
	}

	public Long getEspacioId() {
		return espacioId;
	}

	@Override
	public Long getRecursoId() {
		return espacioId;
	}
}
