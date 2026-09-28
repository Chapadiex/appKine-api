package com.akine.encounter.application;

import com.akine.encounter.domain.LateralidadMedicion;
import com.akine.encounter.domain.PermissionCodes;
import com.akine.encounter.domain.Sesion;
import com.akine.encounter.domain.SesionMedicion;
import com.akine.encounter.domain.ValorMedido;
import com.akine.encounter.domain.exception.ConsultorioNoAccesibleException;
import com.akine.encounter.domain.exception.MedicionDefinicionInactivaException;
import com.akine.encounter.domain.exception.MedicionDefinicionNoAccesibleException;
import com.akine.encounter.domain.exception.MedicionFueraDeRangoException;
import com.akine.encounter.domain.exception.MedicionNoAccesibleException;
import com.akine.encounter.domain.exception.SesionCerradaException;
import com.akine.encounter.domain.exception.SesionNotAccessibleException;
import com.akine.encounter.domain.port.SesionMedicionRepositoryPort;
import com.akine.encounter.domain.port.SesionRepositoryPort;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioMembershipDirectory;
import com.akine.organization.spi.ConsultorioMembershipSnapshot;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.resource.spi.MedicionDefinicionSnapshot;
import com.akine.resource.spi.MedicionDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Examen fisico: registro, borrado, listado y comparacion de mediciones (M14, RF-M14-004).
 *
 * <h2>Lo que esta etapa agrega sobre 06.02</h2>
 *
 * <p>06.02 convirtio la evaluacion base en columnas de {@code sesion} porque son siete campos
 * fijos. El examen fisico no es eso: son decenas de medidas, distintas por especialidad y
 * extensibles por el centro. Una fila por medicion, contra un catalogo que vive en
 * {@code resource} y que este modulo <b>solo lee</b>, por {@code resource.spi.MedicionDirectory}.
 *
 * <h2>El snapshot es la condicion dura, y se toma aca</h2>
 *
 * <p>La unidad, el nombre, el tipo y la version de la definicion se <b>copian en la fila</b>
 * ({@code SesionMedicion} lo hace en su constructor, para que no exista ningun camino que arme la
 * fila sin congelarlos). Sin eso, un {@code UPDATE} sobre el catalogo reescribiria el significado
 * de todas las mediciones pasadas sin tocar {@code sesion_medicion}: reescritura de historia
 * clinica por la puerta de atras. Mismo snapshot que el importe de la obligacion (07.01).
 *
 * <h2>La propiedad no es un permiso</h2>
 *
 * <p>Dos profesionales de la misma sede tienen el mismo {@code sesion:register}. Lo que impide que
 * uno escriba en la atencion del otro es {@code Sesion#exigirPropiedadDe}, y por eso el rechazo es
 * <b>409 y no 403</b>: un 403 mandaria al usuario a pedir un permiso que ya tiene. Es la regla que
 * 06.01 dejo fijada.
 *
 * <h2>Por que este servicio NO toca la version de la sesion</h2>
 *
 * <p>Registrar una medicion no escribe ninguna columna de {@code sesion}, asi que no hay nada que
 * proteger con {@code OPTIMISTIC_FORCE_INCREMENT} — y forzarlo seria activamente daniño: el
 * autosave del borrador sostiene una version leida, y hacerla avanzar en cada medicion le
 * devolveria un 409 tras otro por escrituras que no tocaron nada suyo.
 *
 * <p>Es la <b>reciproca</b> de la regla de 02.07, y el limite entre las dos es el que 04.02 pago:
 * el force-increment va solo donde la escritura no toca ninguna columna del padre <b>y ademas hay
 * un invariante del padre que depende del hijo</b>. Aca no lo hay: cada medicion es independiente
 * de las demas y su unique la protege sola.
 *
 * <h2>El {@code PUT} es idempotente, y como se resuelve el choque</h2>
 *
 * <p>Se consulta la fila por {@code (sesion, definicion, lateralidad)} <b>antes</b> de insertar,
 * dentro de la misma transaccion, y el unique de V52 queda como red. <b>No</b> se atrapa el choque
 * del unique para consultar despues: tras un flush fallido la transaccion queda
 * {@code rollbackOnly} y la lectura posterior produce un 500 en vez del 200 que el contrato
 * promete — regla que este repositorio ya pago cuatro veces. Para un {@code PUT} idempotente el
 * pre-{@code SELECT} alcanza: el perdedor de una carrera escribio el mismo valor o uno posterior,
 * y reintentar converge.
 */
@Service
public class MedicionService {

	private static final Logger log = LoggerFactory.getLogger(MedicionService.class);

	private final SesionRepositoryPort sesiones;
	private final SesionMedicionRepositoryPort mediciones;
	private final MedicionDirectory definiciones;
	private final ConsultorioDirectory consultorios;
	private final ConsultorioMembershipDirectory memberships;
	private final PermissionGuard permissionGuard;

	public MedicionService(
			SesionRepositoryPort sesiones,
			SesionMedicionRepositoryPort mediciones,
			MedicionDirectory definiciones,
			ConsultorioDirectory consultorios,
			ConsultorioMembershipDirectory memberships,
			PermissionGuard permissionGuard) {

		this.sesiones = sesiones;
		this.mediciones = mediciones;
		this.definiciones = definiciones;
		this.consultorios = consultorios;
		this.memberships = memberships;
		this.permissionGuard = permissionGuard;
	}

	// =================================================================================
	// Escrituras
	// =================================================================================

	/**
	 * Registra una medicion, o actualiza la que ya estaba.
	 *
	 * <p>Idempotente por {@code (sesion, definicion, lateralidad)}: el autosave del examen la va a
	 * repetir, y repetirla <b>actualiza</b>. Una medicion bilateral son dos llamadas, una por lado
	 * — {@code BILATERAL} no existe porque los dos valores son distintos y una sola fila obligaria
	 * a promediarlos.
	 *
	 * <p><b>El rango se valida contra la version vigente EN ESTE MOMENTO</b>, y esa version queda
	 * copiada en la fila. Nunca se revalida al leer: una medicion vieja no se vuelve invalida
	 * porque el catalogo estreche el rango despues.
	 *
	 * <p>No lleva {@code expectedVersion}. El control optimista de 06.01 protege el borrador de la
	 * sesion —un documento que dos pestañas editan entero— y aca cada fila es una medida sola: dos
	 * escrituras de la misma medida son la misma medida, y el {@code PUT} converge. Exigir una
	 * version obligaria al autosave a releer cada valor antes de guardarlo.
	 *
	 * @throws SesionNotAccessibleException            la sesion no existe o es de otro tenant (404)
	 * @throws SesionCerradaException                  la atencion ya se cerro (409). Enmendar es 06.06
	 * @throws MedicionDefinicionNoAccesibleException  la definicion no existe o es de otro tenant (404)
	 * @throws MedicionDefinicionInactivaException     la definicion esta dada de baja (409)
	 * @throws MedicionFueraDeRangoException           el valor cae fuera del rango declarado (400)
	 */
	@Transactional
	@SuppressWarnings("java:S107")
	public MedicionView registrar(
			OperatingActor actor,
			long consultorioId,
			long sesionId,
			long definicionId,
			LateralidadMedicion lateralidad,
			ValorMedido valor,
			String nota) {

		long organizationId = exigirContexto(actor);
		Sesion sesion = sesionEditable(actor, organizationId, consultorioId, sesionId);
		LateralidadMedicion lado = lateralidad == null ? LateralidadMedicion.NO_APLICA : lateralidad;

		MedicionDefinicionSnapshot definicion = definiciones.find(organizationId, definicionId)
				.orElseThrow(() -> new MedicionDefinicionNoAccesibleException(definicionId));
		if (!definicion.activa()) {
			// La baja del catalogo NO cascadea: lo unico que se impide es registrar NUEVAS.
			throw new MedicionDefinicionInactivaException(definicionId);
		}
		exigirDentroDeRango(definicion, valor);

		Instant ahora = Instant.now();
		SesionMedicion guardada = mediciones
				.buscarEnSesion(organizationId, sesionId, definicionId, lado)
				.map(existente -> {
					existente.actualizar(valor, nota, ahora, actor.accountId());
					return existente;
				})
				.orElseGet(() -> new SesionMedicion(
						organizationId, sesionId, definicion, lado, valor, nota,
						ahora, actor.accountId()));

		// El valor NO se loguea: es contenido clinico de un paciente. Que medida, que lado y en
		// que sesion si, que es lo que permite correlacionar sin filtrar nada.
		log.info("Medicion registrada: sesionId={} definicionId={} lateralidad={} tipo={}",
				sesionId, definicionId, lado, definicion.tipo());

		return MedicionView.de(mediciones.save(guardada));
	}

	/**
	 * Borra una medicion cargada por error. <b>Solo sobre sesion en curso.</b>
	 *
	 * <p>El borrado es fisico y no contradice la regla maestra 10: lo que nunca se cerro no es
	 * informacion historica. Es el mismo criterio con el que 04.04 admitio borrar items de un plan
	 * en BORRADOR.
	 *
	 * <p>Sobre una sesion <b>cerrada</b> es 409 y lo decide <b>este servicio</b>, no la pantalla.
	 * Corregir una atencion cerrada es una enmienda con su actor y su motivo, y eso es 06.06: hasta
	 * entonces esto es fail-closed, porque es preferible no poder corregir a corregir sin dejar
	 * rastro.
	 *
	 * <p>Que no exista la medicion es <b>404 y no un 204 silencioso</b>: un borrado que finge haber
	 * borrado le oculta a la pantalla que estaba mirando datos viejos, y el profesional se queda
	 * creyendo que saco una medicion que en realidad sigue ahi bajo el otro lado.
	 */
	@Transactional
	public void borrar(
			OperatingActor actor,
			long consultorioId,
			long sesionId,
			long definicionId,
			LateralidadMedicion lateralidad) {

		long organizationId = exigirContexto(actor);
		sesionEditable(actor, organizationId, consultorioId, sesionId);
		LateralidadMedicion lado = lateralidad == null ? LateralidadMedicion.NO_APLICA : lateralidad;

		SesionMedicion medicion = mediciones
				.buscarEnSesion(organizationId, sesionId, definicionId, lado)
				.orElseThrow(() -> new MedicionNoAccesibleException(definicionId, lado.name()));

		mediciones.delete(medicion);
		log.info("Medicion borrada: sesionId={} definicionId={} lateralidad={}",
				sesionId, definicionId, lado);
	}

	// =================================================================================
	// Lecturas
	// =================================================================================

	/**
	 * Las mediciones de una sesion, con el informe de completitud.
	 *
	 * <p>Se lee tambien sobre una sesion <b>cerrada</b>: lo que una sesion cerrada no admite es
	 * escritura. Un 404 sobre la lectura de un examen ya asentado seria borrar historia por la
	 * puerta de atras.
	 *
	 * <p>"Completo" se informa y <b>no gatea nada</b>: ver {@link MedicionesDeSesionView}.
	 */
	@Transactional(readOnly = true)
	public MedicionesDeSesionView listar(
			OperatingActor actor, long consultorioId, long sesionId) {

		long organizationId = exigirContexto(actor);
		Sesion sesion = sesionLegible(actor, organizationId, consultorioId, sesionId);

		List<MedicionView> vistas =
				mediciones.listarDeSesion(organizationId, sesion.getId()).stream()
						.map(MedicionView::de)
						.toList();

		return MedicionesDeSesionView.de(
				vistas, definiciones.definicionesVigentes(organizationId).size());
	}

	/**
	 * Esta sesion contra la cerrada anterior (RF-M14-004, la mitad "cambio" del requisito).
	 *
	 * <p><b>Se calcula al leer y no se guarda</b> ningun delta: guardarlo seria una segunda copia
	 * de la verdad que miente el dia que alguien enmiende la sesion anterior. Es lo mismo que el
	 * timeline de 04.02 y el avance del plan de 04.04.
	 *
	 * <p>El baseline se acota al <b>mismo Caso</b> cuando la sesion tiene caso, y cae a "la
	 * anterior del paciente" solo cuando no lo tiene: con dos casos abiertos —una rodilla y un
	 * hombro, que 04.03 permite— comparar contra la sesion del otro es comparar contra nada.
	 *
	 * <p><b>Sin baseline no es un error</b>: {@code sesionAnteriorId} viene {@code null} y cada
	 * fila trae {@code anterior = null}.
	 *
	 * <p>Devuelve la <b>union</b> de las medidas de las dos sesiones. Las que se tomaron la vez
	 * pasada y todavia no hoy son precisamente las que el profesional va a volver a tomar: esa es
	 * la mitad "copiar-previo-y-ajustar" del requisito, y por eso <b>no hay ningun endpoint de
	 * copiar</b> — escribir el valor es un registro normal, con alguien detras.
	 */
	@Transactional(readOnly = true)
	public ComparacionDeMedicionesView comparar(
			OperatingActor actor, long consultorioId, long sesionId) {

		long organizationId = exigirContexto(actor);
		Sesion sesion = sesionLegible(actor, organizationId, consultorioId, sesionId);

		Long anteriorId = mediciones.idSesionAnteriorConMediciones(
						organizationId,
						sesion.getHistoriaClinicaId(),
						sesion.getCasoId(),
						sesion.getIniciadaEn())
				.orElse(null);

		List<Long> aConsultar = anteriorId == null
				? List.of(sesion.getId())
				: List.of(sesion.getId(), anteriorId);

		// Las dos sesiones en una sola consulta: el resultado se indexa igual por sesion y evita
		// un segundo viaje a la base para un dato que se pide junto.
		Map<Clave, MedicionView> deHoy = new LinkedHashMap<>();
		Map<Clave, MedicionView> deAntes = new LinkedHashMap<>();
		for (SesionMedicion fila : mediciones.listarDeSesiones(organizationId, aConsultar)) {
			MedicionView vista = MedicionView.de(fila);
			Clave clave = new Clave(fila.getDefinicionId(), fila.getLateralidad());
			if (fila.getSesionId().equals(sesion.getId())) {
				deHoy.put(clave, vista);
			} else {
				deAntes.put(clave, vista);
			}
		}

		// Union, y con las de hoy primero: es el orden en el que la pantalla las muestra, y deja
		// al final las que faltan cargar, que es donde el profesional tiene que mirar.
		List<MedicionComparadaView> medidas = new ArrayList<>();
		deHoy.forEach((clave, actualDeHoy) ->
				medidas.add(MedicionComparadaView.de(actualDeHoy, deAntes.get(clave))));
		deAntes.forEach((clave, previa) -> {
			if (!deHoy.containsKey(clave)) {
				medidas.add(MedicionComparadaView.de(null, previa));
			}
		});

		return new ComparacionDeMedicionesView(
				anteriorId, anteriorId != null && sesion.getCasoId() != null, List.copyOf(medidas));
	}

	// =================================================================================
	// Precondiciones
	// =================================================================================

	/**
	 * La sesion sobre la que se puede ESCRIBIR: viva, abierta y del profesional que opera.
	 *
	 * <p>El orden de los tres controles no es indiferente. La <b>propiedad</b> se verifica antes
	 * que el estado: si no, un profesional recibiria "la atencion ya esta cerrada" sobre una sesion
	 * ajena, que le confirma que esa sesion existe y en que estado esta.
	 */
	private Sesion sesionEditable(
			OperatingActor actor, long organizationId, long consultorioId, long sesionId) {

		Sesion sesion = sesionLegible(actor, organizationId, consultorioId, sesionId);
		sesion.exigirPropiedadDe(membershipDe(actor, organizationId, consultorioId));
		if (sesion.estaCerrada()) {
			throw new SesionCerradaException(sesion.getId());
		}
		return sesion;
	}

	/**
	 * La sesion sobre la que se puede LEER: viva y del alcance del actor, abierta o cerrada.
	 *
	 * <p>No exige propiedad: leer el examen de una atencion de otro profesional del mismo centro es
	 * lo normal —una interconsulta, una supervision— y lo que la propiedad protege es la escritura.
	 * El acceso queda cubierto por {@code sesion:register} sobre esa sede.
	 */
	private Sesion sesionLegible(
			OperatingActor actor, long organizationId, long consultorioId, long sesionId) {

		exigirSedeDelTenant(organizationId, consultorioId);
		exigirRegistro(actor, organizationId, consultorioId);

		return sesiones.findByIdInScope(organizationId, consultorioId, sesionId)
				.filter(Sesion::estaViva)
				.orElseThrow(() -> new SesionNotAccessibleException(sesionId));
	}

	/**
	 * El rango, contra la version vigente en este instante.
	 *
	 * <p>Solo aplica a los valores numericos: un rango sobre un texto o un booleano no significa
	 * nada, y la entidad del catalogo impide declararlo. La compatibilidad de tipo la verifica
	 * {@code ValorMedido} dentro de la entidad, que es donde no se puede esquivar.
	 */
	private static void exigirDentroDeRango(
			MedicionDefinicionSnapshot definicion, ValorMedido valor) {

		if (valor == null || valor.numerico() == null || !definicion.tipo().esNumerico()) {
			return;
		}
		if (!definicion.admiteValor(valor.numerico())) {
			throw new MedicionFueraDeRangoException(
					definicion.codigo(), valor.numerico(),
					definicion.minimo(), definicion.maximo());
		}
	}

	/**
	 * La membership del actor en esta sede.
	 *
	 * <p>Es lo que identifica al profesional, y no la cuenta: la misma persona puede ser
	 * profesional en un centro y administrativa en otro. Mismo criterio que {@code SesionService}.
	 */
	private long membershipDe(OperatingActor actor, long organizationId, long consultorioId) {
		return memberships.findByAccount(organizationId, actor.accountId()).stream()
				.filter(ConsultorioMembershipSnapshot::active)
				.filter(membership -> membership.cubreConsultorio(consultorioId))
				.map(ConsultorioMembershipSnapshot::membershipId)
				.findFirst()
				.orElseThrow(() -> new AccessDeniedException(
						"La cuenta no tiene un vinculo activo con la sede " + consultorioId));
	}

	// =================================================================================
	// Autorizacion
	// =================================================================================

	/** Falta de contexto es <b>403 y nunca 401</b>: un 401 deja al frontend en bucle de login. */
	private static long exigirContexto(OperatingActor actor) {
		if (actor == null || actor.contextOrganizationId() == null) {
			throw new AccessDeniedException("La atencion requiere un contexto de trabajo activo");
		}
		return actor.contextOrganizationId();
	}

	private void exigirSedeDelTenant(long organizationId, long consultorioId) {
		consultorios.find(organizationId, consultorioId)
				.orElseThrow(() -> new ConsultorioNoAccesibleException(consultorioId));
	}

	/**
	 * Sin permisos nuevos: registrar una medicion es escribir en la atencion, asi que el permiso es
	 * el de la sesion. Lo mismo la lectura, igual que {@code SesionService#ver}.
	 */
	private void exigirRegistro(OperatingActor actor, long organizationId, long consultorioId) {
		permissionGuard.requirePermission(new PermissionQuery(
				actor.accountId(),
				PermissionCodes.SESION_REGISTER,
				organizationId,
				consultorioId,
				null,
				Instant.now()));
	}

	/** Identidad de una medida dentro de una sesion: la medida y el lado. */
	private record Clave(Long definicionId, LateralidadMedicion lateralidad) {
	}
}
