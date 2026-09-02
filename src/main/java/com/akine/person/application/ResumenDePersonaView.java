package com.akine.person.application;

import com.akine.person.application.ResumenDePersonaService.SeccionOmitida;
import com.akine.person.spi.AporteDeResumen;

import java.util.List;
import java.util.Map;

/**
 * El Paciente 360 tal como sale de {@code application}.
 *
 * @param persona              la ficha administrativa completa, con su perfil ya resuelto
 * @param adjuntosTotal        cuantos adjuntos VIGENTES tiene. Los dados de baja no cuentan: el
 *                             360 muestra lo que hay, no lo que hubo
 * @param adjuntosPorCategoria conteo por categoria, para que la ficha pueda decir "falta la
 *                             credencial" sin traerse la lista
 * @param secciones            lo que aportaron los modulos rio abajo, en el orden en que Spring
 *                             los entrego. El orden de presentacion lo decide la pantalla
 * @param seccionesOmitidas    las que el actor no puede ver. <b>Viajan explicitas</b>: omitirlas
 *                             en silencio haria leer "sin turnos" donde en realidad dice "no
 *                             podes ver los turnos"
 */
public record ResumenDePersonaView(
		PersonaView persona,
		long adjuntosTotal,
		Map<String, Long> adjuntosPorCategoria,
		List<AporteDeResumen> secciones,
		List<SeccionOmitida> seccionesOmitidas) {
}
