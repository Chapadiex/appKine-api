package com.akine.encounter.infrastructure;

import com.akine.assistant.spi.AccionDelAsistente;
import com.akine.assistant.spi.ActorDelAsistente;
import com.akine.assistant.spi.PedidoDeAccion;
import com.akine.assistant.spi.PropuestaDeAccion;
import com.akine.assistant.spi.ResultadoDeAccion;
import com.akine.assistant.spi.Tarjeta;
import com.akine.assistant.spi.Tarjeta.OpcionDeTarjeta;
import com.akine.assistant.spi.Tarjeta.TarjetaDeAtencion;
import com.akine.assistant.spi.Tarjeta.TratamientoEnTarjeta;
import com.akine.assistant.spi.TipoDeAccion;
import com.akine.clinical.spi.ContextoClinicoDeAgenda;
import com.akine.clinical.spi.ContextoClinicoDeAgenda.CasoAgendable;
import com.akine.encounter.application.IdempotencyKeyConflictException;
import com.akine.encounter.application.InicioSinTurno;
import com.akine.encounter.application.OperatingActor;
import com.akine.encounter.application.SesionService;
import com.akine.encounter.application.SesionView;
import com.akine.encounter.application.TratamientoService;
import com.akine.encounter.domain.Asistencia;
import com.akine.encounter.domain.CierreDeSesion;
import com.akine.encounter.domain.PermissionCodes;
import com.akine.encounter.domain.Sesion;
import com.akine.encounter.domain.TratamientoAplicado;
import com.akine.encounter.domain.exception.CasoClinicoRequeridoException;
import com.akine.encounter.domain.exception.CasoNoAsignableException;
import com.akine.encounter.domain.exception.CierreIncompletoException;
import com.akine.encounter.domain.exception.ConsultorioNoAccesibleException;
import com.akine.encounter.domain.exception.OfertaNoAtendibleException;
import com.akine.encounter.domain.exception.PracticaNoUtilizableException;
import com.akine.encounter.domain.exception.SesionAjenaException;
import com.akine.encounter.domain.exception.SesionCerradaException;
import com.akine.encounter.domain.exception.SesionNotAccessibleException;
import com.akine.encounter.domain.exception.TurnoNoAtendibleException;
import com.akine.encounter.domain.port.SesionRepositoryPort;
import com.akine.encounter.domain.port.TratamientoRepositoryPorts.TratamientoRepositoryPort;
import com.akine.encounter.spi.OfertaSinPrecioException;
import com.akine.offering.spi.OfertaDirectory;
import com.akine.offering.spi.OfertaSnapshot;
import com.akine.offering.spi.PracticaDeOferta;
import com.akine.offering.spi.PracticasDeOfertaDirectory;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioMembershipDirectory;
import com.akine.organization.spi.ConsultorioMembershipSnapshot;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.organization.spi.ZonaDeSede;
import com.akine.platform.spi.identity.AccountIdentity;
import com.akine.platform.spi.identity.AccountIdentityDirectory;
import com.akine.platform.spi.listado.TextoDeBusqueda;
import com.akine.resource.spi.CatalogoDirectory;
import com.akine.resource.spi.CatalogoSnapshot;
import com.akine.scheduling.spi.TurnoDirectory;
import com.akine.scheduling.spi.TurnoSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * La atencion de hoy de un paciente, con sus tratamientos, dicha al asistente
 * (AKINE-asistente-acciones §6.3). Solo para quien tiene {@code sesion:register}: la sesion es del
 * profesional que habla y no se registra la atencion de otro.
 *
 * <h2>{@code ejecutar} no es atomico, y no abre transaccion</h2>
 *
 * <p>Son tres pasos, cada uno en la transaccion de su servicio: iniciar (con turno, o sin turno con
 * la clave de idempotencia de la accion), un tratamiento por practica releyendo la version de la
 * sesion entre uno y otro, y cerrar con PRESENTE. No se puede envolver: {@code SesionService.cerrar}
 * se niega a correr dentro de una transaccion (FilaLockPrevia), {@code TratamientoService.registrar}
 * avanza la version con un UPDATE que deja vieja la entidad cargada, e {@code iniciarSinTurno}
 * resuelve su carrera en una transaccion aparte.
 *
 * <p>Por eso <b>un fallo despues de haber grabado algo no es un rechazo</b>, y abrir la sesion ya es
 * grabar: queda abierta con lo que se haya cargado y el resultado es {@code CONFIRMADA} con el
 * motivo y el enlace a la pantalla de atencion. Reintentar la propuesta duplicaria los tratamientos.
 *
 * <p>Las reglas (permiso, propiedad, habilitacion, caso exigido, minimos del cierre, precio) las
 * aplican los servicios; {@code preparar} solo las anticipa para armar la tarjeta.
 */
