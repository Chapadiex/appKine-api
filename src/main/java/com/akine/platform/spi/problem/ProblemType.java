package com.akine.platform.spi.problem;

import java.net.URI;
import java.util.Arrays;
import java.util.List;

/**
 * Catalogo unico de los {@code type} de Problem Details que emite la API (RFC 7807, ADR-0005).
 *
 * <h2>Por que existe</h2>
 *
 * <p>El {@code type} es el <b>unico</b> campo de un Problem Detail pensado para que lo lea una
 * maquina: el {@code status} agrupa demasiado —los cuatro conflictos de colaborador y los seis
 * de sede son todos 409— y el {@code detail} es prosa para humanos, que cambia sin previo aviso
 * y esta en castellano. Antes de este enum los valores vivian sueltos como literales en cinco
 * archivos distintos y el frontend mantenia una copia a mano deducida de las descripciones del
 * contrato. Dos listas escritas por separado divergen; la pregunta es solo cuando.
 *
 * <h2>Que garantiza</h2>
 *
 * <ul>
 *   <li><b>Una sola definicion en el backend.</b> Ningun handler declara la URI a mano: todos
 *       la piden aca, asi que un {@code type} nuevo entra por el enum o no entra.</li>
 *   <li><b>Publicacion en el contrato.</b> {@code OpenApiConfig} publica {@link #valores()} como
 *       el schema {@code ProblemType}, un enum de strings. El cliente generado obtiene un tipo
 *       cerrado y comparar contra un valor que no existe deja de compilar.</li>
 * </ul>
 *
 * <p><b>No se toca el schema {@code ProblemDetail}</b>, que es el estandar de Spring y no es
 * nuestro para redefinir. El catalogo se publica al lado, como schema propio.
 *
 * <p>Vive en {@code platform.spi} porque los tres advices que lo usan —{@code platform},
 * {@code organization} e {@code identity}— pertenecen a modulos distintos, y {@code platform} es
 * el unico que los tres pueden alcanzar sin cerrar un ciclo.
 *
 * <p><b>Los nombres nunca se reciclan.</b> Un {@code type} publicado es contrato: cambiarle el
 * significado a uno existente rompe al cliente en silencio, mientras que agregar uno nuevo es
 * aditivo. Sacar uno es incompatible y exige version mayor.
 */
public enum ProblemType {

	// --- Transversales -----------------------------------------------------------------
	/** Cuerpo o parametros invalidos (400). */
	VALIDATION_ERROR("validation-error"),
	/** Sin sesion valida. Solo lo emiten los endpoints de autenticacion (401). */
	UNAUTHORIZED("unauthorized"),
	/** Autenticado pero sin permiso, o sin contexto de trabajo activo (403). Nunca 401. */
	FORBIDDEN("forbidden"),
	/** Recurso inexistente, o de otro tenant (404). Los dos casos son indistinguibles. */
	NOT_FOUND("not-found"),
	/** Conflicto de estado sin un tipo mas especifico (409). */
	CONFLICT("conflict"),
	/** Demasiados intentos en la ventana (429). */
	RATE_LIMITED("rate-limited"),
	/** Fallo no previsto. Nunca lleva detalle interno (500). */
	INTERNAL_ERROR("internal-error"),

	// --- Sesion, contexto y proteccion de la sesion --------------------------------------
	/** Credenciales rechazadas. Uniforme exista o no la cuenta (ADR-0018). */
	INVALID_CREDENTIALS("invalid-credentials"),
	/** Refresh ausente, vencido, rotado o reusado. Un solo texto para los cuatro. */
	INVALID_REFRESH("invalid-refresh"),
	/** Token de activacion o de recuperacion invalido, vencido o ya usado. */
	INVALID_TOKEN("invalid-token"),
	/** Falta el contexto de trabajo, o el elegido dejo de ser valido (403). */
	MISSING_TENANT_CONTEXT("missing-tenant-context"),
	/** Origin ausente o fuera de la lista blanca en un endpoint que se autentica por cookie. */
	CSRF_REJECTED("csrf-rejected"),

