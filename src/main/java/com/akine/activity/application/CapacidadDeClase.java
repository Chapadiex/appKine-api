package com.akine.activity.application;

import com.akine.activity.domain.ClaseProgramada;
import com.akine.offering.spi.OfertaDirectory;
import com.akine.offering.spi.OfertaSnapshot;
import com.akine.resource.spi.EspacioDirectory;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * La capacidad efectiva de una clase: {@code min(propia, oferta, espacio)} (RN-M28-002).
 *
 * <h2>Por que es un colaborador y no un metodo privado de cada servicio</h2>
 *
 * <p>Porque la usan dos —{@code ClaseService} para validar la capacidad pedida y
 * {@code InscripcionService} para decidir cuantos lugares hay— y <b>tiene que ser exactamente el
 * mismo numero</b>. Dos copias divergen en la primera modificacion, y la divergencia se ve como una
 * clase que acepta un inscripto mas de los que dice tener, o como uno menos: las dos igual de
 * dificiles de explicar.
 *
 * <p><b>Se calcula al leer y nunca se materializa</b>, igual que la disponibilidad efectiva de
 * 02.04 y los slots de 05.01. Si se guardara, mover una clase a un box mas chico dejaria filas
 * prometiendo lugares que ya no existen.
 */
@Component
public class CapacidadDeClase {

	private final OfertaDirectory ofertas;
	private final EspacioDirectory espacios;

	public CapacidadDeClase(OfertaDirectory ofertas, EspacioDirectory espacios) {
		this.ofertas = ofertas;
		this.espacios = espacios;
	}

	/**
	 * El minimo entre la capacidad propia, la de la oferta y la del espacio.
	 *
	 * @param capacidadPropia la de la clase, o la que se esta pidiendo al programar
	 */
	public int efectiva(
			long organizationId,
			OfertaSnapshot oferta,
			Long espacioId,
			Instant at,
			int capacidadPropia) {

		int efectiva = Math.min(capacidadPropia, oferta.capacidad());
		if (espacioId == null) {
			return efectiva;
		}
		return espacios.find(organizationId, espacioId, at)
				.map(espacio -> Math.min(efectiva, espacio.capacidad()))
				.orElse(efectiva);
	}

	/**
	 * Lo mismo para una clase ya existente.
	 *
	 * <p>Si la oferta no se puede resolver, <b>cae a la capacidad propia de la clase</b> en vez de
	 * fallar: una oferta borrada no puede hacer que una clase ya programada deje de poder leerse, y
	 * la capacidad propia es el ultimo valor que alguien aprobo explicitamente.
	 */
	public int efectiva(long organizationId, long consultorioId, ClaseProgramada clase) {
		return ofertas.find(organizationId, consultorioId, clase.getOfertaId())
				.map(oferta -> efectiva(organizationId, oferta, clase.getEspacioId(),
						clase.getInicio(), clase.getCapacidad()))
				.orElseGet(clase::getCapacidad);
	}
}