@Component
class AtencionPorElAsistente implements AccionDelAsistente {

	private static final Logger log = LoggerFactory.getLogger(AtencionPorElAsistente.class);

	static final String MOTIVO_SIN_TURNO = "Registrada desde el asistente";
	static final String PREFIJO_DE_CLAVE = "asistente-accion-";
	static final String ENLACE_ATENCION = "/atencion/sesiones/";

	private static final String CANCELADO = "CANCELADO";
	private static final String SIN_PERFIL_DE_PACIENTE = "PacienteSinPerfilVigenteException";
	private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");

	private final SesionService sesionService;
	private final TratamientoService tratamientoService;
	private final SesionRepositoryPort sesiones;
	private final TratamientoRepositoryPort tratamientos;
	private final TurnoDirectory turnos;
	private final OfertaDirectory ofertas;
	private final PracticasDeOfertaDirectory practicasDeOferta;
	private final CatalogoDirectory catalogo;
	private final ContextoClinicoDeAgenda contextoClinico;
	private final ConsultorioDirectory consultorios;
	private final ConsultorioMembershipDirectory memberships;
	private final AccountIdentityDirectory identidades;
	private final PermissionGuard permissionGuard;

	@SuppressWarnings("java:S107")
	AtencionPorElAsistente(
			SesionService sesionService,
			TratamientoService tratamientoService,
			SesionRepositoryPort sesiones,
			TratamientoRepositoryPort tratamientos,
			TurnoDirectory turnos,
			OfertaDirectory ofertas,
			PracticasDeOfertaDirectory practicasDeOferta,
			CatalogoDirectory catalogo,
			ContextoClinicoDeAgenda contextoClinico,
			ConsultorioDirectory consultorios,
			ConsultorioMembershipDirectory memberships,
			AccountIdentityDirectory identidades,
			PermissionGuard permissionGuard) {

		this.sesionService = sesionService;
		this.tratamientoService = tratamientoService;
		this.sesiones = sesiones;
		this.tratamientos = tratamientos;
		this.turnos = turnos;
		this.ofertas = ofertas;
		this.practicasDeOferta = practicasDeOferta;
		this.catalogo = catalogo;
		this.contextoClinico = contextoClinico;
		this.consultorios = consultorios;
		this.memberships = memberships;
		this.identidades = identidades;
		this.permissionGuard = permissionGuard;
	}

	@Override
	public TipoDeAccion tipo() {
		return TipoDeAccion.ATENCION;
	}

	// =================================================================================
	// preparar — solo lee
	// =================================================================================

