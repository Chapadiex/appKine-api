package com.akine.scheduling.application;

import java.time.Instant;

/**
 * Un turno de la agenda del dia, con lo minimo que la recepcion necesita para llamar al paciente.
 *
 * <h2>Por que existe si ya hay {@link TurnoView}</h2>
 *
 * <p>{@code TurnoView} declara en su cabecera que <b>no lleva datos del paciente mas alla de su
 * id</b>, y esa decision se sostiene: es la respuesta de reservar y de operar, y ahi el nombre
 * sobra. La recepcion es el caso contrario —una lista de personas que van llegando— y una pantalla
 * que muestre "turno 12:00, persona #5" no sirve para llamar a nadie.
 *
 * <p>Resolver el nombre desde el cliente seria una consulta por fila. Por eso viaja resuelto, y
 * por eso es un tipo aparte en vez de campos opcionales sobre el otro: dos respuestas con
 * necesidades distintas de PHI no deberian compartir forma, porque el dia que alguien agregue un
 * campo se lo agrega a las dos.
 *
 * <h2>Que NO lleva, y es deliberado</h2>
 *
 * <p><b>Nada clinico.</b> Ni diagnostico, ni motivo de consulta, ni evolucion: el plan lo pide
 * explicito —"PHI minima en recepcion"— y quien atiende el mostrador no necesita saber por que
 * viene el paciente para decirle que pase. Lo unico que se muestra de la prestacion es el nombre
 * comercial de la oferta, que es lo que ya figura en el comprobante del turno.
 *
 * @param personaNombre     apellido y nombre, ya armados: la pantalla no compone identidades
 * @param documento         tipo y numero, para desambiguar dos personas con el mismo nombre
 * @param llegadaEn         hora real de llegada; {@code null} mientras el paciente no llego
 * @param motivoCancelacion presente solo si el turno esta cancelado — la recepcion los ve igual,
 *                          porque alguien puede presentarse a un turno que se cancelo
 */
public record TurnoDelDiaView(
		long id,
		Instant inicio,
		Instant fin,
		String estado,
		long personaId,
		String personaNombre,
		String documento,
		long ofertaId,
		String ofertaNombre,
		Long profesionalId,
		Long espacioId,
		Instant llegadaEn,
		String motivoCancelacion,
		long version) {
}
