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
	PLAN_COBERTURA_ALREADY_INACTIVE("plan-cobertura-already-inactive"),

	// --- Coberturas del paciente (M08, AKINE-03.04) -----------------------------------------
	// El "no es paciente" reusa PERSONA_SIN_PERFIL_PACIENTE, que 05.02 ya declaro para la reserva
	// de turnos: es la MISMA condicion —RF-M07-010, una Persona no es un Paciente— y darle un
	// type propio obligaria al frontend a manejar dos codigos para la misma accion.
	/**
	 * El plan no se puede elegir para esa fecha (RN-M08-002, RN-M15-002).
	 *
	 * <p>Junta CINCO causas a proposito —el plan no existe, es de otro tenant, esta dado de baja,
	 * su financiador esta dado de baja, o la fecha cae fuera de su vigencia— y las responde igual.
	 * Distinguirlas volveria el alta de cobertura un oraculo del catalogo ajeno: bastaria recorrer
	 * ids para saber que planes tiene cargados otro centro del SaaS.
	 */
	PLAN_NO_SELECCIONABLE("plan-no-seleccionable"),
	/**
	 * El paciente ya tiene una cobertura activa del MISMO plan con la vigencia solapada.
	 *
	 * <p>Es un duplicado, no una segunda cobertura. Lleva {@code coberturaExistenteId}. <b>Dos
	 * coberturas de financiadores distintos solapadas NO producen este error</b>: obra social y
	 * prepaga a la vez es el caso normal.
	 */
	COBERTURA_SUPERPUESTA("cobertura-superpuesta"),
	/**
	 * Ya hay otra cobertura principal activa con la vigencia solapada. Lleva
	 * {@code coberturaPrincipalId}.
	 *
	 * <p>Con dos principales el mismo dia la seleccion vigente deja de ser determinista, y el
	 * desempate que habria que inventar no se podria explicar en el mostrador.
	 */
	COBERTURA_PRINCIPAL_SUPERPUESTA("cobertura-principal-superpuesta"),
	/** La cobertura esta dada de baja: no admite ediciones. Se sigue leyendo con 200. */
	COBERTURA_INACTIVA("cobertura-inactiva"),
	/** La cobertura ya estaba dada de baja. No existe la reactivacion. */
	COBERTURA_ALREADY_INACTIVE("cobertura-already-inactive"),
	// =================================================================================
	// M16 — convenios y aranceles (AKINE-03.05)
	// =================================================================================

	/** Ya existe un convenio VIGENTE con ese codigo en esa sede. El alcance es la sede. */
	CONVENIO_CODIGO_TAKEN("convenio-codigo-taken"),
	/**
	 * Ya existe un convenio de esa (sede, financiador, plan) cuyo periodo se pisa con el pedido
	 * (RN-M16-002).
	 *
	 * <p><b>Es el {@code type} que define AKINE-03.05.</b> No lo produce ningun unique y no podria:
	 * dos periodos que se cruzan no comparten ningun valor de columna. Lo produce el servicio
	 * despues de tomar el lock de {@code convenio_lock}, que es lo unico que garantiza que dos
	 * escrituras concurrentes no lo esquiven las dos.
	 */
	CONVENIO_SOLAPADO("convenio-solapado"),
	/** El convenio esta dado de baja: no admite ediciones ni aranceles nuevos. */
	CONVENIO_INACTIVO("convenio-inactivo"),
	/** El convenio ya estaba dado de baja. No existe la reactivacion. */
	CONVENIO_ALREADY_INACTIVE("convenio-already-inactive"),
	/** Ya existe un arancel de esa practica en ese convenio cuyo periodo se pisa con el pedido. */
	ARANCEL_SOLAPADO("arancel-solapado"),
	/** El arancel esta dado de baja: no admite ediciones. */
	ARANCEL_INACTIVO("arancel-inactivo"),
	/** El arancel ya estaba dado de baja. */
	ARANCEL_ALREADY_INACTIVE("arancel-already-inactive"),

	// --- Ordenes, autorizaciones y documentacion administrativa (M17, AKINE-03.06) ---

	ORDEN_INACTIVA("orden-inactiva"),
	ORDEN_ALREADY_INACTIVE("orden-already-inactive"),
	AUTORIZACION_INACTIVA("autorizacion-inactiva"),
	AUTORIZACION_ALREADY_INACTIVE("autorizacion-already-inactive"),
	AUTORIZACION_SUPERPUESTA("autorizacion-superpuesta"),
	AUTORIZACION_TRANSICION_NO_PERMITIDA("autorizacion-transicion-no-permitida"),
	DOCUMENTO_NUMERO_TAKEN("documento-numero-taken"),

	// --- Timeline, entradas clinicas y adjuntos clinicos (M09/M25, AKINE-04.02) -------------
	/**
	 * La entrada clinica no existe, es de otro tenant, o es de otra historia (404).
	 *
	 * <p>Las tres causas colapsan en un solo {@code type} <b>y en un solo status</b>, que es la
	 * regla de 01.01 llevada al modulo donde mas pesa: un 403 confirmaria que la fila existe, y
	 * probar ids consecutivos alcanzaria para censar cuantas entradas clinicas tiene otro centro.
	 * Deja de ser aislamiento y pasa a ser privacidad.
	 *
	 * <p>Es un {@code type} propio y no {@link #NOT_FOUND} porque la pantalla que lo recibe tiene
	 * una accion distinta segun QUE falto: si falto la historia, vuelve al padron; si falto la
	 * entrada, refresca el listado que la mostraba hace un segundo.
	 */
	ENTRADA_CLINICA_NO_ACCESIBLE("entrada-clinica-no-accesible"),
	/**
	 * La entrada clinica esta dada de baja y no admite contenido nuevo (409, no 404).
	 *
	 * <p>La entrada sigue siendo consultable por su id —eso es lo que distingue "no lo muestres"
	 * de "no existio"—, lo que no admite es una enmienda: produciria una version que nadie va a
	 * leer, porque la entrada ya salio del timeline. Para dejar constancia se registra una entrada
	 * nueva, que es otra operacion.
	 */
	ENTRADA_CLINICA_INACTIVA("entrada-clinica-inactiva"),
	/**
	 * Se intento enmendar sin declarar por que (400, no 409).
	 *
	 * <p>No hay conflicto de estado: la entrada esta vigente y el actor tiene permiso. Falta un
	 * dato del pedido, y un 409 mandaria al profesional a reintentar el mismo cuerpo, que falla
	 * exactamente igual. Sin motivo, una enmienda es indistinguible de una correccion de tipeo y
	 * el historial deja de servir para lo unico que sirve (RF-M09-006, RN-M09-004).
	 */
	ENMIENDA_SIN_MOTIVO("enmienda-sin-motivo"),
	/**
	 * El adjunto clinico no existe, es de otro tenant, o es de otra historia (404).
	 *
	 * <p>Mismo criterio y mismo motivo que {@link #ENTRADA_CLINICA_NO_ACCESIBLE}. Tambien lo
	 * emite el alta que apunta a una entrada que no es de esta historia: podria ser un 400 —el
	 * dato es incoherente— y es 404 deliberadamente, porque un 400 distinguiria "esa entrada no
	 * existe" de "existe pero es de otro paciente".
	 */
	ADJUNTO_CLINICO_NO_ACCESIBLE("adjunto-clinico-no-accesible"),
	/**
	 * El adjunto clinico esta dado de baja y la operacion exige uno vigente (409).
	 *
	 * <p><b>Se sigue descargando.</b> Negar la descarga convertiria la baja logica en un borrado
	 * con otro nombre, que es lo que la regla maestra 10 prohibe. Lo unico que un adjunto de baja
	 * no admite es reclasificarse.
	 */
	ADJUNTO_CLINICO_INACTIVO("adjunto-clinico-inactivo"),
	/**
	 * La metadata del adjunto clinico existe pero el almacenamiento no tiene su contenido (409).
	 *
	 * <p>409 y no 404: la fila esta y quien pregunta la esta viendo en la lista. Un 404 le diria
	 * al profesional que el estudio no existe y lo empujaria a pedirselo de nuevo al paciente bajo
	 * una historia que todavia afirma tenerlo. Par exacto de {@link #ADJUNTO_NO_DISPONIBLE} en el
	 * lado administrativo; son {@code type} distintos porque son modulos, permisos y auditorias
	 * distintas.
	 */
	ADJUNTO_CLINICO_NO_DISPONIBLE("adjunto-clinico-no-disponible"),
	/**
	 * El cursor de paginacion del timeline no se pudo decodificar (400).
	 *
	 * <p>El cursor es opaco y la unica forma legitima de obtener uno es haber leido la pagina
	 * anterior, asi que uno que no parsea o lo construyo un cliente a mano o lo trunco por el
	 * camino. <b>No se reinterpreta como "primera pagina"</b>: contestar la primera pagina ante un
	 * cursor roto haria que un cliente con un bug de paginacion recorriera la misma pagina para
	 * siempre sin que nadie lo note.
	 */
	CURSOR_INVALIDO("cursor-invalido"),

	// --- Caso Clinico (M10, AKINE-04.03) ----------------------------------------------------
	/**
	 * El caso clinico no existe, es de otro tenant, o es de otra historia (404).
	 *
	 * <p>Mismo criterio y mismo motivo que {@link #ENTRADA_CLINICA_NO_ACCESIBLE}: las tres causas
	 * colapsan en un solo {@code type} y en un solo status. Un 403 confirmaria que la fila existe,
	 * y probar ids consecutivos alcanzaria para censar cuantos casos clinicos tiene otro centro
	 * del SaaS — que deja de ser aislamiento y pasa a ser privacidad.
	 */
	CASO_CLINICO_NO_ACCESIBLE("caso-clinico-no-accesible"),
	/**
	 * El caso esta cerrado y la operacion exige uno activo (409, no 404 y no 403).
	 *
	 * <p>El caso existe y se sigue leyendo entero con todo su historial —eso distingue "termino"
	 * de "no existio"— y quien opera SI tiene {@code hc:write}: lo que no admite la operacion es el
	 * estado. La accion que la pantalla tiene que ofrecer es <b>reabrir con motivo</b>, que queda
	 * en el historial (RF-M10-006). Editar en silencio un caso terminado es historia clinica
	 * reescrita, y ADR-0011 lo prohibe.
	 */
	CASO_CLINICO_CERRADO("caso-clinico-cerrado"),
	/**
	 * El alta coincide con un caso ACTIVO de la misma historia y la misma oferta (409).
	 *
	 * <p>Es una <b>advertencia</b>, no un invariante: RN-M10-002 admite varios casos activos, y
	 * reenviar el alta con {@code confirmaPosibleDuplicado} la acepta. Lleva {@code candidatos} con
	 * los ids que coinciden, sin los cuales el 409 seria un callejon. Mismo mecanismo, y misma
	 * pantalla, que {@link #PERSONA_POSIBLE_DUPLICADO} en el alta de Persona.
	 *
	 * <p><b>Es un tipo distinto de {@link #CONCURRENT_MODIFICATION} y tiene que serlo:</b> este se
	 * resuelve confirmando y aquel releyendo. Un solo tipo para los dos obligaria al cliente a
	 * adivinar cual de las dos acciones corresponde leyendo prosa en castellano.
	 */
	CASO_CLINICO_POSIBLE_DUPLICADO("caso-clinico-posible-duplicado"),
	/**
	 * Se intento cerrar o reabrir un caso sin declarar por que (400, no 409).
	 *
	 * <p>No hay conflicto de estado: falta un dato del pedido, y un 409 mandaria al profesional a
	 * reintentar el mismo cuerpo, que falla igual. Sin motivo, un cierre es indistinguible de un
	 * abandono y el historial deja de servir para lo unico que sirve. Mismo reparto que
	 * {@link #ENMIENDA_SIN_MOTIVO}.
	 */
	CASO_SIN_MOTIVO_DE_CIERRE("caso-sin-motivo-de-cierre"),
	/**
	 * La oferta que motiva el caso no existe en la sede o no esta vigente (409, no 404).
	 *
	 * <p>RN-M10-006: la necesidad de Caso la determina la Oferta efectiva, asi que un caso que
	 * apunta a una oferta que no puede prestarse no tiene sobre que apoyarse. Mismo criterio que
	 * {@link #OFERTA_NO_AGENDABLE}: la oferta existe y quien la eligio la esta viendo en una lista,
	 * asi que lo que corresponde es ofrecerle reactivarla o elegir otra, no decir "no encontrada".
	 */
	OFERTA_NO_VIGENTE("oferta-no-vigente"),

	// --- Plan de Tratamiento (M11, AKINE-04.04) ---------------------------------------------
	/**
	 * El plan pedido no existe dentro del alcance del actor (404).
	 *
	 * <p>Junta "no existe", "es de otro tenant" y "su caso o su historia no resuelven", y es
	 * deliberado: un 403 confirmaria que la fila existe, y probar ids consecutivos alcanzaria para
	 * censar cuantos tratamientos tiene en curso otro centro del SaaS. Mismo criterio que
	 * {@link #CASO_CLINICO_NO_ACCESIBLE}.
	 */
	PLAN_NO_ACCESIBLE("plan-no-accesible"),
	/**
	 * Se intento cambiar el contenido de un plan FINALIZADO (409, no 404 y no 403).
	 *
	 * <p>El plan existe y se sigue leyendo entero, con todas sus versiones —eso distingue "termino"
	 * de "no existio"— y quien opera si tiene {@code hc:write}: lo que no admite cambios es el
	 * estado. La accion que la pantalla tiene que ofrecer es <b>crear un plan nuevo</b>, porque un
	 * plan finalizado no se reabre.
	 */
	PLAN_NO_EDITABLE("plan-no-editable"),
	/**
	 * La transicion pedida no sale del estado en que esta el plan (409).
	 *
	 * <p>Activar un finalizado, reanudar uno que no esta suspendido, suspender un borrador. Lleva
	 * {@code estadoActual} para que la pantalla diga cual era en vez de un mensaje generico.
	 *
	 * <p><b>No</b> cubre los reintentos: suspender lo suspendido o activar lo activo son el mismo
	 * pedido y se responden 200 con el plan tal como quedo.
	 */
	PLAN_TRANSICION_INVALIDA("plan-transicion-invalida"),
	/**
	 * Se intento crear o activar un plan sobre un Caso que ya no esta ACTIVO (409).
	 *
	 * <p>Es un tipo distinto de {@link #CASO_CLINICO_CERRADO} porque lleva a otra accion: aquel lo
	 * emite el Caso cuando alguien quiere editarlo, este lo emite el Plan cuando el Caso que lo
	 * tendria que sostener esta cerrado. Lo que corresponde ofrecer es reabrir el caso con motivo
	 * antes de planificar nada.
	 */
	CASO_NO_ACTIVO("caso-no-activo"),
	/**
	 * Una oferta que se quiso planificar no existe en la sede o no esta habilitada hoy (409).
	 *
	 * <p>Distinto de {@link #OFERTA_NO_VIGENTE}, que es del alta de Caso: aquella pantalla elige
	 * <b>una</b> oferta y esta carga <b>varias</b> de una vez, asi que lleva {@code ofertaId} para
	 * poder señalar cual de todas es la que no entra en vez de rechazar el formulario entero.
	 *
	 * <p>Que la oferta se de de baja <b>despues</b> de planificar no invalida el plan: la baja de un
	 * servicio no cascadea, solo impide crear nuevos.
	 */
	OFERTA_NO_HABILITADA("oferta-no-habilitada"),
	/**
	 * No queda saldo en la autorizacion para el movimiento pedido (409). AKINE-04.05.
	 *
	 * <p>Lo decide la BASE con un {@code UPDATE} condicional —cero filas afectadas—, no un
	 * {@code if} sobre un saldo leido: entre leer el saldo y escribirlo hay una ventana en la que
	 * otra sesion se lleva la ultima unidad.
	 *
	 * <p><b>El cierre de una sesion NUNCA lo emite.</b> La atencion ocurrio, y bloquear el cierre
	 * de una historia clinica porque el financiador se quedo sin cupo es lo que DP-06 prohibe: ahi
	 * el saldo insuficiente es un desenlace registrado, no un error. Este tipo sale por los
	 * caminos que si tienen a alguien a quien avisarle.
	 */
	AUTORIZACION_SIN_SALDO("autorizacion-sin-saldo"),
	/**
	 * La autorizacion no habilita ese dia: vencida, aun no vigente, o no APROBADA (409).
	 *
	 * <p>Vencida <b>no es un estado persistido</b>: se calcula al leer contra la fecha que se
	 * pregunta, porque materializarla exigiria un job y un job que no corre deja autorizaciones
	 * vencidas que el sistema cree vigentes. Lleva {@code fecha} porque "vencida" depende de
	 * cuando se pregunta.
	 */
	AUTORIZACION_VENCIDA("autorizacion-vencida"),
	/**
	 * Ese consumo ya tiene su reversion (409). AKINE-04.05.
	 *
	 * <p>Lo garantiza el unique del ledger: la reversion apunta al <b>mismo</b> origen que el
	 * consumo que compensa, asi que la segunda choca. Eso es lo que hace a la reversion
	 * idempotente en vez de meramente segura.
	 *
	 * <p>Lleva {@code reversionExistenteId} para que la pantalla pueda mostrarla —con su motivo y
	 * su autor— en vez de dejar al operador preguntandose quien lo revirtio.
	 */
	MOVIMIENTO_YA_REVERTIDO("movimiento-ya-revertido"),
	/**
	 * Se pidio revertir un consumo sin declarar por que (400). RF-M17-005.
	 *
	 * <p><b>400 y no 409</b>: no hay ningun estado del sistema que impida la operacion, falta un
	 * dato del pedido. Confundirlos haria que la pantalla ofrezca "reintentar" donde lo que
	 * corresponde es "completa el motivo".
	 */
	REVERSION_SIN_MOTIVO("reversion-sin-motivo"),

	// --- Caja diaria (M20, AKINE-07.03) ------------------------------------------------------
	/**
	 * La sede no tiene una jornada de caja abierta y la operacion exige una (409).
	 *
	 * <p><b>Es el que puede aparecer al registrar un cobro</b>, y no solo en las operaciones de
	 * caja: un cobro que incluye efectivo lo exige. La plata entra al cajon exista o no la jornada,
	 * y si el sistema no sabe a cual pertenece, el arqueo de ese dia no cuadra contra nada. Los
	 * medios que no son efectivo no lo producen: esa plata nunca toco el cajon.
	 *
	 * <p>La pantalla tiene que ofrecer abrir la caja ante este error; si solo dice "no se puede", el
	 * administrativo queda trabado sin entender por que.
	 */
	CAJA_NO_ABIERTA("caja-no-abierta"),
	/**
	 * Ya hay una jornada abierta en esa sede (409). Lleva {@code jornadaAbiertaId}.
	 *
	 * <p>A lo sumo una por sede: dos cajas abiertas sobre el mismo cajon fisico hacen que ningun
	 * arqueo se pueda atribuir. El id viaja para que la pantalla lleve al operador a la jornada que
	 * ya existe en vez de dejarlo sin salida.
	 */
	CAJA_YA_ABIERTA("caja-ya-abierta"),
	/**
	 * La jornada ya estaba cerrada (409). RN-M20-003: una caja cerrada no se edita en silencio.
	 *
	 * <p>Es tambien el desenlace del <b>cierre concurrente</b>: dos cierres simultaneos, el segundo
	 * afecta cero filas. No existe la reapertura — un error se compensa con movimientos en la
	 * jornada abierta hoy.
	 */
	CAJA_CERRADA("caja-cerrada"),
	/**
	 * El saldo teorico cambio entre que el operador empezo a contar y confirmo el cierre (409).
	 * Lleva {@code saldoTeoricoActual}.
	 *
	 * <p><b>Es el control que impide registrar un faltante que nunca existio.</b> Si un cobro en
	 * efectivo entra mientras se cuenta, un cierre ingenuo lo registraria como diferencia y
	 * RN-M20-004 obligaria a justificar por escrito un desvio inventado. La accion correcta es
	 * sumar los billetes que entraron y confirmar contra el numero nuevo.
	 */
	CAJA_SALDO_CAMBIO("caja-saldo-cambio"),
	/**
	 * El egreso dejaria la caja en negativo (409). Lleva {@code saldoDisponible}.
	 *
	 * <p>No es una regla de negocio configurable: un cajon no puede tener menos de cero pesos. Lo
	 * decide una condicion del motor —{@code WHERE saldo_arqueo >= :importe}— y no un {@code if},
	 * asi que dos egresos concurrentes no pueden colarse los dos.
	 */
	CAJA_SALDO_INSUFICIENTE("caja-saldo-insuficiente"),
	/**
	 * El movimiento viene en una moneda distinta de la de la jornada (409).
	 *
	 * <p>Un arqueo que suma pesos con dolares no se puede contar, y convertir exigiria una
	 * cotizacion que es una decision de negocio que nadie tomo.
	 */
	CAJA_MONEDA_DISTINTA("caja-moneda-distinta"),
	/**
	 * Ese movimiento de caja no admite reversion (409). Lleva {@code motivo}.
	 *
	 * <p>Dos causas bajo un solo tipo, porque para la pantalla el desenlace es el mismo: ya fue
	 * revertido, o es una reversion —y una reversion no se revierte, se asienta un movimiento
	 * nuevo—. Es un tipo distinto de {@link #MOVIMIENTO_YA_REVERTIDO}, que es del ledger de
	 * autorizaciones (M17) y no del de caja.
	 */
	MOVIMIENTO_NO_REVERSIBLE("movimiento-no-reversible"),
	/**
	 * El arqueo no cuadra y el cierre no trae motivo (400). RN-M20-004.
	 *
	 * <p><b>400 y no 409</b>: el estado del servidor esta perfecto y lo que falta es un campo del
	 * cuerpo. La diferencia no se rechaza —eso dejaria al centro sin poder cerrar el dia en que
	 * realmente falta plata— y no se ajusta; lo unico que se exige es que alguien escriba por que.
	 */
	CAJA_DIFERENCIA_SIN_MOTIVO("caja-diferencia-sin-motivo"),

	// =================================================================================
	// Presentaciones a financiadores — M21 (AKINE-07.04)
	// =================================================================================

	/**
	 * El lote ya salio del centro: no se le agregan ni se le quitan prestaciones (409).
	 *
	 * <p>Una presentacion confirmada existe del otro lado del mostrador. Lo que corresponde es un
	 * <b>debito</b>, que deja motivo, actor e instante.
	 */
	PRESENTACION_NO_EDITABLE("presentacion-no-editable"),
	/**
	 * La transicion no sale de ese estado (409). Lleva {@code estadoActual} y {@code esperado}.
	 *
	 * <p>Un solo tipo para toda la maquina de estados de M21: para la pantalla el desenlace es
	 * siempre el mismo —refrescar el lote y mirar en que quedo—.
	 */
	PRESENTACION_ESTADO_INVALIDO("presentacion-estado-invalido"),
	/**
	 * Se intento confirmar un lote sin prestaciones (400).
	 *
	 * <p><b>400 y no 409</b>: no hay nada del estado del servidor que haya cambiado; lo que falta
	 * es contenido. Un reclamo por cero pesos ademas consumiria un numero de la serie para no decir
	 * nada.
	 */
	PRESENTACION_VACIA("presentacion-vacia"),
	/**
	 * El lote tiene prestaciones que no se pueden reclamar (409). Lleva {@code hallazgos}.
	 *
	 * <p>La lista entera y no el primero: el administrativo tiene que poder arreglar todo de una
	 * vez, y devolver el primero lo obligaria a reintentar tantas veces como items rotos haya.
	 */
	PRESENTACION_CON_HALLAZGOS("presentacion-con-hallazgos"),
	/**
	 * Esa prestacion ya esta viva en otro lote (409). RN-M21-003. Lleva {@code presentacionId}.
	 *
	 * <p>No lo decide un {@code if} sino el unique sobre la columna generada {@code ocupa_marca}: un
	 * item debitado o anulado <b>libera</b> la obligacion, asi que re-presentar lo rechazado es un
	 * camino normal.
	 */
	OBLIGACION_YA_PRESENTADA("obligacion-ya-presentada"),
	/**
	 * Esa deuda no se le puede reclamar a este financiador, en este lote (409). Lleva
	 * {@code motivo}.
	 *
	 * <p>Anulada, sin saldo, de otro financiador, de otra sede, fuera del periodo o en otra moneda.
	 */
	OBLIGACION_NO_PRESENTABLE("obligacion-no-presentable"),
	/**
	 * El pago o el debito es mayor que lo que queda por explicar del lote (409).
	 *
	 * <p>Lo decide una condicion del motor —{@code WHERE saldo >= :importe}— y no un {@code if}, asi
	 * que un aviso de debito y una transferencia cargados a la vez no pueden colarse los dos y
	 * dejar el saldo en negativo. Lleva {@code saldoDisponible}, que es lo que permite al operador
	 * entender que hubo un debito en vez de reintentar a ciegas.
	 */
	PRESENTACION_SALDO_INSUFICIENTE("presentacion-saldo-insuficiente"),
	/**
	 * Queda plata reclamada sin explicar (409). Lleva {@code residual}.
	 *
	 * <p>Conciliar exige saldo cero. No hay cierre con diferencia: el sistema nombra el residual y
	 * se niega a fingir, y el administrativo registra el debito o el pago que falta.
	 */
	PRESENTACION_NO_CONCILIA("presentacion-no-concilia"),
	/**
	 * Ese item ya no admite un debito (409): ya fue debitado, aceptado o anulado.
	 *
	 * <p>Un segundo debito sobre la misma fila restaria dos veces del saldo del lote por una sola
	 * prestacion rechazada.
	 */
	ITEM_NO_DEBITABLE("item-no-debitable"),
	/**
	 * Ese numero de factura ya esta en otro lote del mismo financiador (409).
	 *
	 * <p>El comprobante es del centro y se emite fuera de AKINE: el sistema no lo genera ni lo
	 * numera, lo registra, y lo unico que puede hacer es impedir que el mismo numero quede asociado
	 * a dos lotes.
	 */
	FACTURA_DUPLICADA("factura-duplicada"),

	// =================================================================================
	// Egresos y pagos a profesionales — M22 (AKINE-07.05)
	//
	// Del lado del egreso tambien son tres cosas: el compromiso (`egreso`), el acto de
	// saldarlo (`pago_egreso`) y el hecho monetario (`movimiento_caja`, reusado de M20).
	// Estos tipos son los del compromiso y del pago; los de la caja se reusan tal cual.
	// =================================================================================

	/**
	 * Se intenta editar un egreso que ya no es borrador (409).
	 *
	 * <p>Confirmar congela beneficiario, importe y comprobante. Un importe que cambiara debajo de
	 * pagos ya asentados haria que el saldo dejara de reconciliar con el ledger de caja
	 * <b>sin que nada fallara</b>.
	 */
	EGRESO_NO_EDITABLE("egreso-no-editable"),
	/** Se intenta confirmar algo que ya no es un borrador (409). */
	EGRESO_NO_CONFIRMABLE("egreso-no-confirmable"),
	/**
	 * Se confirma un egreso sin tipo ni numero de comprobante (<b>400</b>).
	 *
	 * <p>400 y no 409: el estado del servidor esta perfecto y lo que falta es un campo del cuerpo,
	 * mismo criterio que {@link #CAJA_DIFERENCIA_SIN_MOTIVO}. En borrador el comprobante es
	 * opcional a proposito: la liquidacion se arma antes de tener la factura en la mano.
	 */
	EGRESO_SIN_COMPROBANTE("egreso-sin-comprobante"),
	/** El egreso ya estaba anulado (409). Anular no borra: la fila sigue ahi con su motivo. */
	EGRESO_YA_ANULADO("egreso-ya-anulado"),
	/**
	 * Se anula un egreso que todavia tiene pagos vigentes (409).
	 *
	 * <p>Lleva {@code yaPagado}: sin ese numero la pantalla solo puede decir "no se puede", y con
	 * el puede nombrar la accion correcta — anular primero los pagos, que es lo que devuelve la
	 * plata al cajon.
	 */
	EGRESO_CON_PAGOS("egreso-con-pagos"),
	/**
	 * El egreso no admite el pago por su estado (409).
	 *
	 * <p>Un borrador no se paga, un anulado tampoco, y uno saldado no debe nada. <b>Solo un egreso
	 * confirmado puede mover la caja</b>: es como esta etapa lee RN-M22-001.
	 */
	EGRESO_NO_PAGABLE("egreso-no-pagable"),
	/**
	 * El pago excede lo que todavia se debe (409).
	 *
	 * <p>409 y no 400: el importe era valido cuando se compuso y lo que cambio es el estado del
	 * servidor, porque otro pago se llevo el saldo primero.
	 */
	EGRESO_SALDO_INSUFICIENTE("egreso-saldo-insuficiente"),
	/**
	 * Ese comprobante de ese beneficiario ya esta cargado y vigente (409).
	 *
	 * <p>Es el caso borde "factura externa duplicada". La clave del beneficiario entra en la
	 * unicidad porque dos proveedores distintos emiten legitimamente su propia factura numero uno.
	 */
	EGRESO_COMPROBANTE_DUPLICADO("egreso-comprobante-duplicado"),
	/** El pago ya estaba anulado (409). Revertir dos veces sacaria plata del cajon dos veces. */
	PAGO_EGRESO_YA_ANULADO("pago-egreso-ya-anulado"),
	/**
	 * Se crea un egreso contra una membership que no esta vigente (409).
	 *
	 * <p><b>Solo al crear.</b> Confirmar y pagar un egreso cuyo beneficiario ya se desvinculo tiene
	 * que funcionar: lo contrario convertiria una desvinculacion en una forma de no pagar.
	 */
	BENEFICIARIO_NO_VINCULADO("beneficiario-no-vinculado"),

	// --- M23 reportes (AKINE-07.06) -------------------------------------------------

	/**
	 * El periodo pedido no sirve para reportar (400).
	 *
	 * <p>Cubre el rango invertido y la ventana demasiado ancha. Es un tipo propio y no un
	 * {@code validation-error} generico porque la pantalla tiene algo concreto que hacer con el:
	 * acotar el periodo y reintentar. Un {@code validation-error} la obligaria a leer el texto.
	 *
	 * <p>El detalle lleva {@code maximoDias} para que el cliente no tenga que hardcodear la
	 * ventana ni descubrirla probando.
	 */
	RANGO_DE_REPORTE_INVALIDO("rango-de-reporte-invalido");

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