	@Override
	public PropuestaDeAccion preparar(ActorDelAsistente actor, PedidoDeAccion pedido) {
		if (pedido.personaId() == null) {
			return PropuestaDeAccion.sinTarjeta("Decime de quién es la atención.");
		}
		long organizationId = exigirContexto(actor);
		long consultorioId = actor.consultorioId();
		// TurnoDirectory no autoriza nada: el permiso de atender se exige aca, antes de leer.
		exigirRegistro(actor, organizationId, consultorioId);
		long propio = membershipDe(actor, organizationId, consultorioId);

		long personaId = pedido.personaId();
		String nombre = pedido.personaNombre();
		ZoneId zona = pedido.zona() == null
				? ZonaDeSede.de(consultorios, organizationId, consultorioId) : pedido.zona();
		LocalDate hoy = pedido.hoy() == null ? LocalDate.now(zona) : pedido.hoy();
		Instant ahora = Instant.now();

		Borrador borrador = new Borrador();
		Long ofertaParaPracticas = null;

		List<TurnoSnapshot> deHoy = turnosVivosDeHoy(organizationId, consultorioId, personaId, zona, hoy);
		Map<Long, Sesion> sesionPorTurno = sesionesDe(organizationId, deHoy);

		// De quien es cada turno: de quien lleva su sesion si ya empezo (pudo haber un reemplazo), y
		// si no, del profesional de la reserva. Un turno sin profesional lo atiende quien inicia.
		List<TurnoSnapshot> propios = deHoy.stream()
				.filter(turno -> {
					Long titular = titular(turno, sesionPorTurno);
					return titular == null || titular == propio;
				})
				.toList();
		List<TurnoSnapshot> atendibles = propios.stream()
				.filter(turno -> !cerrada(sesionPorTurno.get(turno.id())))
				.toList();

		if (atendibles.size() == 1) {
			TurnoSnapshot turno = atendibles.get(0);
			borrador.turnoId = turno.id();
			ofertaParaPracticas = turno.ofertaId();
			Sesion abierta = sesionPorTurno.get(turno.id());
			if (abierta != null) {
				// Iniciar es idempotente y manda sobre el caso: la sesion sigue con el que tenga.
				borrador.casoId = abierta.getCasoId();
				borrador.tratamientosPrevios = tratamientos.listarVigentes(organizationId, abierta.getId()).size();
				borrador.avisos.add("Ya hay una atención abierta con " + borrador.tratamientosPrevios
						+ " tratamientos: los nuevos se agregan.");
			} else {
				resolverCasoDelTurno(organizationId, consultorioId, personaId, nombre, turno, borrador);
			}
		} else if (atendibles.size() > 1) {
			borrador.avisos.add("Tiene más de un turno hoy con vos: elegí cuál.");
			for (TurnoSnapshot turno : atendibles) {
				borrador.turnos.add(new OpcionDeTarjeta(turno.id(), HORA.format(turno.inicio().atZone(zona))
						+ nombreDeOferta(organizationId, consultorioId, turno.ofertaId())));
			}
		} else if (!propios.isEmpty()) {
			borrador.turnoId = propios.get(0).id();
			borrador.bloqueos.add("La atención de hoy ya está cerrada.");
		} else if (!deHoy.isEmpty()) {
			// No se abre una atencion sin turno al lado de un turno vivo: dejaria dos sesiones y dos
			// deudas. El camino es el reemplazo de profesional.
			borrador.bloqueos.add(tieneTurnoHoy(organizationId, deHoy.get(0), sesionPorTurno));
		} else {
			ofertaParaPracticas = resolverOfertaSinTurno(
					organizationId, consultorioId, personaId, nombre, propio, hoy, ahora, borrador);
		}

		List<TratamientoEnTarjeta> reconocidos = reconocerTratamientos(
				organizationId, consultorioId, ofertaParaPracticas, pedido.practicas(), ahora, borrador.avisos);

		return new PropuestaDeAccion(
				"Atención de " + nombre,
				new TarjetaDeAtencion(personaId, nombre, borrador.turnoId, borrador.turnos, borrador.ofertaId,
						borrador.ofertas, borrador.casoId, borrador.casos, reconocidos, pedido.frase(), true,
						borrador.tratamientosPrevios),
				Map.of(PropuestaDeAccion.PERSONA, personaId),
				borrador.avisos,
				borrador.bloqueos,
				null);
	}

	/** Los turnos de hoy de la persona en la sede que siguen en pie: ni dados de baja ni cancelados. */
	private List<TurnoSnapshot> turnosVivosDeHoy(long organizationId, long consultorioId, long personaId,
			ZoneId zona, LocalDate hoy) {

		return turnos.deLaPersonaEnVentana(organizationId, consultorioId, personaId,
						hoy.atStartOfDay(zona).toInstant(), hoy.plusDays(1).atStartOfDay(zona).toInstant())
				.stream()
				.filter(turno -> turno.vivo() && !CANCELADO.equals(turno.estado()))
				.toList();
	}

	private Map<Long, Sesion> sesionesDe(long organizationId, List<TurnoSnapshot> deHoy) {
		return deHoy.isEmpty() ? Map.of()
				: sesiones.findVivasPorTurnos(organizationId, deHoy.stream().map(TurnoSnapshot::id).toList())
						.stream()
						.filter(sesion -> sesion.getTurnoId() != null)
						.collect(Collectors.toMap(Sesion::getTurnoId, sesion -> sesion, (a, b) -> a));
	}

