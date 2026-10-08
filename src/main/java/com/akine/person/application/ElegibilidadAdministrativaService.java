package com.akine.person.application;

import com.akine.contracting.spi.ArancelDirectory;
import com.akine.contracting.spi.ArancelVigente;
import com.akine.contracting.spi.ResolucionDeArancel;
import com.akine.organization.spi.PermissionGuard;
import com.akine.person.domain.Autorizacion;
import com.akine.person.domain.CoberturaPaciente;
import com.akine.person.domain.OrdenMedica;
import com.akine.person.domain.TipoCobertura;
import com.akine.person.domain.exception.CoberturaNotAccessibleException;
import com.akine.person.domain.exception.PersonaNotAccessibleException;
import com.akine.person.domain.port.PersonRepositoryPorts.AutorizacionRepositoryPort;
import com.akine.person.domain.port.PersonRepositoryPorts.CoberturaPacienteRepositoryPort;
import com.akine.person.domain.port.PersonRepositoryPorts.OrdenMedicaRepositoryPort;
import com.akine.person.domain.port.PersonRepositoryPorts.PersonaRepositoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Confronta lo que el convenio EXIGE con lo que el paciente TIENE (M17, RF-M17-007).
 *
 * <h2>Esta clase es el motivo por el que existe la etapa</h2>
 *
 * <p>03.05 dejo {@code convenio.requiere_orden}, {@code requiere_autorizacion} y
 * {@code requiere_credencial} <b>declarados y sin que nadie los aplique</b>, y su registro de
 * cierre lo dice con todas las letras: "quien los aplique es M17". Esta clase los aplica, y es lo
 * unico del repositorio que los lee para decidir algo.
 *
 * <h2>1. Lee el convenio VIVO, no la copia congelada</h2>
 *
 * <p>{@code contracting.spi} separa las dos mitades y aca se usa la <b>primera</b>:
 *
 * <pre>
 *   resolver(...)   LECTURA VIVA, para DECIDIR. Es lo que esta clase llama.
 *   congelar(...)   COPIA, para GUARDAR. Lo llama AutorizacionService, y solo en el alta.
 * </pre>
 *
 * <p>Confundirlas seria un error en las dos direcciones. Decidir con la copia congelada de una
 * autorizacion vieja aplicaria reglas derogadas a una atencion de hoy; guardar la lectura viva
 * reescribiria el pasado cada vez que el convenio cambie. Esta consulta responde <b>que se puede
 * hacer HOY</b>, asi que usa la regla de hoy.
 *
 * <h2>2. No persiste nada y NO CONSUME nada</h2>
 *
 * <p>RN-M17-001: autorizado y consumido son conceptos distintos. Preguntar si se puede atender no
 * descuenta una sesion. El consumo es RF-M17-004 y llega con la integracion clinica; la etapa lo
 * declara explicitamente fuera de alcance. Consecuencia buscada: esta consulta es idempotente y se
 * puede llamar tantas veces como la pantalla quiera.
 *
 * <h2>3. La lista vacia es el caso NORMAL (RN-M17-005, RN-M17-006)</h2>
 *
 * <p>Los requisitos se aplican solo cuando el convenio los exige, y una actividad no cubierta no
 * debe pedir orden ni autorizacion artificialmente. Tres situaciones salen <b>elegibles con cero
 * requisitos</b>, y las tres son frecuentes:
 *
 * <ul>
 *   <li>La cobertura es PARTICULAR. No hay financiador que exija nada.
 *   <li>No hay convenio vigente de esa sede con ese plan. El paciente se atiende como particular,
 *       y 03.05 ya establecio que ese es el desenlace mas frecuente de la resolucion.
 *   <li>Hay convenio pero la practica no tiene arancel cargado. Es un hueco de configuracion del
 *       centro, no un motivo para negarle la atencion al paciente.
 * </ul>
 *
 * <p>Y una cuarta que no es un caso aparte: un convenio que no exige nada devuelve tambien la
 * lista vacia, porque las tres banderas estan en {@code false}. Es DP-08 aplicada — ningun ejemplo
 * historico se vuelve obligatorio global sin una regla confirmada.
 *
 * <h2>4. Un requisito faltante NO es un error</h2>
 *
 * <p>Responde 200 con {@code elegible = false} y el detalle de que falta. Un 409 obligaria a la
 * pantalla a tratar el caso mas frecuente —al paciente le falta la orden— como una excepcion. Es
 * la misma leccion que {@code MotivoSinSlots} en 05.01 y que la resolucion de arancel en 03.05.
 *
 * <h2>5. Lo que esta clase NO afirma</h2>
 *
 * <p>RN-M08-004: nada de lo que devuelve dice que la prestacion sea facturable a ese financiador.
 * Dice si la documentacion administrativa que el convenio exige esta presente, que es otra cosa. Y
 * el tope mensual del convenio viaja como dato informativo <b>sin veredicto</b>: verificarlo exige
 * contar sesiones ya atendidas, o sea el consumo que esta etapa no cablea.
 */
