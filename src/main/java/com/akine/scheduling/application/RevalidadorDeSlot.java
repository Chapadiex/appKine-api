package com.akine.scheduling.application;

import com.akine.offering.spi.HabilitacionSnapshot;
import com.akine.offering.spi.OfertaDirectory;
import com.akine.offering.spi.OfertaSnapshot;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.resource.spi.DisponibilidadDirectory;
import com.akine.resource.spi.EspacioDirectory;
import com.akine.resource.spi.EspacioSnapshot;
import com.akine.scheduling.domain.Turno;
import com.akine.scheduling.domain.exception.EspacioNoElegibleException;
import com.akine.scheduling.domain.exception.RecursoOcupadoException;
import com.akine.scheduling.domain.exception.SlotCompletoException;
import com.akine.scheduling.domain.exception.SlotNoDisponibleException;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.TurnoRepositoryPort;
import com.akine.platform.spi.problem.ConflictoDeAgenda;
import com.akine.scheduling.spi.OcupacionDePersona;
import com.akine.scheduling.spi.OcupacionExternaProbe;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Revalida que un intervalo se pueda ocupar, y elige el espacio. <b>Lo que lee turnos se ejecuta
 * siempre bajo el lock de {@code agenda_sede}</b>, dentro de la transaccion que escribe
 * ({@link #revalidarBajoLock}); lo que no los lee puede ir antes ({@link #prevalidar}).
 *
 * <h2>Por que existe como clase aparte</h2>
 *
 * <p>Reservar (05.02) y reprogramar (05.03) son la misma escritura de agenda: las dos ocupan un
 * intervalo que hasta ese momento estaba libre, y las dos compiten contra cualquier otra reserva de
 * la sede. Si cada una tuviera su copia de estos controles, la segunda copia envejeceria —una regla
 * nueva de habilitacion se aplicaria al reservar y no al mover— y el sintoma seria una agenda con
 * dos turnos encima.
 *
 * <h2>Lo que revalida, y por que no alcanza con lo que mando el cliente</h2>
 *
 * <p>Entre que la pantalla dibujo la agenda y el usuario confirmo pudo cambiar todo: el horario del
 * profesional, un feriado, la vigencia de la oferta o de la habilitacion. El motor de 05.01 no
 * persiste slots, asi que la unica forma de saber que el hueco sigue existiendo es recalcularlo
 * DENTRO de esta transaccion. RN-M12-004.
 */
@Component
class RevalidadorDeSlot {

	private final TurnoRepositoryPort turnos;
	private final OfertaDirectory ofertas;
	private final DisponibilidadDirectory disponibilidad;
	private final EspacioDirectory espacios;
	private final OcupacionExternaProbe ocupacionExterna;
	private final AgendaDelProfesional agendaDelProfesional;
	private final OcupacionDePersona ocupacion;

	RevalidadorDeSlot(
			TurnoRepositoryPort turnos,
			OfertaDirectory ofertas,
			DisponibilidadDirectory disponibilidad,
			EspacioDirectory espacios,
			OcupacionExternaProbe ocupacionExterna,
			AgendaDelProfesional agendaDelProfesional) {
		this(turnos, ofertas, disponibilidad, espacios, ocupacionExterna, agendaDelProfesional,
				new OcupacionDePersonaService(turnos, ocupacionExterna, agendaDelProfesional, disponibilidad, null));
	}

	@Autowired
	RevalidadorDeSlot(
			TurnoRepositoryPort turnos,
			OfertaDirectory ofertas,
			DisponibilidadDirectory disponibilidad,
			EspacioDirectory espacios,
			OcupacionExternaProbe ocupacionExterna,
			AgendaDelProfesional agendaDelProfesional,
			OcupacionDePersona ocupacion) {

		this.turnos = turnos;
		this.ofertas = ofertas;
		this.disponibilidad = disponibilidad;
		this.espacios = espacios;
		this.ocupacionExterna = ocupacionExterna;
		this.agendaDelProfesional = agendaDelProfesional;
		this.ocupacion = ocupacion;
	}

	/**
	 * Lo que hay que saber para decidir si un intervalo se puede ocupar.
	 *
	 * @param turnoExcluidoId turno que NO cuenta como ocupacion. Es el que se esta moviendo: sigue
	 *                        vivo en su intervalo viejo mientras se lo revalida, y sin excluirlo un
	 *                        turno que se corre media hora chocaria contra si mismo
	 * @param espacioElegidoId box elegido a mano, o {@code null} para que lo asigne el sistema
	 * @param sobreturno      {@code true} si se autorizo ocupar un horario tomado. Saltea el cupo y
	 *                        el solapamiento con turnos <b>de esta sede</b> y <b>nada mas</b>: el
	 *                        profesional sigue teniendo que estar habilitado y atender a esa hora, y
	 *                        un turno suyo en otra sede o una clase lo siguen rechazando
	 */
	record Pedido(
			long organizationId,
			long consultorioId,
			ConsultorioSnapshot sede,
			OfertaSnapshot oferta,
			Instant inicio,
			Instant fin,
			Long profesionalId,
			Long turnoExcluidoId,
			Long espacioElegidoId,
			boolean sobreturno) {

		Pedido(long organizationId, long consultorioId, ConsultorioSnapshot sede, OfertaSnapshot oferta,
				Instant inicio, Instant fin, Long profesionalId, Long turnoExcluidoId) {
			this(organizationId, consultorioId, sede, oferta, inicio, fin, profesionalId, turnoExcluidoId,
					null, false);
		}
	}

	/**
	 * Toma de una vez, en orden de cuenta, el lock de todas las personas que una operacion de serie
	 * va a revalidar (V129). Va despues del lock de la sede y antes de la primera revalidacion: con
	 * varias personas, tomarlas en el orden de las ocurrencias podria cruzarse con otra serie.
	 */
	void bloquearProfesionales(long organizationId, java.util.Collection<Long> profesionales) {
		agendaDelProfesional.bloquearEnOrden(organizationId, profesionales);
	}

	/** Los recursos que quedaron clavados para ese intervalo. */
	record Asignacion(Long profesionalId, Long espacioId) {
	}

	/**
	 * Revalida el pedido y devuelve los recursos asignados.
	 *
	 * @throws SlotNoDisponibleException el hueco dejo de existir
	 * @throws SlotCompletoException     no queda cupo
	 * @throws RecursoOcupadoException   el profesional o todos los espacios estan tomados
	 */
	Asignacion revalidar(Pedido pedido) {
		return revalidarBajoLock(pedido, prevalidar(pedido));
	}

	/**
	 * Las fechas de {@code [desde, hasta)} que la sede cierra por feriado para el profesional
	 * (U-22): las que la disponibilidad efectiva de M05 deja vacias con {@code razonVacio = FERIADO}.
	 * Un feriado que una apertura reabre no aparece, porque ese dia tiene franjas.
	 *
	 * <p>Vacio para una oferta sin profesional o sin profesional indicado: la reserva de esa oferta
	 * no pasa por la disponibilidad —controla solo el horario general de la sede, que no conoce
	 * feriados—, asi que un feriado no le impide nada y no hay nada que omitir. Una sola consulta
	 * para toda la ventana, no una por dia.
	 *
	 * @param hasta fecha local <b>EXCLUSIVA</b>
	 */
	Set<LocalDate> feriadosQueCierran(
			long organizationId, ConsultorioSnapshot sede, OfertaSnapshot oferta, Long profesionalId,
			LocalDate desde, LocalDate hasta) {

		if (!oferta.requiereProfesional() || profesionalId == null) {
			return Set.of();
		}
		return disponibilidad.efectiva(organizationId, sede, profesionalId, desde, hasta).stream()
				.filter(dia -> FERIADO.equals(dia.razonVacio()))
				.map(DisponibilidadDirectory.DiaDisponible::fecha)
				.collect(Collectors.toUnmodifiableSet());
	}

	/** El {@code razonVacio} con que M05 marca un dia que la sede cierra por feriado. */
	private static final String FERIADO = "FERIADO";

	/** Lo que {@link #prevalidar} resolvio sin mirar turnos. */
	record Previa(Long profesionalId) {
	}

	/**
	 * La mitad de la revalidacion que <b>no lee turnos</b>: que el profesional siga habilitado para
	 * la oferta y atienda en ese horario, o que la sede atienda si la oferta no lleva profesional.
	 *
	 * <p>Puede correr <b>antes</b> del lock de {@code agenda_sede}, y la reserva la corre ahi
	 * (docs/carga.md §10). Ese lock serializa a quienes ocupan la agenda —turnos y clases—, no a
	 * quienes cambian disponibilidad, habilitaciones o el horario de la sede: esas escrituras toman
	 * sus propios locks y nunca este. Bajo el lock o antes, en {@code READ_COMMITTED} estas lecturas
	 * ven lo ultimo commiteado al momento de leer y ninguna queda protegida hasta el commit de la
	 * reserva: la ventana es la misma clase de ventana, unos milisegundos mas larga. Lo que el lock
	 * si protege —cupo y solapamiento— sigue en {@link #revalidarBajoLock}.
	 *
	 * @throws SlotNoDisponibleException el hueco dejo de existir
	 */
	Previa prevalidar(Pedido pedido) {
		return new Previa(resolverProfesional(pedido));
	}

	/**
	 * La mitad que <b>tiene que correr bajo el lock</b>: cupo, solapamiento con turnos y clases, y
	 * eleccion del espacio.
	 *
	 * @throws SlotCompletoException   no queda cupo
	 * @throws RecursoOcupadoException el profesional o todos los espacios estan tomados
	 */
	Asignacion revalidarBajoLock(Pedido pedido, Previa previa) {
		return evaluar(pedido, previa, true);
	}

	/**
	 * Los mismos controles, <b>sin lock y sin efectos</b>: lo que la pantalla pregunta antes de
	 * confirmar (TURNO - Tecnico §85). Es informativo, igual que la agenda: dos consultas concurrentes
	 * ven el mismo hueco libre, y quien decide es la reserva.
	 *
	 * @throws SlotNoDisponibleException el hueco no existe
	 * @throws SlotCompletoException     no queda cupo
	 * @throws RecursoOcupadoException   el profesional o todos los espacios estan tomados
	 */
	Asignacion evaluarSinLock(Pedido pedido) {
		return evaluar(pedido, prevalidar(pedido), false);
	}

	/** Los espacios que se podrian usar para ese pedido, para que la pantalla ofrezca "Cambiar". */
	List<EspacioSnapshot> espaciosLibres(Pedido pedido) {
		if (!pedido.oferta().requiereEspacio()) {
			return List.of();
		}
		List<HabilitacionSnapshot> habilitados = ofertas.espaciosHabilitados(
				pedido.organizationId(), pedido.consultorioId(), pedido.oferta().id());
		List<EspacioSnapshot> candidatos = habilitados.isEmpty()
				? espacios.enServicio(
						pedido.organizationId(), pedido.consultorioId(), pedido.inicio(), pedido.fin())
				: habilitados.stream()
						.filter(habilitacion -> habilitacion.vigenteEn(pedido.inicio()))
						.map(habilitacion -> espacios.find(
								pedido.organizationId(), habilitacion.recursoId(), pedido.inicio()))
						.flatMap(Optional::stream)
						.toList();
		return candidatos.stream()
				.filter(EspacioSnapshot::active)
				.filter(EspacioSnapshot::enServicio)
				.filter(espacio -> espacio.consultorioId() == pedido.consultorioId())
				.filter(espacio -> primerTurnoQueOcupa(pedido, espacio.id()).isEmpty())
				.filter(espacio -> espacioLibreDeEventosExternos(pedido, espacio.id()))
				.toList();
	}

	private Asignacion evaluar(Pedido pedido, Previa previa, boolean bajoLock) {
		Long profesionalId = previa.profesionalId();

		if (!pedido.sobreturno()) {
			exigirLugarDelProfesional(pedido, profesionalId, bajoLock);
		} else {
			exigirSobreturnoSinOtrasObligaciones(pedido, profesionalId, bajoLock);
		}

		Long espacioId = null;
		if (pedido.oferta().requiereEspacio()) {
			try {
				espacioId = elegirEspacio(pedido, profesionalId, bajoLock);
			} catch (RecursoOcupadoException sinBoxLibre) {
				// Un sobreturno ocupa un horario tomado: que no quede box libre es parte del caso, y
				// el turno queda sin box asignado en vez de rechazarse.
				if (!pedido.sobreturno()) {
					throw sinBoxLibre;
				}
			}
		}
		return new Asignacion(profesionalId, espacioId);
	}

	private void exigirLugarDelProfesional(Pedido pedido, Long profesionalId, boolean bajoLock) {
		// Cupo primero: es una sola consulta y descarta el caso mas frecuente —el slot grupal
		// lleno— sin recorrer habilitaciones ni espacios.
		long ocupados = contarOcupacion(pedido, profesionalId);
		if (ocupados >= pedido.oferta().capacidad()) {
			throw new SlotCompletoException(pedido.oferta().capacidad());
		}

		if (profesionalId != null) {
			// Agenda de profesionales (checklist §9.2). La misma PERSONA tiene una membership por sede, y
			// el lock de agenda_sede no ve la otra sede. Bajo el lock se toma la fila de la persona (V129)
			// DESPUES de la de la sede —orden fijo, sin deadlock entre sedes—.
			tomarPersona(pedido, profesionalId, bajoLock);
			// Disponibilidad central (0.158.0): turnos y clases (principal o adicional) de la misma persona
			// en cualquier sede, preguntados a la logica unica que tambien usan las clases y las aperturas.
			// Un turno del mismo grupo (oferta grupal, mismo profesional e inicio) no es un choque.
			Optional<ConflictoDeAgenda> conflicto = ocupacion.actividadReservada(new OcupacionDePersona.Consulta(
							pedido.organizationId(), pedido.consultorioId(), profesionalId, pedido.inicio(),
							pedido.fin(), pedido.turnoExcluidoId(), null, pedido.oferta().id()))
					.stream()
					.findFirst();
			if (conflicto.isPresent()) {
				throw RecursoOcupadoException.por("profesional", conflicto.get());
			}
			// La franja se leyo antes del lock de la persona (prevalidar). Una apertura con sustitucion en
			// otra sede pudo cerrar esta franja mientras se esperaba: bajo el lock se vuelve a mirar.
			if (bajoLock) {
				exigirDentroDeFranja(pedido);
			}
		}
	}

	/**
	 * El sobreturno salva la ocupacion de TURNOS <b>de esta sede</b> (atender a dos pacientes en el mismo
	 * horario, en el mismo lugar), y nada mas. No salva una clase —la persona no puede estar a la vez dando
	 * una clase, en otra sala o en otra sede (agenda de clases y avisos, 0.156.0)— ni un turno de la misma
	 * persona en <b>otra</b> sede (D-5, QA del 05/11/2026): no puede atender en dos sedes a la vez, y hasta
	 * entonces el filtro solo miraba {@code CLASE} y el turno de la otra sede pasaba con 201. Los cierres y
	 * la franja ya los exigio {@link #prevalidar}. Toma igual el lock de la persona, para serializar con la
	 * otra sede y con la clase.
	 */
	private void exigirSobreturnoSinOtrasObligaciones(Pedido pedido, Long profesionalId, boolean bajoLock) {
		if (profesionalId == null) {
			return;
		}
		tomarPersona(pedido, profesionalId, bajoLock);
		Optional<ConflictoDeAgenda> conflicto = ocupacion.actividadReservada(new OcupacionDePersona.Consulta(
						pedido.organizationId(), pedido.consultorioId(), profesionalId, pedido.inicio(),
						pedido.fin(), pedido.turnoExcluidoId(), null, pedido.oferta().id()))
				.stream()
				.filter(RevalidadorDeSlot::noLoSalvaElSobreturno)
				.findFirst();
		if (conflicto.isPresent()) {
			throw RecursoOcupadoException.por("profesional", conflicto.get());
		}
	}

	/** Lo unico que un sobreturno puede pisar es un turno de la misma sede. */
	static boolean noLoSalvaElSobreturno(ConflictoDeAgenda conflicto) {
		return !(conflicto.tipo() == ConflictoDeAgenda.Tipo.TURNO && conflicto.mismaSede());
	}

	private void tomarPersona(Pedido pedido, long profesionalId, boolean bajoLock) {
		if (bajoLock) {
			agendaDelProfesional.bloquear(pedido.organizationId(), profesionalId);
		}
	}

	/**
	 * Resuelve y revalida el profesional del turno.
	 *
	 * <p>Tres controles, y ninguno lo puede hacer el cliente: que este habilitado para la oferta y
	 * vigente ese dia (02.07), y que el intervalo pedido caiga DENTRO de una franja de su
	 * disponibilidad efectiva. El tercero es el que atrapa el caso de RN-M12-004: la pantalla
	 * mostro el slot hace cinco minutos y desde entonces alguien cerro el dia.
	 */
	private Long resolverProfesional(Pedido pedido) {
		if (!pedido.oferta().requiereProfesional()) {
			// A-8b (DP-19): una oferta sin profesional no pasa por la disponibilidad efectiva —no
			// hay de quien calcularla—, asi que el horario general de la sede se controla aca. Sin
			// esto seria la unica puerta para reservar fuera del horario de la sede.
			if (disponibilidad.fueraDelHorarioDeSede(
					pedido.organizationId(), pedido.sede(), pedido.inicio(), pedido.fin())) {
				throw new SlotNoDisponibleException("la sede no atiende en ese horario");
			}
			return null;
		}
		if (pedido.profesionalId() == null) {
			throw new SlotNoDisponibleException("la oferta exige profesional y no se indico ninguno");
		}

		// LISTA VACIA SIGNIFICA TODOS, NO NINGUNO. Es la regla de V28 y la decision con mas
		// consecuencias de 02.07: una oferta recien creada no tiene filas de habilitacion, y si
		// eso significara "nadie puede prestarla", toda reserva contra ella se rechazaria.
		//
		// Se pierde con facilidad al refactorizar —de hecho se perdio al extraer este revalidador
		// de TurnoService— porque un `anyMatch` sobre una lista vacia da false y parece correcto.
		// Lo destapo el QA manual contra el stack real, no un test.
		List<HabilitacionSnapshot> habilitaciones = ofertas.profesionalesHabilitados(
				pedido.organizationId(), pedido.consultorioId(), pedido.oferta().id());
		boolean habilitado = habilitaciones.isEmpty()
				|| habilitaciones.stream()
						.filter(habilitacion -> habilitacion.recursoId() == pedido.profesionalId())
						.anyMatch(habilitacion -> habilitacion.vigenteEn(pedido.inicio()));
		if (!habilitado) {
			throw new SlotNoDisponibleException(
					"el profesional ya no esta habilitado para esta oferta en esa fecha");
		}

		exigirDentroDeFranja(pedido);
		return pedido.profesionalId();
	}

	/**
	 * Que {@code [inicio, fin)} caiga DENTRO de una franja de la disponibilidad efectiva del profesional
	 * en la sede. Si no cae y lo que la recorta es un cierre —un bloqueo o una ausencia suyos, o el
	 * cierre de la sede—, el rechazo lo nombra con su franja (disponibilidad central, 0.158.0).
	 */
	private void exigirDentroDeFranja(Pedido pedido) {
		LocalDate fecha = pedido.inicio()
				.atZone(ZoneId.of(pedido.sede().timezone())).toLocalDate();
		boolean dentroDeFranja = disponibilidad
				.efectiva(pedido.organizationId(), pedido.sede(), pedido.profesionalId(),
						fecha, fecha.plusDays(1))
				.stream()
				.flatMap(dia -> dia.franjas().stream())
				// Contiene, no se cruza: media consulta fuera del horario no es un turno valido.
				.anyMatch(franja -> !franja.desde().isAfter(pedido.inicio())
						&& !franja.hasta().isBefore(pedido.fin()));
		if (!dentroDeFranja) {
			Optional<ConflictoDeAgenda> cierre = ocupacion.cierres(OcupacionDePersona.Consulta.de(
					pedido.organizationId(), pedido.consultorioId(), pedido.profesionalId(), pedido.inicio(),
					pedido.fin())).stream().findFirst();
			throw cierre.map(SlotNoDisponibleException::por)
					.orElseGet(() -> new SlotNoDisponibleException("el profesional ya no atiende en ese horario"));
		}
	}

	/**
	 * Cuantos turnos vivos ocupan el slot, sin contar el que se esta moviendo.
	 *
	 * <p>Con profesional, el cupo es el <b>suyo</b>: dos profesionales que prestan la misma oferta a la
	 * misma hora son dos lugares aunque la capacidad sea uno. Antes se contaba por oferta e inicio, y
	 * el segundo profesional recibia slot-completo con la agenda libre.
	 */
	private long contarOcupacion(Pedido pedido, Long profesionalId) {
		long ocupados = profesionalId == null
				? turnos.contarVivosEnSlot(pedido.organizationId(), pedido.oferta().id(), pedido.inicio())
				: turnos.contarVivosEnSlotDelProfesional(
						pedido.organizationId(), pedido.oferta().id(), profesionalId, pedido.inicio());
		if (pedido.turnoExcluidoId() == null) {
			return ocupados;
		}
		// El excluido cuenta como ocupacion solo si esta EN este slot: un turno que se mueve desde
		// otro horario nunca estuvo aca, y restarlo de todas formas dejaria entrar uno de mas.
		boolean estabaEnEsteSlot = turnos
				.findByIdInScope(
						pedido.organizationId(), pedido.consultorioId(), pedido.turnoExcluidoId())
				.filter(Turno::estaVivo)
				.filter(turno -> turno.getOfertaId().equals(pedido.oferta().id()))
				.filter(turno -> turno.getInicio().equals(pedido.inicio()))
				.filter(turno -> profesionalId == null
						|| profesionalId.equals(turno.getProfesionalMembershipId()))
				.isPresent();
		return estabaEnEsteSlot ? ocupados - 1 : ocupados;
	}


	/**
	 * Elige el primer espacio habilitado, en servicio y libre en ese intervalo.
	 *
	 * <p><b>El primero y no el mejor.</b> Cualquier criterio de reparto —el menos usado, el mas
	 * chico que alcance— exige leer mas estado dentro del lock que serializa toda la sede, y no
	 * hay ninguna regla de negocio que lo pida. Un criterio se agrega despues sin cambiar la
	 * estructura; la contencion no se saca.
	 *
	 * <p>El orden es el que devuelve el directorio, que es estable: la asignacion es determinista
	 * para el mismo estado de la base.
	 */
	private Long elegirEspacio(Pedido pedido, Long profesionalId, boolean bajoLock) {
		if (pedido.espacioElegidoId() != null) {
			return validarEspacioElegido(pedido, bajoLock);
		}

		// Paquete E-1. Una franja grupal ocupa UN box con todas sus personas. Sin esto, la segunda
		// inscripcion veia el box "ocupado por un turno que se cruza" —el de su propio grupo— y se
		// rechazaba con recurso-ocupado: una oferta grupal que exige espacio no admitia mas de una
		// persona. Es la misma distincion que hayConflictoRealDeProfesional hace para el
		// profesional. El cupo ya lo controlo contarOcupacion contra la capacidad de la oferta.
		Optional<Long> delGrupo = espacioDelGrupo(pedido, profesionalId)
				.filter(espacioId -> !bajoLock || sigueActivoBajoLock(pedido, espacioId));
		if (delGrupo.isPresent()) {
			// El grupo comparte inicio pero no siempre fin: si la duracion cambio con el grupo armado, quien
			// se suma puede terminar despues que el resto, y a esa hora el box puede ser de otro. Un grupo
			// ocupa un solo box, asi que no se lo reparte: se rechaza.
			if (otroOcupaElBoxDelGrupo(pedido, delGrupo.get(), profesionalId)
					|| !espacioLibreDeEventosExternos(pedido, delGrupo.get())) {
				throw new RecursoOcupadoException("espacio");
			}
			return delGrupo.get();
		}

		List<HabilitacionSnapshot> habilitados = ofertas.espaciosHabilitados(
				pedido.organizationId(), pedido.consultorioId(), pedido.oferta().id());
		if (habilitados.isEmpty()) {
			// Lista vacia significa TODOS: la oferta se puede prestar en cualquier espacio en
			// servicio de la sede. Misma regla de V28 que para los profesionales.
			return espacios.enServicio(
					pedido.organizationId(), pedido.consultorioId(), pedido.inicio(), pedido.fin())
					.stream()
					.filter(EspacioSnapshot::active)
					.filter(EspacioSnapshot::enServicio)
					.map(EspacioSnapshot::id)
					// Sin el turno que se mueve: mover un turno media hora con un solo box lo hacia chocar
					// contra si mismo. La rama de abajo, con espacios declarados, ya lo excluia.
					.filter(espacioId -> primerTurnoQueOcupa(pedido, espacioId).isEmpty())
					// AKINE-08.01: un box tomado por una clase no es un box libre. Ver
					// espacioLibreDeEventosExternos.
					.filter(espacioId -> espacioLibreDeEventosExternos(pedido, espacioId))
					// El ultimo filtro, y perezoso: toma lock compartido solo sobre el que se elige.
					.filter(espacioId -> !bajoLock || sigueActivoBajoLock(pedido, espacioId))
					.findFirst()
					.orElseThrow(() -> new RecursoOcupadoException("espacio"));
		}

		for (HabilitacionSnapshot habilitacion : habilitados) {
			if (!habilitacion.vigenteEn(pedido.inicio())) {
				continue;
			}
			Optional<EspacioSnapshot> espacio = espacios.find(
					pedido.organizationId(), habilitacion.recursoId(), pedido.inicio());
			boolean utilizable = espacio
					.filter(EspacioSnapshot::active)
					.filter(EspacioSnapshot::enServicio)
					.filter(candidato -> candidato.consultorioId() == pedido.consultorioId())
					.isPresent();
			if (!utilizable) {
				continue;
			}
			boolean libre = primerTurnoQueOcupa(pedido, habilitacion.recursoId()).isEmpty();
			if (libre && espacioLibreDeEventosExternos(pedido, habilitacion.recursoId())
					&& (!bajoLock || sigueActivoBajoLock(pedido, habilitacion.recursoId()))) {
				return habilitacion.recursoId();
			}
		}
		// Se distingue de SlotNoDisponible: el hueco existe y el profesional atiende; lo que falta
		// es un box. La pantalla puede ofrecer otro horario en vez de mandar a recargar la agenda.
		throw new RecursoOcupadoException("espacio");
	}

	/**
	 * El box elegido a mano (TURNO - Tecnico §17, "Cambiar"). Pasa por los mismos filtros que el
	 * automatico: elegirlo no habilita un box que la oferta no admite ni uno que esta ocupado.
	 */
	private Long validarEspacioElegido(Pedido pedido, boolean bajoLock) {
		long elegido = pedido.espacioElegidoId();
		List<HabilitacionSnapshot> habilitados = ofertas.espaciosHabilitados(
				pedido.organizationId(), pedido.consultorioId(), pedido.oferta().id());
		boolean habilitado = habilitados.isEmpty() || habilitados.stream()
				.filter(habilitacion -> habilitacion.recursoId() == elegido)
				.anyMatch(habilitacion -> habilitacion.vigenteEn(pedido.inicio()));
		boolean utilizable = habilitado && espacios.find(pedido.organizationId(), elegido, pedido.inicio())
				.filter(EspacioSnapshot::active)
				.filter(EspacioSnapshot::enServicio)
				.filter(espacio -> espacio.consultorioId() == pedido.consultorioId())
				.isPresent();
		if (!utilizable) {
			throw new EspacioNoElegibleException(elegido);
		}
		Optional<Turno> ocupante = primerTurnoQueOcupa(pedido, elegido);
		if (ocupante.isPresent()) {
			throw new RecursoOcupadoException("espacio", ocupante.get().getId(),
					ocupante.get().getInicio(), ocupante.get().getFin());
		}
		if (!espacioLibreDeEventosExternos(pedido, elegido)) {
			throw new RecursoOcupadoException("espacio");
		}
		if (bajoLock && !sigueActivoBajoLock(pedido, elegido)) {
			throw new EspacioNoElegibleException(elegido);
		}
		return elegido;
	}

	/** El turno que ocupa ese box en el intervalo, sin contar el que se esta moviendo. */
	private Optional<Turno> primerTurnoQueOcupa(Pedido pedido, long espacioId) {
		return turnos
				.findVivosDeEspacioQueCruzan(
						pedido.organizationId(), espacioId, pedido.inicio(), pedido.fin())
				.stream()
				.filter(otro -> !esElExcluido(otro, pedido))
				.findFirst();
	}

	/**
	 * {@code true} si el espacio sigue activo, releido con <b>lock compartido</b> sobre su fila.
	 *
	 * <p>El {@code active} del snapshot se leyo sin lock, y la baja del box toma el exclusivo de la
	 * fila antes de preguntar por turnos pendientes: sin esta relectura un turno se colaba en un box
	 * entre la sonda y el commit de la baja. Es la misma carrera que la de la sede
	 * ({@link BloqueoDeAgenda#tomarParaOcupar}) y se cierra igual. Orden de locks:
	 * {@code agenda_sede}, {@code consultorio}, {@code espacio}; la baja del box no toma ninguno de
	 * los dos primeros.
	 */
	private boolean sigueActivoBajoLock(Pedido pedido, long espacioId) {
		return !espacios.inactivoBajoLockCompartido(pedido.organizationId(), espacioId);
	}

	/**
	 * {@code true} si ningun evento de otro modulo —hoy, una clase de M28— ocupa ese box en el
	 * intervalo. AKINE-08.01.
	 *
	 * <p>Un box tomado por una clase <b>no es un box libre</b>, y sin este filtro la reserva
	 * elegiria el primero que ningun turno esta usando y lo venderia dos veces. El caso no lo
	 * puede atrapar ningun unique —dos intervalos que se cruzan no comparten un valor de columna—
	 * y lo que lo hace confiable es que esta consulta corre <b>bajo el lock de {@code agenda_sede}</b>,
	 * la misma fila que la clase se disputa al programarse.
	 *
	 * <p><b>No hay nada que excluir aca.</b> El {@code turnoExcluidoId} de una reprogramacion es un
	 * turno, y un turno nunca aparece en esta respuesta: la sonda contesta por lo que NO es turno.
	 */
	private boolean espacioLibreDeEventosExternos(Pedido pedido, long espacioId) {
		return !ocupacionExterna.espacioOcupado(
				pedido.organizationId(), espacioId, pedido.inicio(), pedido.fin());
	}

	/**
	 * El box de los otros turnos vivos de la misma franja —misma oferta, mismo inicio y <b>mismo
	 * profesional</b>—, si los hay. Dos profesionales a la misma hora son dos grupos: si no, el segundo
	 * caeria en el box del primero, encima de su turno.
	 *
	 * <p>La ventana es de un microsegundo porque {@code inicio} se guarda en {@code DATETIME(6)} y
	 * la consulta filtra {@code [desde, hasta)}: es la forma de pedir "exactamente este inicio" sin
	 * agregar otra consulta al puerto.
	 */
	private Optional<Long> espacioDelGrupo(Pedido pedido, Long profesionalId) {
		return turnos.findVivosDeLaOfertaEnVentana(pedido.organizationId(), pedido.oferta().id(),
						pedido.inicio(), pedido.inicio().plus(1, ChronoUnit.MICROS))
				.stream()
				.filter(otro -> !esElExcluido(otro, pedido))
				.filter(otro -> otro.getInicio().equals(pedido.inicio()))
				.filter(otro -> Objects.equals(otro.getProfesionalMembershipId(), profesionalId))
				.map(Turno::getEspacioId)
				.filter(Objects::nonNull)
				.findFirst();
	}

	private boolean otroOcupaElBoxDelGrupo(Pedido pedido, long espacioId, Long profesionalId) {
		return turnos
				.findVivosDeEspacioQueCruzan(
						pedido.organizationId(), espacioId, pedido.inicio(), pedido.fin())
				.stream()
				.filter(otro -> !esElExcluido(otro, pedido))
				.anyMatch(otro -> !(otro.getOfertaId().equals(pedido.oferta().id())
						&& otro.getInicio().equals(pedido.inicio())
						&& Objects.equals(otro.getProfesionalMembershipId(), profesionalId)));
	}

	private static boolean esElExcluido(Turno turno, Pedido pedido) {
		return pedido.turnoExcluidoId() != null && turno.getId().equals(pedido.turnoExcluidoId());
	}
}