	private String tieneTurnoHoy(long organizationId, TurnoSnapshot turno, Map<Long, Sesion> sesionPorTurno) {
		return "Tiene turno hoy con " + nombreDeProfesional(organizationId, titular(turno, sesionPorTurno)) + ".";
	}

	/** Lo que {@code preparar} va juntando para la tarjeta. */
	private static final class Borrador {
		private Long turnoId;
		private Long ofertaId;
		private Long casoId;
		private int tratamientosPrevios;
		private final List<OpcionDeTarjeta> turnos = new ArrayList<>();
		private final List<OpcionDeTarjeta> ofertas = new ArrayList<>();
		private final List<OpcionDeTarjeta> casos = new ArrayList<>();
		private final List<String> avisos = new ArrayList<>();
		private final List<String> bloqueos = new ArrayList<>();
	}

	/**
	 * El caso de una atencion con turno. Si la reserva ya lo dijo y sigue activo, es ese (lo mismo
	 * que hace {@code SesionService.iniciar}); si no, solo se pide cuando la oferta lo exige.
	 */
	private void resolverCasoDelTurno(long organizationId, long consultorioId, long personaId, String nombre,
			TurnoSnapshot turno, Borrador borrador) {

		boolean exige = ofertas.find(organizationId, consultorioId, turno.ofertaId())
				.map(OfertaSnapshot::requiereCasoClinico)
				.orElse(false);
		if (turno.casoId() == null && !exige) {
			return;
		}
		List<CasoAgendable> activos = contextoClinico.casosActivosDe(organizationId, personaId);
		if (turno.casoId() != null
				&& activos.stream().anyMatch(caso -> caso.casoId() == turno.casoId())) {
			borrador.casoId = turno.casoId();
			return;
		}
		if (exige) {
			elegirCaso(activos, nombre, true, borrador);
		}
	}

	/**
	 * La oferta de una atencion sin turno: las activas de la sede, vigentes hoy, en que el profesional
	 * esta habilitado (sin habilitaciones declaradas la oferta admite a todos, V28). Son las mismas
	 * condiciones que valida {@code iniciarSinTurno}, que es quien decide al confirmar.
	 *
	 * @return la oferta si quedo una sola, para buscar en ella las practicas dichas
	 */
	@SuppressWarnings("java:S107")
	private Long resolverOfertaSinTurno(long organizationId, long consultorioId, long personaId, String nombre,
			long propio, LocalDate hoy, Instant ahora, Borrador borrador) {

		List<OfertaSnapshot> candidatas = ofertas.activasDeLaSede(organizationId, consultorioId).stream()
				.filter(oferta -> oferta.vigenteEl(hoy))
				.filter(oferta -> habilitado(organizationId, consultorioId, oferta.id(), propio, ahora))
				.toList();
		if (candidatas.isEmpty()) {
			borrador.bloqueos.add("No tenés ofertas habilitadas para atender sin turno.");
			return null;
		}
		if (candidatas.size() == 1) {
			OfertaSnapshot unica = candidatas.get(0);
			borrador.ofertaId = unica.id();
			if (unica.requiereCasoClinico()) {
				elegirCaso(contextoClinico.casosActivosDe(organizationId, personaId), nombre, true, borrador);
			}
			return unica.id();
		}
		candidatas.forEach(oferta ->
				borrador.ofertas.add(new OpcionDeTarjeta(oferta.id(), oferta.nombreComercial())));
		// Todavia no se sabe cual va a elegir: si alguna exige caso, se deja listo. Al confirmar, el
		// caso solo se usa si la oferta elegida lo exige.
		if (candidatas.stream().anyMatch(OfertaSnapshot::requiereCasoClinico)) {
			elegirCaso(contextoClinico.casosActivosDe(organizationId, personaId), nombre, false, borrador);
		}
		return null;
	}

	/** Un caso abierto: ese. Varios: a elegir. Ninguno: bloqueo, o aviso si la oferta no esta decidida. */
	private static void elegirCaso(List<CasoAgendable> activos, String nombre, boolean ofertaDecidida,
			Borrador borrador) {

		if (activos.size() == 1) {
			borrador.casoId = activos.get(0).casoId();
		} else if (activos.size() > 1) {
			activos.forEach(caso ->
					borrador.casos.add(new OpcionDeTarjeta(caso.casoId(), "Caso N.º " + caso.numeroCaso())));
		} else if (ofertaDecidida) {
			borrador.bloqueos.add("La oferta exige un caso clínico y " + nombre + " no tiene uno abierto.");
		} else {
			borrador.avisos.add("Si la oferta que elijas exige un caso clínico, " + nombre
					+ " no tiene uno abierto.");
		}
	}