	// --- Organizacion, plan y suscripcion ------------------------------------------------
	/** El slug de organizacion ya esta tomado. */
	ORGANIZATION_SLUG_TAKEN("organization-slug-taken"),
	/** La transicion de suscripcion pedida no existe en la maquina de estados. */
	INVALID_SUBSCRIPTION_TRANSITION("invalid-subscription-transition"),
	/** Se alcanzo un limite del plan contratado. */
	PLAN_LIMIT_EXCEEDED("plan-limit-exceeded"),
	/** El plan contratado no incluye la funcionalidad pedida. */
	FEATURE_NOT_AVAILABLE("feature-not-available"),
	/** La suscripcion no esta ACTIVA y la operacion la exige. */
	SUBSCRIPTION_SUSPENDED("subscription-suspended"),
	/** La misma clave de idempotencia se reuso con un cuerpo distinto. */
	IDEMPOTENCY_KEY_CONFLICT("idempotency-key-conflict"),
	/** La version enviada quedo vieja: releer y reintentar. */
	CONCURRENT_MODIFICATION("concurrent-modification"),

	// --- Colaboradores --------------------------------------------------------------------
	/** Esa cuenta ya tiene un vinculo activo con la organizacion. */
	MEMBERSHIP_ALREADY_EXISTS("membership-already-exists"),
	/** El vinculo no esta ACTIVA y la operacion lo exige. */
	MEMBERSHIP_NOT_ACTIVE("membership-not-active"),
	/** La operacion dejaria a la organizacion sin ningun administrador. */
	LAST_ADMIN_REQUIRED("last-admin-required"),
	/** Nadie se revoca a si mismo su ultimo rol administrativo. */
	SELF_REVOKE_NOT_ALLOWED("self-revoke-not-allowed"),
	/** Ese permiso adicional ya esta vigente sobre el vinculo. */
	GRANT_ALREADY_ACTIVE("grant-already-active"),

	// --- Sedes --------------------------------------------------------------------------
	/** Ya hay una sede activa con ese nombre en la organizacion. */
	CONSULTORIO_NAME_TAKEN("consultorio-name-taken"),
	/** La sede esta dada de baja y la operacion exige una activa. */
	CONSULTORIO_INACTIVE("consultorio-inactive"),
	/** La sede ya estaba dada de baja. */
	CONSULTORIO_ALREADY_INACTIVE("consultorio-already-inactive"),
	/** La baja dejaria a la organizacion sin ninguna sede activa. */
	LAST_CONSULTORIO_REQUIRED("last-consultorio-required"),
	/** La sede tiene referencias vigentes que impiden darla de baja. */
	CONSULTORIO_HAS_ACTIVE_REFERENCES("consultorio-has-active-references"),

	// --- Espacios (M04) -------------------------------------------------------------------
	/** Ya hay un espacio vigente con ese nombre en esa sede. */
	ESPACIO_NAME_TAKEN("espacio-name-taken"),
	/** El espacio esta dado de baja y la operacion exige uno activo. */
	ESPACIO_INACTIVE("espacio-inactive"),
	/** El espacio ya estaba dado de baja. */
	ESPACIO_ALREADY_INACTIVE("espacio-already-inactive"),
	/**
	 * La capacidad pedida es menor que la ocupacion ya comprometida.
	 *
	 * <p><b>Reservado en F2.</b> No lo emite nadie mientras no exista ningun modulo que reserve
	 * lugares; se publica desde ya para que su aparicion en F5 no sea un cambio de
	 * comportamiento sorpresivo para el cliente. Lleva {@code requestedCapacity} y
	 * {@code currentOccupancy} como propiedades extra.
	 */
	ESPACIO_CAPACITY_BELOW_OCCUPANCY("espacio-capacity-below-occupancy"),
	/** El espacio tiene ocupacion vigente que impide darlo de baja. <b>Reservado en F2</b>. */
	ESPACIO_HAS_ACTIVE_REFERENCES("espacio-has-active-references"),

