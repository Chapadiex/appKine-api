package com.akine.resource.domain.port;

import com.akine.resource.domain.BloqueDisponibilidad;
import com.akine.resource.domain.CalendarioSede;
import com.akine.resource.domain.DisponibilidadExcepcion;
import com.akine.resource.domain.Feriado;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Los cuatro puertos de persistencia de la disponibilidad profesional (M05), en un solo
 * archivo, igual que {@code CatalogoRepositoryPorts}.
 *
 * <h2>Por que estan juntos</h2>
 *
 * <p>Los cuatro son la persistencia de un mismo diseno (AKINE-02.04) y comparten protocolo de
 * aislamiento con una unica excepcion declarada. Tenerlos a la vista hace evidente esa
 * excepcion en vez de esconderla en un archivo aparte que nadie compara con los otros tres.
 *
 * <h2>El aislamiento: {@code organizationId}, salvo {@link FeriadoRepositoryPort}</h2>
 *
 * <p>{@code BloqueDisponibilidadRepositoryPort}, {@code DisponibilidadExcepcionRepositoryPort}
 * y {@code CalendarioSedeRepositoryPort} filtran TODA consulta por {@code organizationId} y,
 * ademas, por {@code consultorioId}: un id de otro tenant, o de otra sede del mismo tenant, no
 * debe resolver nunca (ADR-0004, RN-M05-001). {@code FeriadoRepositoryPort} es la unica
 * excepcion, y es deliberada: ver su javadoc.
 *
 * <h2>Por que {@code lockByScope} esta en {@code CalendarioSedeRepositoryPort} y no en los
 * bloques</h2>
 *
 * <p>MySQL 8.4 no tiene exclusion constraints (son de PostgreSQL), asi que el solapamiento de
 * {@link BloqueDisponibilidad} se valida en aplicacion. Bloquear las filas de bloques que ya
 * existen no alcanza: no impide que otra transaccion INSERTE una fila nueva en el hueco, que es
 * justamente el caso a evitar. La fila de {@link CalendarioSede} de la sede, en cambio, siempre
 * existe (se crea a demanda) y sirve de punto unico de serializacion para todos los writes de
 * disponibilidad de esa sede. Detalle completo en el diseno de etapa §5.
 */
public final class DisponibilidadRepositoryPorts {

	private DisponibilidadRepositoryPorts() {
		// Contenedor de puertos.
	}

	/** Bloques recurrentes de atencion de un profesional en una sede (RF-M05-003). */
	public interface BloqueDisponibilidadRepositoryPort {

		/**
		 * Bloques ACTIVOS de esa membership en esa sede cuya ventana de vigencia se solapa con
		 * {@code [desde, hasta)}. Es la consulta de lectura: la que arma la disponibilidad
		 * efectiva de una ventana concreta, no la que valida altas.
		 */
		List<BloqueDisponibilidad> findVigentesEn(
				Long organizationId, Long consultorioId, Long membershipId,
				LocalDate desde, LocalDate hasta);

		/**
		 * Todos los bloques ACTIVOS de esa membership en esa sede, sin filtro de fecha.
		 *
		 * <p>Es la base de la deteccion de solapamiento de RF-M05-005 y se ejecuta SIEMPRE
		 * despues de {@link CalendarioSedeRepositoryPort#lockByScope}. No filtra por vigencia
		 * porque dos bloques de vigencia futura pueden solapar entre si sin que ninguno este
		 * vigente todavia, y ese solapamiento hay que rechazarlo igual en el alta.
		 */
		List<BloqueDisponibilidad> findActivosDe(Long organizationId, Long consultorioId, Long membershipId);

		/** Un bloque por id, acotado al tenant y a la sede, activo o no. */
		Optional<BloqueDisponibilidad> findByIdScoped(Long id, Long organizationId, Long consultorioId);

		BloqueDisponibilidad save(BloqueDisponibilidad bloque);
	}

	/** Cierres y aperturas puntuales de disponibilidad (RF-M05-004). */
	public interface DisponibilidadExcepcionRepositoryPort {