	/**
	 * Cada texto dicho contra las practicas de la oferta y, si ahi no hay ninguna, contra el catalogo:
	 * por nombre plegado contenido. Solo una coincidencia exacta en cantidad vale; con cero o varias
	 * no se elige la mas parecida, se deja para elegir a mano.
	 */
	private List<TratamientoEnTarjeta> reconocerTratamientos(long organizationId, long consultorioId,
			Long ofertaId, List<String> dichos, Instant ahora, List<String> avisos) {

		List<String> textos = dichos.stream()
				.filter(texto -> texto != null && !texto.isBlank())
				.map(String::strip)
				.toList();
		if (textos.isEmpty()) {
			return List.of();
		}
		List<CatalogoSnapshot> delCatalogo = catalogo.practicasVigentes(organizationId, ahora);
		Set<Long> idsDeLaOferta = ofertaId == null ? Set.of()
				: practicasDeOferta.practicasHabilitadas(organizationId, consultorioId, ofertaId).stream()
						.map(PracticaDeOferta::practicaId)
						.collect(Collectors.toSet());
		List<CatalogoSnapshot> deLaOferta = delCatalogo.stream()
				.filter(practica -> idsDeLaOferta.contains(practica.id()))
				.toList();

		List<TratamientoEnTarjeta> reconocidos = new ArrayList<>();
		for (String texto : textos) {
			List<CatalogoSnapshot> coincidencias = coincidencias(deLaOferta, texto);
			if (coincidencias.isEmpty()) {
				coincidencias = coincidencias(delCatalogo, texto);
			}
			if (coincidencias.size() == 1) {
				CatalogoSnapshot practica = coincidencias.get(0);
				reconocidos.add(new TratamientoEnTarjeta(texto, practica.id(), practica.name()));
			} else {
				reconocidos.add(new TratamientoEnTarjeta(texto, null, null));
				avisos.add("No reconocí «" + texto + "»: elegilo a mano.");
			}
		}
		return reconocidos;
	}

	private static List<CatalogoSnapshot> coincidencias(List<CatalogoSnapshot> practicas, String texto) {
		String buscado = TextoDeBusqueda.plegar(texto);
		return practicas.stream()
				.filter(practica -> practica.name() != null
						&& TextoDeBusqueda.plegar(practica.name()).contains(buscado))
				.toList();
	}

	// =================================================================================
	// ejecutar — tres pasos, sin transaccion
	// =================================================================================

