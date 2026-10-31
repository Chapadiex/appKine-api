package com.akine.scheduling.spi;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Cuantas reservas ya tomo cada slot. Es la costura hacia AKINE-05.02.
 *
 * <h2>Por que existe antes que los turnos</h2>
 *
 * <p>DP-10 fija la regla del recorte: <b>se corta alcance, no modelo</b>. El motor de slots sin
 * descuento de reservas ofrece huecos ya vendidos, asi que la resta no es opcional: lo que se
 * difiere es QUIEN la contesta, no el hecho de preguntarla. Durante 05.01 respondio una
 * implementacion provisoria con el mapa vacio; hoy contesta
 * {@link com.akine.scheduling.infrastructure.ReservaProbeSobreTurnos} contando los turnos vivos
 * de la ventana, sin que el motor haya cambiado una linea.
 *
 * <p>Es exactamente la misma forma que {@code resource.spi.DisponibilidadImpactProbe}, que 02.04
 * dejo cableada devolviendo cero por el mismo motivo.
 *
 * <h2>El contrato de concurrencia, y por que esto NO alcanza para reservar</h2>
 *
 * <p>Esta lectura es una <b>foto</b>. Entre que devuelve y que alguien confirma un turno, otro
 * puede haber tomado el ultimo lugar. Un slot con cupo libre segun esta sonda <b>no es una
 * promesa de que se pueda reservar</b>: la exclusion real la tiene que dar 05.02 en la escritura,
 * con el lock que corresponda. Si la reserva confiara en este resultado, dos recepcionistas
 * mirando la misma pantalla venden el mismo turno.
 */
public interface ReservaProbe {

	/**
	 * Reservas vigentes por instante de inicio de slot, dentro de {@code [desde, hasta)}.
	 *
	 * <p>La clave es el instante de inicio porque es lo unico que el motor y la agenda comparten
	 * sin ambiguedad: el motor no persiste slots y por lo tanto no tiene ids que ofrecer.
	 *
	 * <p><b>Solo cuenta las reservas que ocupan lugar.</b> Un turno cancelado libera su cupo y no
	 * debe aparecer; uno con paciente ausente SI lo ocupo y la decision de si se libera es de
	 * 05.03, no de aca.
	 *
	 * @param recursoIds memberships de profesional, o espacios, segun que este agrupando el motor.
	 *                   Vacio significa "sin filtro por recurso"
	 * @return mapa por instante de inicio; un instante ausente significa cero reservas
	 */
	Map<Instant, Integer> reservasPorInicio(
			long organizationId,
			long consultorioId,
			long ofertaId,
			List<Long> recursoIds,
			Instant desde,
			Instant hasta);

	/**
	 * Lo que tiene ocupados a esos profesionales o a esos espacios en la ventana: turnos vivos de
	 * cualquier oferta, y clases de la sede.
	 *
	 * <p>Es lo que hace que la agenda no ofrezca un horario que la reserva despues rechaza. Un slot
	 * libre de ESTA oferta no alcanza: el profesional puede estar con otra prestacion a esa hora, o
	 * todos los boxes pueden estar tomados.
	 */
	List<OcupacionDeRecurso> ocupacionDeRecursos(
			long organizationId,
			long consultorioId,
			Collection<Long> profesionalIds,
			Collection<Long> espacioIds,
			Instant desde,
			Instant hasta);

	/** @param ofertaId {@code null} si no es un turno: una clase nunca es del mismo grupo */
	record OcupacionDeRecurso(
			Long turnoId, Long ofertaId, Long profesionalId, Long espacioId, Instant inicio, Instant fin) {

		public boolean seCruzaCon(Instant desde, Instant hasta) {
			return inicio.isBefore(hasta) && desde.isBefore(fin);
		}
	}
}
