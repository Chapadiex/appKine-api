package com.akine.resource.application;

import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioMembershipDirectory;
import com.akine.organization.spi.ConsultorioMembershipSnapshot;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.resource.application.DisponibilidadEfectivaView.DiaEfectivo;
import com.akine.resource.application.DisponibilidadEfectivaView.FranjaResuelta;
import com.akine.resource.domain.BloqueDisponibilidad;
import com.akine.resource.domain.CalendarioSede;
import com.akine.resource.domain.DiaCalculado;
import com.akine.resource.domain.DisponibilidadEfectivaCalculator;
import com.akine.resource.domain.DisponibilidadExcepcion;
import com.akine.resource.domain.Feriado;
import com.akine.resource.domain.FranjaEfectiva;
import com.akine.resource.domain.IntervaloLocal;
import com.akine.resource.domain.OrigenFranja;
import com.akine.resource.domain.PermissionCodes;
import com.akine.resource.domain.exception.ConsultorioNotAccessibleException;
import com.akine.resource.domain.exception.ProfesionalNotAccessibleException;
import com.akine.resource.domain.port.DisponibilidadRepositoryPorts.BloqueDisponibilidadRepositoryPort;
import com.akine.resource.domain.port.DisponibilidadRepositoryPorts.CalendarioSedeRepositoryPort;
import com.akine.resource.domain.port.DisponibilidadRepositoryPorts.DisponibilidadExcepcionRepositoryPort;
import com.akine.resource.domain.port.DisponibilidadRepositoryPorts.FeriadoRepositoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Proyeccion de la disponibilidad efectiva de un profesional en una sede (M05, diseno §4).
 *
 * <p>Orquesta {@link DisponibilidadEfectivaCalculator}, que es puro y trabaja en hora local de
 * punta a punta. Todo lo que ese calculador declara que NO hace, lo hace esta clase, y son cinco
 * cosas que conviene tener a la vista porque ninguna falla de forma visible cuando se omite.
 *
 * <h2>1. El huso, y por que este es el unico lugar del modulo que lo aplica al calculo</h2>
 *
 * <p>El calculador no conoce la zona de la sede a proposito: un hueco o un solapamiento de
 * horario de verano metido adentro de la aritmetica de intervalos produce un error imposible de
 * aislar en un test. La conversion se hace aca, al final, con {@link #aInstante}.
 *
 * <h2>2. {@code FIN_DE_DIA} no es la medianoche</h2>
 *
 * <p>{@link IntervaloLocal#FIN_DE_DIA} es {@code LocalTime.MAX}: 23:59:59.999999999. Convertirlo
 * literal pierde el ultimo slot del dia y <b>nadie lo nota</b>, porque la diferencia es de un
 * nanosegundo en un campo que la pantalla muestra redondeado. Se mapea al INICIO DEL DIA
 * SIGUIENTE, que es lo que un bloque "hasta la medianoche" significa y lo que la base guarda como
 * {@code '24:00:00'}.
 *
 * <h2>3. El filtro por SEDE es de esta clase</h2>
 *
 * <p>El calculador no recibe {@code consultorioId} y no puede filtrar por sede: lo dice su
 * javadoc y nombra al llamador como responsable. El caso que lo rompe: una membership de alcance
 * ORGANIZACION ({@code consultorio_id} nulo, legal desde V10) puede tener bloques en la sede A y
 * en la B bajo el mismo {@code membership_id}. Si la consulta pierde el predicado por
 * {@code consultorio_id}, los horarios de las dos sedes se MEZCLAN en la disponibilidad de un
 * solo profesional y cada franja del resultado sigue reportando un {@code reglaId} legitimo. No
 * falla nada: inventa horarios. Por eso las dos lecturas de reglas de este servicio —bloques y
 * excepciones— van por los puertos que llevan {@code consultorioId} en el {@code WHERE}, y hay un
 * test de integracion con ese caso exacto.
 *
 * <h2>4. La vigencia de la membership</h2>
 *
 * <p>El calculador no la valida. Esta clase si, y el resultado es <b>disponibilidad vacia, no un
 * error</b>: un profesional desvinculado no atiende, pero preguntarlo es legitimo y la pantalla
 * de un administrador que revisa el mes pasado tiene que poder mostrarlo. Sus bloques
 * <b>no se tocan</b>: RN-M05-003 conserva la historia y esta lectura no borra nada.
 *
 * <p><b>Se evalua DIA POR DIA, no contra la ventana entera</b> (ruling R13). Con el control
 * grueso, un vinculo que termina el 15 de marzo seguia ofreciendo turnos el 20 en una consulta
 * de todo marzo, y el motor de agenda los iba a reservar contra alguien que ya no trabaja en el
 * centro. El dia que el vinculo no cubre se vacia con {@link OrigenFranja#VINCULO} como razon:
 * sin ese valor el dia saldria como "vacio sin explicacion" y seria indistinguible de un dia en
 * el que nadie cargo horario. El control grueso sobrevive como atajo —una membership que no
 * cubre ni un dia se responde sin leer una sola regla— pero ya no es el unico.
 *
 * <h2>5. El nombre del feriado</h2>
 *
 * <p>El calculador recibe {@code feriadosQueCierran} como un {@code Set<LocalDate>} sin ids ni
 * nombres, asi que su {@code reglaVacio} es nulo cuando el dia lo vacia un feriado. Esta clase
 * tiene la lista completa y es la unica que puede decirle a la pantalla CUAL feriado cerro el
 * dia. Sin eso la pantalla dice "cerrado" y no puede decir por que, y el criterio de aceptacion
 * de la etapa es justamente que la disponibilidad efectiva explique que regla la afecta.
 *
 * <h2>Autorizacion</h2>
 *
 * <p>Es una LECTURA: {@code colaborador:read}, igual que {@code DisponibilidadService#listar} y
 * por el mismo motivo —la disponibilidad de un profesional es informacion de un colaborador—.
 * Pertenencia primero y permiso despues: una sede de otro tenant sale por 404 antes de que el
 * evaluador pueda contestar 403, porque un 403 confirmaria que esa sede existe.
 */
@Service
public class DisponibilidadEfectivaService {

	private static final Logger log = LoggerFactory.getLogger(DisponibilidadEfectivaService.class);

	/**
	 * El calculador es puro, sin estado y sin dependencias: no hace falta que sea un bean.
	 *
	 * <p>Registrarlo en el contexto de Spring solo agregaria una clase de configuracion y la
	 * posibilidad de sustituirlo por un doble, que es exactamente lo que sus propios tests no
	 * quieren: {@code DisponibilidadEfectivaCalculatorTest} lo prueba directo, y los tests de
	 * este servicio prueban la orquestacion CONTRA EL CALCULADOR REAL. Un mock del calculador
	 * dejaria sin probar la unica parte que importa.
	 */
	private static final DisponibilidadEfectivaCalculator CALCULADOR =
			new DisponibilidadEfectivaCalculator();

	private final BloqueDisponibilidadRepositoryPort bloques;
	private final DisponibilidadExcepcionRepositoryPort excepciones;
	private final FeriadoRepositoryPort feriados;
	private final CalendarioSedeRepositoryPort calendarios;
	private final ConsultorioDirectory consultorioDirectory;
	private final ConsultorioMembershipDirectory membershipDirectory;
	private final PermissionGuard permissionGuard;

	public DisponibilidadEfectivaService(
			BloqueDisponibilidadRepositoryPort bloques,
			DisponibilidadExcepcionRepositoryPort excepciones,
			FeriadoRepositoryPort feriados,
			CalendarioSedeRepositoryPort calendarios,
			ConsultorioDirectory consultorioDirectory,
			ConsultorioMembershipDirectory membershipDirectory,
			PermissionGuard permissionGuard) {

		this.bloques = bloques;
		this.excepciones = excepciones;
		this.feriados = feriados;
		this.calendarios = calendarios;
		this.consultorioDirectory = consultorioDirectory;
		this.membershipDirectory = membershipDirectory;
		this.permissionGuard = permissionGuard;
	}

	/**
	 * Resuelve la disponibilidad efectiva de un profesional en una sede, dia por dia.
	 *
	 * <p>La secuencia es la del diseno §4 y el orden de los tres primeros pasos importa:
	 *
	 * <pre>
	 *   1. sede + huso                  -&gt; 404 si es de otro tenant, ANTES del permiso
	 *   2. colaborador:read             -&gt; 403
	 *   3. ventana coherente y acotada  -&gt; 400
	 *   4. membership: si no cubre NI UN dia de la ventana, disponibilidad VACIA (no es error)
	 *   5. bloques, excepciones y feriados de la ventana, TODOS acotados a la sede
	 *   6. feriadosQueCierran = feriados de la ventana SI la sede cierra por feriado
	 *   7. calculador (hora local)
	 *   8. recorte por vigencia DIA POR DIA (ruling R13) y conversion a Instant con la zona
	 * </pre>
	 *
	 * <p><b>El paso 3 despues del 2, y no antes.</b> Validar la ventana primero seria contestar
	 * 400 a quien ni siquiera puede saber que esa sede existe, y ese 400 —que solo llega si la
	 * ruta resuelve— vuelve a ser un oraculo de existencia por la puerta de atras.
	 *
	 * @param desde primera fecha local incluida
	 * @param hasta fecha local <b>EXCLUSIVA</b>
	 * @throws ConsultorioNotAccessibleException si la sede no existe o es de otro tenant (404)
	 * @throws ProfesionalNotAccessibleException si la membership no existe en el tenant (404)
	 * @throws com.akine.resource.domain.exception.VentanaDemasiadoAmpliaException si la ventana
	 *         supera el tope consultable (400)
	 * @throws IllegalArgumentException si la ventana esta invertida o vacia (400)
	 * @throws AccessDeniedException si el request no trae contexto de tenant (403, nunca 401)
	 */
	@Transactional(readOnly = true)
	public DisponibilidadEfectivaView efectiva(
			OperatingActor actor,
			long consultorioId,
			long membershipId,
			LocalDate desde,
			LocalDate hasta) {

		long organizationId = exigirContexto(actor);
		ConsultorioSnapshot sede = exigirSedeDelTenant(organizationId, consultorioId);
		exigirLectura(actor, organizationId, consultorioId);

		VentanaConsultable.exigirValida(desde, hasta);

		ZoneId zona = ZonaSede.de(sede);

		// La politica de la sede se lee UNA sola vez: de ella salen el pais con el que se buscan
		// los feriados y la decision de si cierran. Leerla dos veces —una por dato— serian dos
		// consultas que ademas podrian ver versiones distintas de la misma fila.
		CalendarioSede politica = calendarios.findByScope(organizationId, consultorioId)
				.orElseGet(() -> new CalendarioSede(organizationId, consultorioId));
		Map<LocalDate, Feriado> feriadosDeLaVentana =
				feriadosDeLaVentana(politica.getPais(), desde, hasta);

		ConsultorioMembershipSnapshot profesional = membershipDirectory.find(organizationId, membershipId)
				.orElseThrow(() -> new ProfesionalNotAccessibleException(membershipId));

		if (!vinculadaEntre(profesional, consultorioId, desde, hasta, zona)) {
			// Atajo barato, NO el control principal: el vinculo no cubre ni un dia de la ventana,
			// asi que no hay nada que calcular y no se lee una sola regla. No es un error: es la
			// respuesta correcta para quien no estaba vinculado. Y no se toca ninguna fila — sus
			// bloques siguen enteros, con su autoria (RN-M05-003).
			log.info("Disponibilidad efectiva de una membership no vigente en la ventana: "
					+ "consultorioId={} membershipId={} ventana={}..{}",
					consultorioId, membershipId, desde, hasta);
			return vacia(consultorioId, membershipId, zona, desde, hasta, feriadosDeLaVentana);
		}

		List<BloqueDisponibilidad> bloquesDeLaSede =
				bloques.findVigentesEn(organizationId, consultorioId, membershipId, desde, hasta);
		List<DisponibilidadExcepcion> excepcionesDeLaSede =
				excepciones.findQueCubren(organizationId, consultorioId, membershipId, desde, hasta);

		Set<LocalDate> feriadosQueCierran =
				politica.isCierraPorFeriado() ? feriadosDeLaVentana.keySet() : Set.of();

		Map<LocalDate, DiaCalculado> calculado = CALCULADOR.calcular(
				membershipId, desde, hasta, bloquesDeLaSede, excepcionesDeLaSede, feriadosQueCierran);

		List<DiaEfectivo> dias = new ArrayList<>(calculado.size());
		for (Map.Entry<LocalDate, DiaCalculado> dia : calculado.entrySet()) {
			LocalDate fecha = dia.getKey();
			// Ruling R13: la vigencia se evalua DIA POR DIA, despues del calculo. El calculador no
			// la conoce —no esta entre sus entradas y no puede estarlo sin cambiarle la firma—, asi
			// que sin este recorte un vinculo que vence el 15 sigue ofreciendo turnos el 20 y el
			// motor de agenda los va a reservar contra alguien que ya no trabaja en el centro.
			DiaCalculado delDia = vinculadaEse(profesional, consultorioId, fecha, zona)
					? dia.getValue()
					: DiaCalculado.vacio(OrigenFranja.VINCULO, null);
			dias.add(proyectar(fecha, delDia, zona, feriadosDeLaVentana));
		}

		return new DisponibilidadEfectivaView(membershipId, consultorioId, zona.getId(), dias);
	}

	// =================================================================================
	// Conversion a instantes
	// =================================================================================

	/**
	 * Convierte una hora local de la sede al instante UTC correspondiente.
	 *
	 * <p><b>Nunca usar {@code LocalDateTime.toInstant(ZoneOffset)} con un offset fijo.</b> Eso
	 * funciona hasta el primer cambio de horario de verano y despues devuelve instantes corridos
	 * una hora, sin fallar y sin que ninguna pantalla lo denuncie. Argentina hoy no aplica DST,
	 * pero {@code consultorio.timezone} admite cualquier huso y la sede puede no estar en
	 * Argentina.
	 *
	 * <p>Los dos casos que {@link ZonedDateTime#of} resuelve, y como:
	 * <ul>
	 *   <li><b>Hueco (adelanto):</b> la hora local NO EXISTE —el reloj salta de 02:00 a 03:00—.
	 *       Se corre hacia adelante la duracion del salto, asi que un bloque que empieza a las
	 *       02:00 ese dia empieza en realidad a las 03:00 locales.</li>
	 *   <li><b>Solapamiento (atraso):</b> la hora local ocurre DOS VECES. Se toma el
	 *       <b>primer</b> offset, que es el criterio por defecto de {@code ZonedDateTime} y el
	 *       que hace que la manana no se duplique.</li>
	 * </ul>
	 *
	 * <p>{@link IntervaloLocal#FIN_DE_DIA} se mapea al inicio del dia siguiente: ver la cabecera
	 * de la clase. No se le suma un nanosegundo —{@code LocalTime.MAX} no admite sumas— sino que
	 * se cambia de fecha, que es la unica forma correcta de expresar las 24:00.
	 */
	private static Instant aInstante(LocalDate fecha, LocalTime hora, ZoneId zona) {
		if (IntervaloLocal.FIN_DE_DIA.equals(hora)) {
			return ZonedDateTime.of(fecha.plusDays(1), LocalTime.MIDNIGHT, zona).toInstant();
		}
		return ZonedDateTime.of(fecha, hora, zona).toInstant();
	}

	private static DiaEfectivo proyectar(
			LocalDate fecha,
			DiaCalculado calculado,
			ZoneId zona,
			Map<LocalDate, Feriado> feriadosDeLaVentana) {

		List<FranjaResuelta> franjas = new ArrayList<>(calculado.franjas().size());
		for (FranjaEfectiva franja : calculado.franjas()) {
			franjas.add(new FranjaResuelta(
					aInstante(fecha, franja.intervalo().desde(), zona),
					aInstante(fecha, franja.intervalo().hasta(), zona),
					franja.origen() == null ? null : franja.origen().name(),
					franja.recortadoPor() == null ? null : franja.recortadoPor().name(),
					franja.reglaId()));
		}

		Feriado feriado = feriadosDeLaVentana.get(fecha);
		return new DiaEfectivo(
				fecha,
				feriado != null,
				feriado == null ? null : feriado.getNombre(),
				calculado.razonVacio() == null ? null : calculado.razonVacio().name(),
				calculado.reglaVacio(),
				franjas);
	}

	/**
	 * La ventana entera sin una sola franja, para la membership que no estaba vinculada.
	 *
	 * <p>Devuelve TODOS los dias y no una lista vacia, por el mismo motivo por el que el
	 * calculador nunca omite un dia: la pantalla dibuja una grilla de fechas, y una respuesta sin
	 * dias la dejaria en blanco sin poder distinguir "no atiende" de "no cargo". Los feriados
	 * viajan igual, porque siguen siendo feriados aunque nadie atienda.
	 *
	 * <p>Cada dia lleva {@link OrigenFranja#VINCULO} como razon, igual que los dias que el recorte
	 * por vigencia vacia uno por uno en el camino normal. Es el MISMO hecho —el vinculo no cubria
	 * ese dia— y tiene que explicarse igual, si no la pantalla muestra dos textos distintos segun
	 * si la ventana entera quedo afuera o solo una parte.
	 */
	private static DisponibilidadEfectivaView vacia(
			long consultorioId,
			long membershipId,
			ZoneId zona,
			LocalDate desde,
			LocalDate hasta,
			Map<LocalDate, Feriado> feriadosDeLaVentana) {

		List<DiaEfectivo> dias = new ArrayList<>();
		for (LocalDate fecha = desde; fecha.isBefore(hasta); fecha = fecha.plusDays(1)) {
			Feriado feriado = feriadosDeLaVentana.get(fecha);
			dias.add(new DiaEfectivo(
					fecha,
					feriado != null,
					feriado == null ? null : feriado.getNombre(),
					OrigenFranja.VINCULO.name(),
					null,
					List.of()));
		}
		return new DisponibilidadEfectivaView(membershipId, consultorioId, zona.getId(), dias);
	}

	// =================================================================================
	// Reglas del calendario
	// =================================================================================

	/**
	 * Los feriados del pais de la sede que caen dentro de {@code [desde, hasta)}, por fecha.
	 *
	 * <p><b>El extremo superior se resta un dia, y no es un ajuste cosmetico.</b>
	 * {@code findByPaisAndFechaBetween} es INCLUSIVA en los dos extremos —{@code BETWEEN} de SQL—
	 * mientras que toda ventana de esta etapa es {@code [desde, hasta)}. Sin el
	 * {@code minusDays(1)}, el feriado del dia {@code hasta} entraria al calculo y cerraria un dia
	 * que la consulta ni siquiera pidio.
	 *
	 * <p>Se leen TODOS los feriados de la ventana, cierre o no: el nombre se muestra igual. Lo que
	 * depende de la politica es el conjunto que se le pasa al calculador, no este mapa.
	 */
	private Map<LocalDate, Feriado> feriadosDeLaVentana(
			String pais, LocalDate desde, LocalDate hasta) {

		return feriados.findByPaisAndFechaBetween(pais, desde, hasta.minusDays(1)).stream()
				.collect(Collectors.toMap(
						Feriado::getFecha,
						feriado -> feriado,
						// V22 no tiene un unique por (pais, fecha): dos filas para el mismo dia
						// son posibles. Gana la primera, que es la que el ORDER BY del motor
						// devolvio antes; quedarse con una es suficiente porque lo unico que se
						// usa es el nombre, y un merge que lanzara convertiria un dato mal
						// cargado en un 500 de una consulta de lectura.
						(primero, segundo) -> primero,
						LinkedHashMap::new));
	}

	/**
	 * {@code true} si el vinculo habilita a ese profesional en esa sede en algun momento de
	 * {@code [desde, hasta)}.
	 *
	 * <p>Son dos preguntas y las dos hacen falta: que el vinculo CUBRA la sede —el de alcance
	 * organizacion cubre todas, el de otra sede no cubre esta— y que su vigencia se SOLAPE con el
	 * intervalo pedido.
	 *
	 * <p><b>Se usa con dos granularidades distintas y por eso esta parametrizado por fechas.</b>
	 * Con la ventana entera es el atajo barato: una membership que no cubre ni un dia se responde
	 * vacia sin leer una sola regla. Con UN dia —{@code [F, F+1)}— es el control fino que exige el
	 * ruling R13, el que impide que un vinculo vencido el 15 siga ofreciendo turnos el 20.
	 *
	 * <p><b>Por que un intervalo y no {@code validAt} sobre el arranque del dia.</b> Es el mismo
	 * predicado que {@code validAt} evalua —{@code active}, {@code habilitada} y
	 * {@code [validFrom, validUntil)}— pero medido sobre el dia entero en vez de sobre un solo
	 * instante. La diferencia aparece el dia en que alguien se incorpora: un vinculo que arranca a
	 * las 14:00 no es valido a las 00:00, y preguntar solo por el arranque del dia tiraria a la
	 * basura la tarde en la que esa persona si atiende. Del lado del vencimiento —que es el caso
	 * que R13 nombra— las dos formas dan exactamente lo mismo.
	 *
	 * <p>Los limites se convierten con la zona de la sede y no con UTC: el "primer instante" de un
	 * dia de un centro argentino son las 03:00 UTC, y mezclar un dia local con un
	 * {@code Instant} de vigencia sin pasar por el huso es como entran los errores de un dia de
	 * corrimiento.
	 */
	private static boolean vinculadaEntre(
			ConsultorioMembershipSnapshot profesional,
			long consultorioId,
			LocalDate desde,
			LocalDate hasta,
			ZoneId zona) {

		if (!profesional.cubreConsultorio(consultorioId)) {
			return false;
		}
		if (!profesional.active() || !profesional.habilitada()) {
			return false;
		}
		Instant inicio = desde.atStartOfDay(zona).toInstant();
		Instant fin = hasta.atStartOfDay(zona).toInstant();

		boolean empiezaAntesDelFin = profesional.validFrom().isBefore(fin);
		boolean terminaDespuesDelInicio =
				profesional.validUntil() == null || profesional.validUntil().isAfter(inicio);
		return empiezaAntesDelFin && terminaDespuesDelInicio;
	}

	/** El vinculo cubre ese dia local completo. Ver {@link #vinculadaEntre}. */
	private static boolean vinculadaEse(
			ConsultorioMembershipSnapshot profesional,
			long consultorioId,
			LocalDate fecha,
			ZoneId zona) {

		return vinculadaEntre(profesional, consultorioId, fecha, fecha.plusDays(1), zona);
	}

	// =================================================================================
	// Autorizacion
	// =================================================================================

	/**
	 * Falta de contexto es <b>403 y nunca 401</b>: el interceptor del frontend borra el token ante
	 * cualquier 401 y dejaria al usuario en un bucle de login del que no sale.
	 */
	private long exigirContexto(OperatingActor actor) {
		if (actor.contextOrganizationId() == null) {
			log.info("Lectura de disponibilidad efectiva sin contexto validado: accountId={}",
					actor.accountId());
			throw new AccessDeniedException("La operacion requiere un contexto de trabajo activo");
		}
		return actor.contextOrganizationId();
	}

	/**
	 * Exige {@code colaborador:read} con la SEDE como alcance.
	 *
	 * <p>Igual que {@code DisponibilidadService#listar}, <b>no</b> exige ademas que la sede de la
	 * ruta sea la del contexto: la sede viaja como alcance al evaluador, asi que un
	 * {@code CONSULTORIO_ADMIN} de otra sede recibe 403 por la formula del evaluador y una segunda
	 * regla que duplique la decision solo agregaria un lugar donde equivocarse.
	 */
	private void exigirLectura(OperatingActor actor, long organizationId, long consultorioId) {
		permissionGuard.requirePermission(new PermissionQuery(
				actor.accountId(),
				PermissionCodes.COLABORADOR_READ,
				organizationId,
				consultorioId,
				null,
				Instant.now()));
	}

	private ConsultorioSnapshot exigirSedeDelTenant(long organizationId, long consultorioId) {
		return consultorioDirectory.find(organizationId, consultorioId)
				.orElseThrow(() -> new ConsultorioNotAccessibleException(consultorioId));
	}
}