	@Override
	public ResultadoDeAccion ejecutar(ActorDelAsistente actor, long accionId, Tarjeta confirmada) {
		if (!(confirmada instanceof TarjetaDeAtencion tarjeta)) {
			return ResultadoDeAccion.rechazada("Lo confirmado no es una atención.");
		}
		// Antes de tocar nada: lo que el cierre va a exigir y lo que falta elegir.
		if (tarjeta.cerrar() && (tarjeta.nota() == null || tarjeta.nota().isBlank())) {
			return ResultadoDeAccion.rechazada("Falta la nota de cierre.");
		}
		// Una sesion cerrada no admite agregar practicas: lo que quedo sin reconocer se perderia.
		if (tarjeta.cerrar()
				&& tarjeta.tratamientos().stream().anyMatch(tratamiento -> tratamiento.practicaId() == null)) {
			return ResultadoDeAccion.rechazada("Hay tratamientos sin reconocer: quitalos o dejá la atención "
					+ "abierta para cargarlos desde la pantalla de atención.");
		}
		if (tarjeta.turnoId() == null && tarjeta.ofertaId() == null) {
			return ResultadoDeAccion.rechazada("Elegí la oferta.");
		}
		long organizationId = exigirContexto(actor);
		long consultorioId = actor.consultorioId();
		if (tarjeta.turnoId() == null) {
			// Lo mismo que bloquea `preparar`, releido: el turno pudo reservarse despues de la propuesta,
			// y una atencion sin turno a su lado dejaria dos sesiones y dos deudas.
			ZoneId zona = ZonaDeSede.de(consultorios, organizationId, consultorioId);
			List<TurnoSnapshot> deHoy = turnosVivosDeHoy(
					organizationId, consultorioId, tarjeta.personaId(), zona, LocalDate.now(zona));
			if (!deHoy.isEmpty()) {
				return ResultadoDeAccion.rechazada(
						tieneTurnoHoy(organizationId, deHoy.get(0), sesionesDe(organizationId, deHoy)));
			}
		}
		OperatingActor operador = new OperatingActor(
				actor.accountId(), actor.platformAdmin(), actor.organizationId(), actor.consultorioId());

		// PASO 1: la sesion. Si falla, no se grabo nada.
		String clave = PREFIJO_DE_CLAVE + accionId;
		SesionView sesion;
		boolean iniciadaAca;
		try {
			if (tarjeta.turnoId() != null) {
				Optional<TurnoSnapshot> turno = turnos.find(organizationId, consultorioId, tarjeta.turnoId());
				if (turno.isEmpty()) {
					return ResultadoDeAccion.rechazada("El turno ya no existe en esta sede.");
				}
				if (turno.get().personaId() != tarjeta.personaId()) {
					return ResultadoDeAccion.rechazada("Ese turno no es de " + tarjeta.nombre() + ".");
				}
				ZoneId zona = ZonaDeSede.de(consultorios, organizationId, consultorioId);
				if (!LocalDate.ofInstant(turno.get().inicio(), zona).equals(LocalDate.now(zona))) {
					return ResultadoDeAccion.rechazada("Ese turno no es de hoy.");
				}
				iniciadaAca = sesiones.findVivaPorTurno(organizationId, tarjeta.turnoId()).isEmpty();
				sesion = sesionService.iniciar(operador, consultorioId, tarjeta.turnoId(),
						casoSiLaOfertaLoExige(organizationId, consultorioId, turno.get().ofertaId(), tarjeta.casoId()));
			} else {
				iniciadaAca = sesiones.findPorIdempotencyKey(organizationId, clave).isEmpty();
				sesion = sesionService.iniciarSinTurno(operador, consultorioId,
						new InicioSinTurno(tarjeta.personaId(), tarjeta.ofertaId(),
								casoSiLaOfertaLoExige(organizationId, consultorioId, tarjeta.ofertaId(), tarjeta.casoId()),
								MOTIVO_SIN_TURNO),
						clave);
			}
		} catch (TurnoNoAtendibleException | OfertaNoAtendibleException | CasoClinicoRequeridoException
				| CasoNoAsignableException | ConsultorioNoAccesibleException
				| IdempotencyKeyConflictException rechazo) {
			return ResultadoDeAccion.rechazada(oracion(motivo(rechazo)));
		} catch (RuntimeException otro) {
			// La excepcion es de clinical.domain, que este modulo no puede nombrar: se reconoce por su
			// nombre. Cualquier otra cosa no es un rechazo de negocio y sigue su camino.
			if (SIN_PERFIL_DE_PACIENTE.equals(otro.getClass().getSimpleName())) {
				return ResultadoDeAccion.rechazada(tarjeta.nombre() + " no tiene perfil de paciente activo.");
			}
			throw otro;
		}
		if (sesion.cerradaEn() != null) {
			return ResultadoDeAccion.rechazada("La atención de hoy ya está cerrada.");
		}

		long sesionId = sesion.id();
		String enlace = ENLACE_ATENCION + sesionId;
		List<String> salteados = tarjeta.tratamientos().stream()
				.filter(tratamiento -> tratamiento.practicaId() == null)
				.map(tratamiento -> "No cargué «" + tratamiento.texto() + "»: no tenía una práctica elegida.")
				.toList();

		// PASO 2: un tratamiento por practica, cada uno con la version de la sesion recien leida.
		int registrados = 0;
		boolean cerrada = false;
		boolean cerrando = false;
		try {
			for (TratamientoEnTarjeta tratamiento : tarjeta.tratamientos()) {
				if (tratamiento.practicaId() == null) {
					continue;
				}
				tratamientoService.registrar(operador, consultorioId, sesionId,
						new TratamientoAplicado(tratamiento.practicaId(), null, null, null, null, null, null, null,
								List.of()),
						versionActual(organizationId, consultorioId, sesionId));
				registrados++;
			}
			// PASO 3: el cierre. Si falla, la sesion queda abierta con lo cargado.
			if (tarjeta.cerrar()) {
				cerrando = true;
				sesionService.cerrar(operador, consultorioId, sesionId,
						new CierreDeSesion(Asistencia.PRESENTE, tarjeta.nota(), null, null, null, null),
						versionActual(organizationId, consultorioId, sesionId));
				cerrada = true;
			}
		} catch (AccessDeniedException sinPermiso) {
			throw sinPermiso;
		} catch (RuntimeException fallo) {
			String motivo = motivo(fallo);
			if (registrados == 0 && !cerrando) {
				if (!iniciadaAca) {
					return ResultadoDeAccion.rechazada(oracion(motivo));
				}
				// La sesion se abrio en esta llamada y existe: decir "rechazada" la esconderia.
				log.warn("Atencion del asistente abierta sin tratamientos: sesionId={} causa={}",
						sesionId, fallo.getClass().getSimpleName());
				return new ResultadoDeAccion(ResultadoDeAccion.Estado.CONFIRMADA,
						"Abrí la atención pero no pude cargar los tratamientos: " + motivo
								+ ". Quedó abierta para terminarla.",
						enlace, salteados, Map.of("sesionId", sesionId));
			}
			log.warn("Atencion del asistente a medio cargar: sesionId={} tratamientos={} cerrando={} causa={}",
					sesionId, registrados, cerrando, fallo.getClass().getSimpleName());
			return new ResultadoDeAccion(ResultadoDeAccion.Estado.CONFIRMADA,
					"Cargué la atención pero no pude completarla: " + motivo
							+ ". Quedó abierta para terminarla.",
					enlace, salteados, Map.of("sesionId", sesionId));
		}

		String cuantos = switch (registrados) {
			case 0 -> "sin tratamientos";
			case 1 -> "con 1 tratamiento";
			default -> "con " + registrados + " tratamientos";
		};
		return new ResultadoDeAccion(ResultadoDeAccion.Estado.CONFIRMADA,
				"Registré la atención de " + tarjeta.nombre() + " " + cuantos
						+ (cerrada ? " y la cerré." : ". Quedó abierta."),
				enlace, salteados, Map.of("sesionId", sesionId));
	}

