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
	EXCEPCION_ALREADY_INACTIVE("excepcion-already-inactive");

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