	// --- Catalogo clinico: especialidades, practicas y nomencladores (M06) -----------------
	CATALOGO_CODE_TAKEN("catalogo-code-taken"),
	CATALOGO_NAME_TAKEN("catalogo-name-taken"),
	CATALOGO_INACTIVE("catalogo-inactive"),
	CATALOGO_ALREADY_INACTIVE("catalogo-already-inactive"),
	CATALOGO_REFERENCE_INACTIVE("catalogo-reference-inactive"),
	CATALOGO_HAS_ACTIVE_REFERENCES("catalogo-has-active-references"),
	CATALOGO_SCOPE_MISMATCH("catalogo-scope-mismatch"),
	NOMENCLADOR_VIGENCIA_OVERLAP("nomenclador-vigencia-overlap"),
	CATALOGO_SOLICITUD_DUPLICADA("catalogo-solicitud-duplicada"),
	CATALOGO_SOLICITUD_YA_RESUELTA("catalogo-solicitud-ya-resuelta"),

	// --- Invitacion a colaborar (M05) ------------------------------------------------------
	INVITACION_PENDIENTE_DUPLICADA("invitacion-pendiente-duplicada"),
	INVITACION_VENCIDA("invitacion-vencida"),
	INVITACION_YA_RESUELTA("invitacion-ya-resuelta"),
	COLABORADOR_YA_VINCULADO("colaborador-ya-vinculado"),

	// --- Disponibilidad profesional, excepciones y calendario de sede (M05, AKINE-02.04) -----
	/** El bloque pedido se pisa con otro bloque activo del mismo profesional en esa sede. */
	BLOQUE_SOLAPADO("bloque-solapado"),
	/** El bloque esta dado de baja y la operacion exige uno vigente. */
	BLOQUE_INACTIVO("bloque-inactivo"),
	/** El bloque ya estaba dado de baja. */
	BLOQUE_ALREADY_INACTIVE("bloque-already-inactive"),
	/** La membership existe en el tenant pero no habilita a atender en ESA sede (RN-M05-001). */
	PROFESIONAL_NO_VINCULADO("profesional-no-vinculado"),
	/**
	 * La ventana {@code [desde, hasta)} supera el tope consultable (400).
	 *
	 * <p>Lleva {@code maximoDias} como propiedad extra, y no es decoracion: es lo que le permite
	 * a la pantalla recortar la ventana sola en vez de mostrarle un error al usuario.
	 */
	VENTANA_DEMASIADO_AMPLIA("ventana-demasiado-amplia"),
	/** La excepcion de disponibilidad ya estaba dada de baja. */
	EXCEPCION_ALREADY_INACTIVE("excepcion-already-inactive"),

	// --- Servicio global y oferta por consultorio (M27, AKINE-02.06) ------------------------
	/** Ya existe un servicio VIGENTE con ese codigo en el catalogo global. */
	SERVICIO_CODIGO_TAKEN("servicio-codigo-taken"),
	/** Ya existe un servicio VIGENTE con ese nombre. Comparado sin mayusculas ni acentos. */
	SERVICIO_NOMBRE_TAKEN("servicio-nombre-taken"),
	/**
	 * El servicio esta dado de baja y la operacion exige uno vigente.
	 *
	 * <p>Es el 409 de RF-M27-002: la baja de un servicio global NO cascadea sobre las ofertas
	 * que ya lo referencian —siguen operando—, lo que impide es crear ofertas nuevas sobre el.
	 */
	SERVICIO_INACTIVO("servicio-inactivo"),
	/** El servicio ya estaba dado de baja. */
	SERVICIO_ALREADY_INACTIVE("servicio-already-inactive"),
	/** Ya existe una oferta VIGENTE con ese nombre comercial en esa sede. */
	OFERTA_NOMBRE_COMERCIAL_TAKEN("oferta-nombre-comercial-taken"),
	/** La oferta esta dada de baja: no admite ediciones. Sus historicos siguen resolviendo. */
	OFERTA_INACTIVA("oferta-inactiva"),
	/** La oferta ya estaba dada de baja. */
	OFERTA_ALREADY_INACTIVE("oferta-already-inactive"),
	/** La sede existe y es accesible, pero su estado no admite operar sobre ella. */