@Service
public class ElegibilidadAdministrativaService {

	private static final Logger log =
			LoggerFactory.getLogger(ElegibilidadAdministrativaService.class);

	private final CoberturaPacienteRepositoryPort coberturas;
	private final OrdenMedicaRepositoryPort ordenes;
	private final AutorizacionRepositoryPort autorizaciones;
	private final PersonaRepositoryPort personas;
	private final ArancelDirectory aranceles;
	private final PermissionGuard permissionGuard;

	public ElegibilidadAdministrativaService(
			CoberturaPacienteRepositoryPort coberturas,
			OrdenMedicaRepositoryPort ordenes,
			AutorizacionRepositoryPort autorizaciones,
			PersonaRepositoryPort personas,
			ArancelDirectory aranceles,
			PermissionGuard permissionGuard) {

		this.coberturas = coberturas;
		this.ordenes = ordenes;
		this.autorizaciones = autorizaciones;
		this.personas = personas;
		this.aranceles = aranceles;
		this.permissionGuard = permissionGuard;
	}

	/**
	 * Que le falta al paciente para que le atiendan esa practica con esa cobertura, ese dia.
	 *
	 * <p>Se autoriza con {@code paciente:read} (DP-22) y no con {@code paciente:manage}: quien mas
	 * necesita esta respuesta es el profesional que esta por atender, y exigirle el permiso de
	 * gestion lo dejaria afuera. Es la "lectura justificada del profesional" que la etapa pide.
	 *
	 * <p>El permiso se evalua sobre la sede del contexto porque el <b>convenio es de la sede</b>
	 * (RN-M16-001): la misma cobertura del mismo paciente puede exigir cosas distintas en dos sedes
	 * de la misma organizacion, y eso no es una anomalia sino el caso normal de una cadena.
	 */
	@Transactional(readOnly = true)
	public ElegibilidadAdministrativa consultar(
			OperatingActor actor,
			long personaId,
			long coberturaId,
			long practicaId,
			LocalDate fecha) {

		long organizationId = AutorizacionDePadron.exigirLecturaDelPadron(permissionGuard,
				actor, "Consultar la elegibilidad administrativa");
		if (actor.consultorioId() == null) {
			// Sin sede no hay convenio que resolver: el convenio es de la SEDE. Devolver "elegible
			// sin requisitos" seria peor que rechazar, porque afirmaria que no hace falta nada.
			throw new org.springframework.security.access.AccessDeniedException(
					"La consulta de elegibilidad requiere un consultorio activo en el contexto: "
							+ "el convenio que fija los requisitos es de la sede");
		}
		personas.findByIdAndOrganizationId(personaId, organizationId)
				.orElseThrow(() -> new PersonaNotAccessibleException(personaId));

		LocalDate dia = fecha == null ? LocalDate.now() : fecha;
		CoberturaPaciente cobertura = coberturas
				.findByIdAndOrganizationIdAndPersonaId(coberturaId, organizationId, personaId)
				.orElseThrow(() -> new CoberturaNotAccessibleException(coberturaId));

		return evaluarCobertura(
				organizationId, actor.consultorioId(), personaId, coberturaId, cobertura, practicaId, dia);
	}

	/**
	 * La misma regla que {@link #consultar}, sin actor, para otro modulo (AKINE E-4: la recepcion).
	 *
	 * <p>No autoriza nada: quien llama ya resolvio permiso y pertenencia. La persona o la cobertura
	 * que no son de la organizacion responden <b>vacio</b> en vez de una excepcion, porque es una
	 * costura entre modulos y quien llama decide que hacer con eso.
	 */
	@Transactional(readOnly = true)
	public Optional<ElegibilidadAdministrativa> evaluar(
			long organizationId, long consultorioId, long personaId, long coberturaId,
			long practicaId, LocalDate fecha) {

		if (personas.findByIdAndOrganizationId(personaId, organizationId).isEmpty()) {
			return Optional.empty();
		}
		LocalDate dia = fecha == null ? LocalDate.now() : fecha;
		return coberturas
				.findByIdAndOrganizationIdAndPersonaId(coberturaId, organizationId, personaId)
				.map(cobertura -> evaluarCobertura(
						organizationId, consultorioId, personaId, coberturaId, cobertura, practicaId, dia));
	}

