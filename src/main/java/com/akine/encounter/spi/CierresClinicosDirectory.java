package com.akine.encounter.spi;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * Las sesiones cerradas de una sede, reducidas a lo minimo que necesita el reporte de continuidad
 * del circuito (RF-M23-008, AKINE-09.01b): de que historia, de que oferta y cuando.
 *
 * <h2>"Alta clinica" es la proxima conducta {@code ALTA}</h2>
 *
 * <p>El unico dato tipado que dice "este paciente recibio el alta" es la proxima conducta que el
 * profesional registra al cerrar la sesion (RF-M14-007, V35: {@code CONTINUA}, {@code ALTA},
 * {@code DERIVA}, {@code REEVALUA}). El cierre del Caso (RF-M10-005) lleva un motivo en <b>texto
 * libre</b>, que no distingue un alta de un abandono: contarlo seria inferir el alta desde la prosa.
 *
 * <h2>Lo que NO sale por aca</h2>
 *
 * <p>Ni la evolucion, ni las indicaciones, ni el motivo, ni el profesional: solo ids e instantes. El
 * llamador agrega y nunca devuelve filas por persona (RN-M23-001, "sin exponer contenido clinico
 * innecesario"), y se autoriza con {@code hc:read} antes de llegar aca.
 *
 * <p>La interfaz vive en {@code encounter}, que es duenia de {@code sesion}, y la consume
 * {@code activity}: la arista {@code activity -> encounter.spi} ya existe desde 08.05.
 */
public interface CierresClinicosDirectory {

	/**
	 * Las sesiones cerradas en la sede dentro de {@code [desde, hasta)} con proxima conducta
	 * {@code ALTA}, sin la baja logica.
	 */
	List<CierreClinico> altasDelPeriodo(
			long organizationId, long consultorioId, Instant desde, Instant hasta);

	/**
	 * Las sesiones cerradas en la sede, de esas historias, <b>despues</b> de {@code desde}
	 * (exclusivo), con cualquier conducta, sin la baja logica. Sin tope superior: lo registrado
	 * hasta el momento de la consulta.
	 */
	List<CierreClinico> cerradasDespuesDe(
			long organizationId, long consultorioId, Collection<Long> historiaClinicaIds, Instant desde);

	/** Una sesion cerrada, sin contenido clinico. */
	record CierreClinico(long historiaClinicaId, long ofertaId, Instant cerradaEn) {
	}
}