	/**
	 * La oferta existe pero no puede agendar en ningun dia de la ventana pedida (M12).
	 *
	 * <p><b>409 y no 404 a proposito.</b> La oferta existe y quien pregunta la esta viendo en la
	 * lista; un 404 mandaria a la pantalla a decir "no encontrada" sobre algo que el usuario tiene
	 * delante. El motivo —de baja, o vigencia fuera de la ventana— viaja en el {@code detail} para
	 * que la pantalla pueda ofrecer la accion correcta: reactivar la oferta, o mover la fecha.
	 */
	OFERTA_NO_AGENDABLE("oferta-no-agendable"),

	// --- Turnos (M12) ----------------------------------------------------------------------
	/**
	 * El hueco pedido dejo de existir entre que la pantalla lo mostro y el usuario confirmo.
	 *
	 * <p>Distinto de {@link #SLOT_COMPLETO}: aca el slot no existe —cerraron el dia, cambio el
	 * horario, se cayo la habilitacion— y la pantalla tiene que RECARGAR la agenda. Con
	 * {@code slot-completo} el horario sigue ahi y lo que corresponde es ofrecer el siguiente.
	 * Dos acciones distintas exigen dos tipos distintos; un {@code conflict} generico obligaria al
	 * cliente a adivinar leyendo prosa en castellano.
	 */
	SLOT_NO_DISPONIBLE("slot-no-disponible"),
	/** El slot existe y ya no tiene cupo. Lleva {@code cupoTotal}. Ver {@link #SLOT_NO_DISPONIBLE}. */
	SLOT_COMPLETO("slot-completo"),
	/**
	 * El profesional o el espacio ya tienen otro turno que se CRUZA con el intervalo pedido.
	 *
	 * <p>Se cruza, no coincide: dos ofertas de duraciones distintas producen slots que no caen en
	 * la misma grilla. Lleva {@code recurso} para que la pantalla distinga "el profesional esta
	 * ocupado" de "no queda ningun box", que llevan a acciones distintas.
	 */
	RECURSO_OCUPADO("recurso-ocupado"),
	/**
	 * La persona existe en el padron pero no tiene perfil de paciente vigente.
	 *
	 * <p>RF-M07-010: una Persona no es un Paciente. La reserva no activa el perfil en silencio, asi
	 * que la pantalla tiene que ofrecer activarlo como una accion propia.
	 */
	PERSONA_SIN_PERFIL_PACIENTE("persona-sin-perfil-paciente"),

	// --- Ciclo de vida del turno (M12, AKINE-05.03) ------------------------------------------
	/**
	 * La transicion pedida no existe en la maquina de estados del turno, o su ventana no la admite.
	 *
	 * <p>Un solo tipo para las dos familias —"ya esta cancelado" y "ya empezo"— con el
	 * {@code motivo} como propiedad extra: para la pantalla el desenlace es el mismo, refrescar el
	 * turno y explicar por que no se puede, y publicar dos tipos obligaria al cliente a manejar dos
	 * codigos para una misma accion imposible.
	 */
	TURNO_TRANSICION_NO_PERMITIDA("turno-transicion-no-permitida"),
	/**
	 * El turno tiene una Sesion registrada: no se cancela, no se mueve y no se marca ausente.
	 *
	 * <p>Distinto del anterior porque lleva a otra accion: no hay nada que refrescar, hay una
	 * atencion que resolver. DP-05 mantiene separadas las dos maquinas de estado y ninguna
	 * transicion administrativa puede borrar la prueba de que la atencion ocurrio.
	 */
	TURNO_CON_ATENCION("turno-con-atencion"),

	// --- Atencion (M14) --------------------------------------------------------------------
	/**
	 * El turno existe pero no habilita una atencion: esta dado de baja, o lo atiende otro
	 * profesional. Lleva {@code motivo}, porque cada caso lleva a una accion distinta.
	 */
	TURNO_NO_ATENDIBLE("turno-no-atendible"),
	/**
	 * Alguien intenta guardar en la sesion de otro profesional.
	 *
	 * <p><b>409 y no 403 a proposito.</b> Quien opera SI tiene {@code sesion:register}: lo que no
	 * tiene es la propiedad de esa atencion. Un 403 mandaria al usuario a pedir un permiso que ya
	 * tiene. Es una regla de propiedad, no de autorizacion.
	 */
	SESION_AJENA("sesion-ajena"),
	/**
	 * Se intenta editar una atencion ya cerrada.
	 *
	 * <p>Corregirla es una ENMIENDA con su actor y su motivo —AKINE-06.06, fuera del Paquete B—.
	 * Hasta que exista es fail-closed: es preferible no poder corregir a corregir sin dejar rastro,
	 * porque lo segundo es historia clinica reescrita en silencio.
	 */
	SESION_CERRADA("sesion-cerrada"),