		/**
		 * Excepciones ACTIVAS cuya ventana de fechas se solapa con {@code [desde, hasta)},
		 * incluyendo las de esa membership puntual Y las de alcance SEDE ENTERA
		 * ({@code membershipId IS NULL}).
		 *
		 * <p><b>Las dos poblaciones son obligatorias, no un detalle de implementacion.</b> Una
		 * excepcion de sede sin acotar significa alcance, no un dato faltante (mismo criterio que
		 * {@code consultorio_id} nulo en {@code membership} y en {@code colaborador_invitacion}).
		 * Si esta consulta devolviera solo las filas del profesional, un cierre de sede completo
		 * dejaria de aplicarsele sin que ningun test unitario de un solo profesional lo detecte.
		 */
		List<DisponibilidadExcepcion> findQueCubren(
				Long organizationId, Long consultorioId, Long membershipId,
				LocalDate desde, LocalDate hasta);

		/**
		 * Excepciones ACTIVAS de alcance SEDE ENTERA ({@code membershipId IS NULL}) cuya ventana
		 * de fechas se solapa con {@code [desde, hasta)}. Ninguna de un profesional puntual.
		 *
		 * <p><b>Existe porque {@link #findQueCubren} no puede expresar este caso.</b> Aquel toma
		 * un {@code Long membershipId} no nulable y siempre agrega las de sede a las del
		 * profesional; no hay forma de pedirle "solo las de la sede". El endpoint
		 * {@code GET /consultorios/{cid}/excepciones} lleva {@code membershipId} OPCIONAL, y sin
		 * el la pantalla de calendario de la sede quiere ver exactamente los cierres que afectan
		 * a todos.
		 *
		 * <p>La alternativa —pasarle a {@link #findQueCubren} un id centinela que no exista— es
		 * como se cuelan los bugs de alcance: el dia que ese id exista de verdad, el listado de
		 * la sede empieza a mostrar en silencio las excepciones de un profesional cualquiera.
		 */
		List<DisponibilidadExcepcion> findDeSedeQueCubren(
				Long organizationId, Long consultorioId, LocalDate desde, LocalDate hasta);

		/**
		 * Una excepcion por id, acotada al tenant y a la sede, activa o no. Deliberadamente NO
		 * acotada por membership: una excepcion de sede entera no tiene una unica membership
		 * duena, y el llamador ya sabe a que sede pertenece por la ruta.
		 */
		Optional<DisponibilidadExcepcion> findByIdScoped(Long id, Long organizationId, Long consultorioId);

		DisponibilidadExcepcion save(DisponibilidadExcepcion excepcion);
	}

	/**
	 * Acceso al calendario de feriados. <b>Sin {@code organizationId}, y no es un olvido:</b> la
	 * tabla es global por ADR-0022. Si alguien agrega el parametro, entendio mal el alcance —
	 * la decision por tenant vive en {@code CalendarioSede}, no aca.
	 */
	public interface FeriadoRepositoryPort {

		List<Feriado> findByPaisAndFechaBetween(String pais, LocalDate desde, LocalDate hasta);
	}

	/**
	 * Politica de calendario de una sede, y la fila que serializa los writes de disponibilidad de
	 * esa sede (V23, diseno §2.2 y §5).
	 */
	public interface CalendarioSedeRepositoryPort {

		/** Lectura sin lock: para mostrar la politica o decidir si hace falta crearla a demanda. */
		Optional<CalendarioSede> findByScope(Long organizationId, Long consultorioId);

		/**
		 * La misma fila, tomando lock exclusivo. Ver el javadoc de la clase y el de
		 * {@code CalendarioSedeRepository#lockByScope} para el motivo completo.
		 */
		Optional<CalendarioSede> lockByScope(Long organizationId, Long consultorioId);

		/**
		 * Crea la fila de la sede si todavia no existe, <b>sin lanzar nunca</b> y sin leer antes.
		 *
		 * <p>Es lo que hace posible tomar {@link #lockByScope} sobre una fila que siempre existe.
		 * La crea {@code CalendarioSedeIniciador} en su propia transaccion; el motivo por el que
		 * no puede crearse dentro de la transaccion que la va a bloquear —deadlock, no violacion
		 * de unique— esta en el javadoc de esa clase.
		 */
		void crearSiFalta(long organizationId, long consultorioId);

		CalendarioSede save(CalendarioSede calendario);
	}
}