	private ElegibilidadAdministrativa evaluarCobertura(
			long organizationId, long consultorioId, long personaId, long coberturaId,
			CoberturaPaciente cobertura, long practicaId, LocalDate dia) {

		// RN-M17-006: una atencion particular no pide orden ni autorizacion. No hay financiador.
		if (cobertura.getTipo() != TipoCobertura.FINANCIADA) {
			return ElegibilidadAdministrativa.sinRequisitos(
					dia, ElegibilidadAdministrativa.PARTICULAR);
		}

		// LECTURA VIVA. Ver la cabecera: decidir se hace con la regla de hoy.
		ResolucionDeArancel resolucion = aranceles.resolver(
				organizationId,
				consultorioId,
				cobertura.getFinanciadorId(),
				cobertura.getPlanId(),
				practicaId,
				dia);

		if (!resolucion.estaResuelta()) {
			// Sin convenio aplicable no hay requisito que exigir. Es el desenlace MAS frecuente
			// —el paciente se atiende como particular— y el motivo viaja con el mismo nombre que
			// publica GET /aranceles/efectivo, para que el mismo desenlace no se llame distinto
			// segun por que endpoint se lo mire.
			return ElegibilidadAdministrativa.sinRequisitos(dia, resolucion.motivo().name());
		}

		ArancelVigente convenio = resolucion.arancel();
		List<RequisitoAdministrativo> requisitos = new ArrayList<>();

		if (convenio.requiereOrden()) {
			requisitos.add(evaluarOrden(organizationId, personaId, coberturaId, dia));
		}
		if (convenio.requiereAutorizacion()) {
			requisitos.add(evaluarAutorizacion(organizationId, coberturaId, practicaId, dia));
		}
		if (convenio.requiereCredencial()) {
			requisitos.add(evaluarCredencial(cobertura, dia));
		}

		ElegibilidadAdministrativa veredicto = ElegibilidadAdministrativa.evaluada(
				dia,
				requisitos,
				convenio.convenioId(),
				convenio.convenioNombre(),
				convenio.limiteSesionesMensual());

		log.debug("Elegibilidad resuelta: personaId={} coberturaId={} practicaId={} elegible={} "
						+ "requisitos={}",
				personaId, coberturaId, practicaId, veredicto.elegible(), requisitos.size());
		return veredicto;
	}

	// =================================================================================
	// Los tres requisitos
	// =================================================================================

	/**
	 * Hay una orden medica activa, vigente ese dia y que sirva para esa cobertura.
	 *
	 * <p>"Que sirva" incluye a las ordenes <b>sin cobertura declarada</b>: una prescripcion la
	 * firma un medico y no un financiador, asi que la que no esta atada a ninguna cobertura vale
	 * para todas. Ver {@code OrdenMedica#sirveParaCobertura}.
	 */
	private RequisitoAdministrativo evaluarOrden(
			long organizationId, long personaId, long coberturaId, LocalDate dia) {

		return ordenes.activasDe(organizationId, personaId).stream()
				.filter(orden -> orden.vigenteEl(dia))
				.filter(orden -> orden.sirveParaCobertura(coberturaId))
				.findFirst()
				.map(orden -> RequisitoAdministrativo.cumplidoConVigencia(
						TipoRequisito.ORDEN,
						orden.getId(),
						detalleDeOrden(orden, dia),
						null,
						orden.getVigenciaHasta(),
						orden.diasParaVencer(dia)))
				.orElseGet(() -> RequisitoAdministrativo.faltante(
						TipoRequisito.ORDEN,
						"El convenio exige orden medica y el paciente no tiene ninguna vigente el "
								+ dia + ". Cargala antes de atender."));
	}