	/** El caso viaja solo si la oferta lo exige: sin exigencia, la atencion va sin caso (RF-M14-002). */
	private Long casoSiLaOfertaLoExige(long organizationId, long consultorioId, long ofertaId, Long casoId) {
		if (casoId == null) {
			return null;
		}
		boolean exige = ofertas.find(organizationId, consultorioId, ofertaId)
				.map(OfertaSnapshot::requiereCasoClinico)
				.orElse(false);
		return exige ? casoId : null;
	}

	/**
	 * La version de la sesion, releida de la base. No hay transaccion ni sesion de persistencia
	 * abierta, asi que cada lectura ve el avance que dejo el tratamiento anterior.
	 */
	private long versionActual(long organizationId, long consultorioId, long sesionId) {
		return sesiones.findByIdInScope(organizationId, consultorioId, sesionId)
				.map(Sesion::getVersion)
				.orElseThrow(() -> new SesionNotAccessibleException(sesionId));
	}

	/** El motivo de un rechazo, en minuscula y sin punto: va solo o detras de "no pude completarla:". */
	private static String motivo(RuntimeException fallo) {
		if (fallo instanceof TurnoNoAtendibleException turno) {
			return "el turno no habilita la atención (" + turno.getMotivo() + ")";
		}
		if (fallo instanceof OfertaNoAtendibleException oferta) {
			return switch (oferta.getMotivo()) {
				case NO_EXISTE -> "la oferta ya no existe en esta sede";
				case NO_VIGENTE -> "la oferta no está vigente hoy";
				case PROFESIONAL_NO_HABILITADO -> "no estás habilitado para esa oferta";
			};
		}
		if (fallo instanceof CasoClinicoRequeridoException) {
			return "la oferta exige un caso clínico y no hay uno elegido";
		}
		if (fallo instanceof CasoNoAsignableException) {
			return "el caso clínico elegido no se puede usar en esta atención";
		}
		if (fallo instanceof SesionAjenaException) {
			return "la atención la lleva otro profesional";
		}
		if (fallo instanceof SesionCerradaException) {
			return "la atención ya está cerrada";
		}
		if (fallo instanceof SesionNotAccessibleException || fallo instanceof ConsultorioNoAccesibleException) {
			return "la atención ya no existe en esta sede";
		}
		if (fallo instanceof OfertaSinPrecioException) {
			return "la oferta no tiene precio particular cargado para hoy";
		}
		if (fallo instanceof CierreIncompletoException) {
			return "falta la nota de cierre";
		}
		if (fallo instanceof PracticaNoUtilizableException) {
			return "una de las prácticas ya no está vigente en el catálogo";
		}
		if (fallo instanceof IdempotencyKeyConflictException) {
			return "esta propuesta ya se confirmó con otros datos";
		}
		if (fallo instanceof OptimisticLockingFailureException) {
			return "alguien modificó la atención al mismo tiempo";
		}
		log.warn("Rechazo no previsto en la atencion del asistente", fallo);
		return "el sistema rechazó la operación";
	}

