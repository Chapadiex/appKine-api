package com.akine.encounter.domain.port;

import com.akine.encounter.domain.Sesion;

import java.util.Optional;

/**
 * Persistencia de sesiones.
 *
 * <p>Vive en {@code domain} porque {@code application} consume puertos y nunca repositorios de
 * {@code infrastructure}: es la regla que 01.01 dejo fijada y que ArchUnit verifica.
 */
public interface SesionRepositoryPort {

	Sesion save(Sesion sesion);

	/**
	 * Guarda y <b>vacia la sesion de persistencia</b>, para que la fila quede escrita ya.
	 *
	 * <p>Hace falta en todo camino cuya respuesta lleve la {@code version} de la sesion, que son
	 * los cuatro de escritura. {@code save} sobre una entidad ya gestionada es un merge: deja el
	 * {@code UPDATE} pendiente y Hibernate incrementa {@code @Version} recien en el flush, al
	 * cerrar la transaccion — o sea <b>despues</b> de que la vista ya leyo {@code getVersion()}.
	 * El cliente se lleva la version vieja, la manda en su operacion siguiente y come un 409 del
	 * que no puede salir salvo releyendo la sesion entera.
	 *
	 * <p>Es la regla 5 del repositorio —"{@code save()} antes del flush devuelve la version
	 * vieja"— que 02.07 ya pago y que 04.02 volvio a encontrar por otra puerta. Se aplica aca en
	 * 06.06 porque la enmienda es la primera operacion que <b>consume</b> la version que devuelve
	 * el cierre: hasta que existio, el sintoma no tenia por donde manifestarse.
	 */
	Sesion saveAndFlush(Sesion sesion);

	Optional<Sesion> findByIdInScope(long organizationId, long consultorioId, long sesionId);

	/**
	 * La sesion viva de ese turno, si ya se inicio.
	 *
	 * <p>Es lo que hace idempotente el doble inicio: RN-M14-001 dice que un turno produce como
	 * mucho una sesion, y el {@code uk_sesion_turno} de V33 lo hace cumplir del lado del motor.
	 * Esta consulta es la que permite devolver la sesion existente en vez de chocar contra el
	 * unique y contestar un 409 que para el usuario no significa nada — apreto dos veces.
	 */
	Optional<Sesion> findVivaPorTurno(long organizationId, long turnoId);

	/**
	 * La sesion anterior del mismo paciente que tenga evaluacion cargada.
	 *
	 * <p>Es lo que permite comparar: "la vez pasada tenia 7". Se filtra por {@code evaluadaEn}
	 * y no simplemente por la anterior en el tiempo, porque una sesion que se abrio y no se
	 * evaluo no tiene nada contra que comparar y devolverla mostraria campos vacios donde el
	 * profesional espera un numero.
	 */
	Optional<Sesion> findPreviaEvaluada(
			long organizationId, long historiaClinicaId, java.time.Instant antesDe);

	/**
	 * Las sesiones <b>cerradas</b> de una historia para una pagina de timeline: las
	 * {@code limite} mas recientes con {@code cerradaEn <= hasta}.
	 *
	 * <p><b>Solo cerradas, y no es un filtro negociable.</b> Una sesion en borrador es eso, un
	 * borrador: indexarla pondria en el timeline de un paciente una fila cuyo contenido cambia
	 * mientras alguien la mira. La sesion cerrada si es un hecho clinico ocurrido, que es lo que
	 * DP-05 distingue de cualquier transicion administrativa.
	 *
	 * <p>El instante del hecho es {@code cerrada_en} y no {@code iniciada_en}: es el momento en
	 * que la atencion quedo asentada. El indice {@code ix_sesion_cerradas} de V35 sostiene
	 * exactamente esta consulta.
	 *
	 * @param casoId filtro opcional por Caso Clinico (04.03). {@code null} no filtra. <b>Esta es la
	 *               unica fuente del timeline que puede atribuir un hecho a un caso</b>, porque es
	 *               la unica tabla que guarda {@code caso_id}: la entrada clinica, el adjunto y el
	 *               antecedente cuelgan de la historia. Con caso, la consulta pasa a calzar con
	 *               {@code ix_sesion_caso} en vez de con {@code ix_sesion_cerradas}
	 */
	java.util.List<Sesion> buscarCerradasParaTimeline(
			long organizationId,
			long historiaClinicaId,
			java.time.Instant hasta,
			int limite,
			Long casoId);

	/**
	 * Cuantas sesiones <b>cerradas</b> hay en ese Caso, agrupadas por oferta y repartidas por
	 * asistencia.
	 *
	 * <p>Es lo que sostiene el avance del Plan de Tratamiento (M11), que se <b>deriva al leer</b>
	 * porque {@code plan_item} no tiene columna de realizadas — si la tuviera, la mantendria
	 * correcta este modulo y la guardaria otro, que es como se desincroniza un contador. La
	 * consulta la consume {@code clinical} por {@code clinical.spi.RealizadoEnElCasoProbe}: este
	 * modulo no conoce al Plan y no tiene por que.
	 *
	 * <p><b>Solo cerradas, y no es negociable</b>: una sesion en borrador es eso, un borrador, y
	 * contarla haria que el avance de un plan se moviera mientras alguien tipea. Solo la sesion
	 * cerrada es un hecho clinico ocurrido (DP-05).
	 *
	 * <p>El reparto lo hace la asistencia, que {@code CierreDeSesion} exige al cerrar: presente es
	 * una realizacion, ausente no lo es. No hay tercer grupo — una sesion cerrada sin asistencia no
	 * la puede producir el dominio.
	 *
	 * @return una entrada por oferta con al menos una sesion cerrada. Las ofertas sin sesiones no
	 *         aparecen: el llamador las resuelve como cero
	 */
	java.util.List<com.akine.encounter.domain.ConteoDeSesionesPorOferta> contarCerradasPorOferta(
			long organizationId, long casoId);
}