	/**
	 * Hay una autorizacion APROBADA, vigente ese dia y con saldo.
	 *
	 * <p>Las cuatro condiciones estan en {@code Autorizacion#habilitaEl} y no se reparten entre
	 * esta clase y la entidad: si cada una supiera una parte, "habilitar" significaria dos cosas
	 * distintas segun quien pregunte.
	 *
	 * <p>Cuando hay varias candidatas se elige la de vencimiento mas proximo. No es un desempate
	 * arbitrario: es la que hay que gastar primero, porque es la que se pierde antes. Y es la que
	 * la etapa del consumo va a querer descontar.
	 */
	private RequisitoAdministrativo evaluarAutorizacion(
			long organizationId, long coberturaId, long practicaId, LocalDate dia) {

		List<Autorizacion> candidatas =
				autorizaciones.aprobadasDe(organizationId, coberturaId, practicaId).stream()
						.filter(autorizacion -> autorizacion.habilitaEl(dia))
						.toList();

		return candidatas.stream()
				.min(ElegibilidadAdministrativaService::porVencimientoMasProximo)
				.map(autorizacion -> RequisitoAdministrativo.cumplidoConVigencia(
						TipoRequisito.AUTORIZACION,
						autorizacion.getId(),
						detalleDeAutorizacion(autorizacion, dia),
						autorizacion.saldo(),
						autorizacion.getVigenciaHasta(),
						autorizacion.diasParaVencer(dia)))
				.orElseGet(() -> RequisitoAdministrativo.faltante(
						TipoRequisito.AUTORIZACION,
						"El convenio exige autorizacion previa y no hay ninguna aprobada, vigente "
								+ "el " + dia + " y con saldo para esta practica."));
	}

	/**
	 * La cobertura tiene numero de afiliado y la credencial no esta vencida.
	 *
	 * <p>Es el unico de los tres requisitos que no necesita ninguna fila nueva: el dato ya vive en
	 * {@code cobertura_paciente} desde 03.04.
	 *
	 * <p>03.04 fijo que <b>una credencial vencida no invalida la cobertura</b>, y esto no lo
	 * contradice: la cobertura sigue activa y sigue explicando el pasado. Lo que dice aca es que
	 * ese dia, para ese convenio, el requisito no esta cumplido — que es informacion para el
	 * mostrador, no una baja automatica.
	 */
	private static RequisitoAdministrativo evaluarCredencial(
			CoberturaPaciente cobertura, LocalDate dia) {

		if (cobertura.getNumeroAfiliado() == null) {
			return RequisitoAdministrativo.faltante(
					TipoRequisito.CREDENCIAL,
					"El convenio exige credencial y la cobertura no tiene numero de afiliado "
							+ "cargado.");
		}
		if (cobertura.credencialVencidaEl(dia)) {
			return RequisitoAdministrativo.faltante(
					TipoRequisito.CREDENCIAL,
					"La credencial del paciente vencio el " + cobertura.getCredencialVigenciaHasta()
							+ ". La cobertura sigue vigente; lo que falta es la credencial al dia.");
		}
		return RequisitoAdministrativo.cumplido(
				TipoRequisito.CREDENCIAL,
				cobertura.getId(),
				"Credencial vigente.",
				null);
	}

	// =================================================================================
	// Utilidades
	// =================================================================================

	/** Primero la que vence antes. Las que no vencen van al final: no hay apuro por gastarlas. */
	private static int porVencimientoMasProximo(Autorizacion una, Autorizacion otra) {
		LocalDate finUna = una.getVigenciaHasta();
		LocalDate finOtra = otra.getVigenciaHasta();
		if (finUna == null && finOtra == null) {
			return Long.compare(una.getId(), otra.getId());
		}
		if (finUna == null) {
			return 1;
		}
		if (finOtra == null) {
			return -1;
		}
		int porFecha = finUna.compareTo(finOtra);
		return porFecha != 0 ? porFecha : Long.compare(una.getId(), otra.getId());
	}

	private static String detalleDeOrden(OrdenMedica orden, LocalDate dia) {
		Long dias = orden.diasParaVencer(dia);
		String vencimiento = dias == null
				? "sin vencimiento declarado"
				: "vence en " + dias + " dia(s)";
		return "Orden de " + orden.getProfesionalEmisor() + " del " + orden.getFechaEmision()
				+ ", " + vencimiento + ".";
	}

	private static String detalleDeAutorizacion(Autorizacion autorizacion, LocalDate dia) {
		Long dias = autorizacion.diasParaVencer(dia);
		String vencimiento = dias == null
				? "sin vencimiento declarado"
				: "vence en " + dias + " dia(s)";
		Integer saldo = autorizacion.saldo();
		String restantes = saldo == null ? "sin tope declarado" : saldo + " sesion(es) restantes";
		return "Autorizacion " + autorizacion.getNumero() + ", " + restantes + ", " + vencimiento
				+ ".";
	}
}