	private static String oracion(String motivo) {
		return motivo.substring(0, 1).toUpperCase(Locale.ROOT) + motivo.substring(1) + ".";
	}

	// =================================================================================
	// Quien es el profesional del actor, y lecturas de apoyo
	// =================================================================================

	private static long exigirContexto(ActorDelAsistente actor) {
		if (actor == null || actor.organizationId() == null || actor.consultorioId() == null) {
			throw new AccessDeniedException("La atencion requiere un contexto de trabajo activo");
		}
		return actor.organizationId();
	}

	private void exigirRegistro(ActorDelAsistente actor, long organizationId, long consultorioId) {
		permissionGuard.requirePermission(new PermissionQuery(
				actor.accountId(), PermissionCodes.SESION_REGISTER, organizationId, consultorioId, null,
				Instant.now()));
	}

	/** El mismo camino que {@code SesionService#membershipDe}: el vinculo activo que cubre la sede. */
	private long membershipDe(ActorDelAsistente actor, long organizationId, long consultorioId) {
		return memberships.findByAccount(organizationId, actor.accountId()).stream()
				.filter(ConsultorioMembershipSnapshot::active)
				.filter(membership -> membership.cubreConsultorio(consultorioId))
				.map(ConsultorioMembershipSnapshot::membershipId)
				.findFirst()
				.orElseThrow(() -> new AccessDeniedException(
						"La cuenta no tiene un vinculo activo con la sede " + consultorioId));
	}

	private static Long titular(TurnoSnapshot turno, Map<Long, Sesion> sesionPorTurno) {
		Sesion sesion = sesionPorTurno.get(turno.id());
		return sesion == null ? turno.profesionalMembershipId() : sesion.getProfesionalMembershipId();
	}

	private static boolean cerrada(Sesion sesion) {
		return sesion != null && sesion.estaCerrada();
	}

	/** Lista vacia de habilitaciones es "todos" (V28), igual que en {@code SesionService}. */
	private boolean habilitado(long organizationId, long consultorioId, long ofertaId, long membershipId,
			Instant ahora) {
		var habilitados = ofertas.profesionalesHabilitados(organizationId, consultorioId, ofertaId);
		return habilitados.isEmpty() || habilitados.stream()
				.anyMatch(habilitacion -> habilitacion.recursoId() == membershipId && habilitacion.vigenteEn(ahora));
	}

	private String nombreDeOferta(long organizationId, long consultorioId, long ofertaId) {
		return ofertas.find(organizationId, consultorioId, ofertaId)
				.map(oferta -> " · " + oferta.nombreComercial())
				.orElse("");
	}

	private String nombreDeProfesional(long organizationId, Long membershipId) {
		if (membershipId == null) {
			return "otro profesional";
		}
		return memberships.find(organizationId, membershipId)
				.map(ConsultorioMembershipSnapshot::accountId)
				.map(accountId -> identidades.identidadesDe(List.of(accountId)).get(accountId))
				.map(AccountIdentity::nombreCompleto)
				.filter(nombre -> !nombre.isBlank())
				.orElse("otro profesional");
	}
}
