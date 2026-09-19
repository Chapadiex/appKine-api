package com.akine.clinical.spi;

import java.time.Instant;
import java.util.List;

/**
 * Aporte de un modulo al timeline clinico de una Historia Clinica.
 *
 * <h2>La costura que 04.01 dejo escrita y 04.02 lleno</h2>
 *
 * <p>El timeline <b>no tiene tabla</b>: se calcula al leer, agregando lo que aporta cada
 * implementacion de esta interfaz. Una tabla de timeline seria una segunda copia de la verdad, y
 * el dia que alguien enmiende una entrada, de de baja un adjunto o anule una sesion sin avisarle
 * al proyector, esa copia miente sin que nadie se entere. Mismo argumento con el que 05.01 no
 * persiste slots.
 *
 * <p>El consumidor inyecta una {@code List<EventoClinicoContributor>}, que Spring resuelve con
 * todas las implementaciones registradas. <b>Por eso es lista y no bean singular</b>, al reves que
 * {@link RelacionAsistencialProbe}: el timeline es por definicion un agregado de varias fuentes, y
 * "cero eventos" es una respuesta valida.
 *
 * <p><b>La contrapartida esta asumida:</b> el costo de una pagina de timeline es la suma de las
 * consultas de todos los contribuyentes y crece con cada modulo que aporte. El tope por
 * contribuyente lo acota; el dia que sean ocho fuentes y el percentil 95 se note, la respuesta es
 * una proyeccion — y recien ahi una tabla con su escritor.
 *
 * <h2>Lo que un contribuyente NO puede poner en un evento</h2>
 *
 * <p>Ningun {@link EventoClinico} lleva texto de evolucion, diagnostico ni medicion: solo la
 * etiqueta del tipo de hecho. <b>El timeline es un indice, no un visor</b>, y quien quiera el
 * detalle va al modulo dueno con su propio permiso, donde ese acceso se audita aparte.
 */
public interface EventoClinicoContributor {

	/**
	 * Los eventos que este modulo aporta al timeline de esa historia, mas nuevos primero.
	 *
	 * <h2>Por que la firma lleva un tope temporal</h2>
	 *
	 * <p>El timeline pagina por <b>keyset descendente</b> y no por offset: un offset sobre un
	 * agregado de fuentes heterogeneas se desordena en cuanto una fuente inserta una fila. El
	 * agregador le pide a cada contribuyente los eventos anteriores o iguales a un instante, los
	 * mezcla y recorta.
	 *
	 * <p>Este parametro se agrego en 04.02, cuando la interfaz tenia <b>cero implementaciones</b> y
	 * cambiarla era gratis. El dia que haya cinco, cambiarla es tocar cinco modulos: si hace falta
	 * otro criterio de corte, conviene pensarlo antes de que aparezca el quinto.
	 *
	 * @param hasta  tope superior <b>inclusivo</b> de {@code ocurrioEn}. Es inclusivo a proposito:
	 *               el desempate entre eventos del mismo instante lo resuelve el agregador contra
	 *               el orden total, y un tope exclusivo saltearia los del instante del cursor
	 * @param limite tope de eventos a devolver. Cada contribuyente lo respeta por su cuenta: el
	 *               consumidor mezcla y vuelve a recortar
	 */
	List<EventoClinico> eventosDe(
			long organizationId, long historiaClinicaId, Instant hasta, int limite);
}
