package com.akine.encounter.infrastructure;

import com.akine.clinical.spi.EventoClinico;
import com.akine.clinical.spi.EventoClinicoContributor;
import com.akine.encounter.domain.port.SesionRepositoryPort;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Las sesiones <b>cerradas</b> de la historia, aportadas al timeline clinico (RF-M09-004).
 *
 * <h2>Por que la implementacion vive aca y no en {@code clinical}</h2>
 *
 * <p>La interfaz es de {@code clinical.spi} y la implementacion de {@code encounter} porque la
 * dependencia entre los dos modulos ya va en este sentido desde 06.01
 * ({@code HistoriaClinicaDirectory}): {@code encounter} conoce a {@code clinical}, nunca al reves.
 * {@code clinical} no gana ninguna dependencia — recibe una lista de interfaces que Spring le
 * inyecta y no sabe quien las implementa.
 *
 * <p>Y por lo mismo, <b>la Sesion no se copia a una {@code entrada_clinica}</b>: dos modulos serian
 * duenos de la misma fila y la evolucion viviria duplicada en {@code sesion.evolucion} y en
 * {@code entrada_clinica_version.cuerpo}, con la garantia de que en algun momento discrepan.
 *
 * <h2>Solo cerradas, y el Turno no aporta nada</h2>
 *
 * <p>Una sesion en borrador no se indexa: es un texto que todavia esta cambiando, y ponerlo en el
 * timeline mostraria una fila cuyo contenido muta mientras alguien la mira.
 *
 * <p><b>El Turno no contribuye al timeline y es una decision, no un olvido.</b> RN-M09-005 deja
 * las asistencias no clinicas fuera de la Historia Clinica y DP-05 es explicito en que ninguna
 * transicion administrativa prueba que una prestacion ocurrio. Un turno reservado, cancelado o
 * ausente es un hecho de agenda; la sesion cerrada es el hecho clinico.
 *
 * <h2>Lo que el evento no lleva</h2>
 *
 * <p>Ni evolucion, ni motivo clinico, ni EVA, ni nota de cierre. Solo el instante del cierre y la
 * etiqueta del hecho. Quien quiera la sesion va a {@code encounter} con {@code sesion:read} y ese
 * acceso se audita alla. <b>Tampoco se importa nada de {@code clinical} que no sea su {@code spi}</b>,
 * ni al reves: el titulo viaja dentro del evento que este modulo construye, que es justamente lo
 * que evita que {@code clinical} termine importando {@code encounter.domain}.
 */
@Component
public class SesionEventoContributor implements EventoClinicoContributor {

	/** Como se identifica esta fuente en el orden total del timeline y en el cursor. */
	static final String ORIGEN = "SESION";

	/** Unico tipo que este contribuyente emite: no hay otro estado que sea un hecho clinico. */
	static final String TIPO_SESION_CERRADA = "SESION_CERRADA";

	private final SesionRepositoryPort sesiones;

	public SesionEventoContributor(SesionRepositoryPort sesiones) {
		this.sesiones = sesiones;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p><b>Esta es la unica fuente del timeline que puede filtrar por caso</b>, y no por
	 * casualidad: {@code sesion} es la unica tabla del sistema que guarda {@code caso_id}. La
	 * entrada clinica, el adjunto y el antecedente cuelgan de la historia, asi que con filtro por
	 * caso devuelven vacio en vez de devolver de mas. El filtro se aplica <b>en la base</b>, no
	 * despues: recortar en memoria sobre un {@code LIMIT} ya aplicado devolveria menos eventos de
	 * los que el caso tiene.
	 */
	@Override
	public List<EventoClinico> eventosDe(
			long organizationId, long historiaClinicaId, Instant hasta, int limite, Long casoId) {

		return sesiones
				.buscarCerradasParaTimeline(organizationId, historiaClinicaId, hasta, limite, casoId)
				.stream()
				.map(sesion -> new EventoClinico(
						sesion.getCerradaEn(),
						ORIGEN,
						TIPO_SESION_CERRADA,
						"Sesion cerrada",
						sesion.getId()))
				.toList();
	}
}