	// --- Deuda (M18) -----------------------------------------------------------------------
	/** La obligacion ya estaba anulada. Anular dos veces no es idempotente: es un error. */
	OBLIGACION_ALREADY_ANULADA("obligacion-already-anulada"),
	/**
	 * Se intenta anular una deuda que ya tiene cobros imputados. Lleva {@code yaCobrado}.
	 *
	 * <p>Anular lo que ya se cobro dejaria plata en la caja sin ninguna deuda que la justifique y
	 * el arqueo del dia no cerraria. Lo que corresponde es una devolucion, que es M19.
	 */
	OBLIGACION_CON_COBROS("obligacion-con-cobros"),

	// --- Cobros (M19) ----------------------------------------------------------------------
	/**
	 * La suma de los medios, o la de las imputaciones, no da el total del cobro.
	 *
	 * <p>Son invariantes de RN-M19 que ninguna constraint puede expresar —MySQL no admite
	 * subconsultas en un CHECK— asi que las verifica la aplicacion. Lleva {@code esperado} y
	 * {@code recibido} para que la pantalla muestre la diferencia en vez de un mensaje generico.
	 */
	COBRO_NO_CUADRA("cobro-no-cuadra"),
	/**
	 * Se intento imputar mas de lo que la deuda debe.
	 *
	 * <p>Es el desenlace legitimo de una carrera: otro cobro se llevo la plata entre que la
	 * pantalla mostro la cuenta corriente y el operador confirmo. Reintentar con la cuenta
	 * recargada es la accion correcta.
	 */
	SALDO_INSUFICIENTE("saldo-insuficiente"),
	/** La deuda esta anulada, ya pagada, o es de otra persona. Lleva {@code motivo}. */
	OBLIGACION_NO_COBRABLE("obligacion-no-cobrable"),
	CONSULTORIO_NO_OPERABLE("consultorio-no-operable"),

	// --- Personas y perfiles de paciente (M07, AKINE-03.01) ---------------------------------
	/**
	 * Ya existe una persona VIGENTE con ese documento en la organizacion.
	 *
	 * <p>Invariante DURO: no se puede confirmar ni saltear. Lleva {@code personaExistenteId} como
	 * propiedad extra cuando se lo pudo determinar, para que la pantalla ofrezca abrir la ficha
	 * que ya existe en vez de dejar al operador sin salida.
	 */
	PERSONA_DOCUMENTO_TAKEN("persona-documento-taken"),
	/**
	 * El alta coincide con personas ya registradas y nadie confirmo que sea otra distinta.
	 *
	 * <p>Es una ADVERTENCIA, no un invariante: reenviar el alta con {@code confirmaPosibleDuplicado}
	 * la acepta. Es RN-M07-001 —busqueda previa a la creacion— hecho cumplir por el backend. Lleva
	 * {@code candidatos} como propiedad extra, con los ids que coinciden.
	 */
	PERSONA_POSIBLE_DUPLICADO("persona-posible-duplicado"),
	/** La persona esta dada de baja y la operacion exige una vigente. Se sigue leyendo con 200. */
	PERSONA_INACTIVA("persona-inactiva"),

	// --- Adjuntos administrativos (M25, AKINE-03.02) -----------------------------------------
	/**
	 * El archivo no pasa la validacion de tipo o de tamano (400).
	 *
	 * <p><b>Un solo tipo para las dos familias</b>, con {@code motivo} —{@code TIPO_NO_PERMITIDO} o
	 * {@code DEMASIADO_GRANDE}— como propiedad extra. Para la pantalla el desenlace es el mismo:
	 * decir por que ese archivo no entra y pedir otro. Mismo criterio que
	 * {@link #TURNO_TRANSICION_NO_PERMITIDA}.
	 *
	 * <p>400 y no 415: el tipo declarado del request es correcto —es {@code multipart/form-data}—
	 * y lo que se rechaza es el CONTENIDO de una de sus partes.
	 */
	ARCHIVO_NO_ACEPTADO("archivo-no-aceptado"),
	/**
	 * La metadata del adjunto existe pero el almacenamiento no tiene su contenido (409, no 404).
	 *
	 * <p>La fila esta y se sigue listando. Un 404 le diria al operador que el documento no existe y
	 * lo empujaria a volver a subirlo bajo una ficha que todavia afirma tenerlo.
	 */
	ADJUNTO_NO_DISPONIBLE("adjunto-no-disponible"),
	/** El adjunto ya estaba dado de baja. Se sigue descargando; no se reclasifica. */
	ADJUNTO_INACTIVO("adjunto-inactivo"),
	// --- Financiadores y planes de cobertura (M15, AKINE-03.03) -----------------------------
	/** Ya existe un financiador VIGENTE con ese codigo en la organizacion. */
	FINANCIADOR_CODIGO_TAKEN("financiador-codigo-taken"),
	/** Ya existe un financiador VIGENTE con ese nombre en la organizacion. */
	FINANCIADOR_NOMBRE_TAKEN("financiador-nombre-taken"),
	/**
	 * Ya existe un financiador VIGENTE con ese CUIT en la organizacion.
	 *
	 * <p>Es un {@code type} propio y no una variante del de codigo: quien lo recibe tiene que
	 * entender que la obra social ya esta cargada con OTRO codigo, no que eligio mal el suyo.
	 */
	FINANCIADOR_CUIT_TAKEN("financiador-cuit-taken"),
	/**
	 * El financiador esta dado de baja y la operacion exige uno vigente.
	 *
	 * <p>Es el 409 que hace visible que la baja de un financiador NO cascadea: sus planes y las
	 * coberturas ya firmadas siguen resolviendo, y lo que se impide es crear planes NUEVOS bajo
	 * el. Mismo par, y mismo razonamiento, que {@link #SERVICIO_INACTIVO} en M27.
	 */
	FINANCIADOR_INACTIVO("financiador-inactivo"),
	/** El financiador ya estaba dado de baja. No existe la reactivacion. */
	FINANCIADOR_ALREADY_INACTIVE("financiador-already-inactive"),
	/** Ya existe un plan VIGENTE con ese codigo en ESE financiador. El alcance es el financiador. */
	PLAN_COBERTURA_CODIGO_TAKEN("plan-cobertura-codigo-taken"),
	/** Ya existe un plan VIGENTE con ese nombre en ese financiador. */
	PLAN_COBERTURA_NOMBRE_TAKEN("plan-cobertura-nombre-taken"),
	/** El plan esta dado de baja: no admite ediciones. Sus coberturas historicas resuelven. */
	PLAN_COBERTURA_INACTIVO("plan-cobertura-inactivo"),
	/** El plan ya estaba dado de baja. */
	PLAN_COBERTURA_ALREADY_INACTIVE("plan-cobertura-already-inactive");

	/** Prefijo unico de los {@code type} del proyecto (ADR-0005). */
	public static final String BASE = "https://akine.app/problems/";

	private final URI uri;

	ProblemType(String slug) {
		this.uri = URI.create(BASE + slug);
	}

	/** La URI absoluta, que es lo que viaja en el cuerpo del Problem Detail. */
	public URI uri() {
		return uri;
	}

	/** La URI como texto, para los caminos que serializan el cuerpo a mano. */
	public String value() {
		return uri.toString();
	}

	/**
	 * El catalogo completo, en el orden en que esta declarado.
	 *
	 * <p>Lo consume {@code OpenApiConfig} para publicar el schema. Devuelve las URIs y no los
	 * slugs porque es lo que el cliente compara contra {@code problem.type}.
	 */
	public static List<String> valores() {
		return Arrays.stream(values()).map(ProblemType::value).toList();
	}
}
