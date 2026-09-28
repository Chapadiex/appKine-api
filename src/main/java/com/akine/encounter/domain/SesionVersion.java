package com.akine.encounter.domain;

import com.akine.encounter.domain.exception.EnmiendaSinMotivoException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * El contenido de una version de sesion cerrada (RF-M14-010, RF-M24-005).
 *
 * <h2>Esta fila no cambia y no se da de baja. Nunca</h2>
 *
 * <p>No hay un solo metodo que modifique nada, no hay {@code active}, no hay {@code deleted_at} y
 * no hay {@code @Version}. Las cuatro ausencias son la misma decision, y es la misma que tomo
 * {@code clinical.EntradaClinicaVersion} en 04.02: <b>una version es un hecho pasado</b>.
 * Corregirla seria el {@code UPDATE} que RN-M14-006 evita, y darla de baja seria reescribir
 * historia clinica, que ADR-0011 prohibe.
 *
 * <h2>La v1 es el original, y se escribe al CERRAR</h2>
 *
 * <p>No al enmendar. Si se escribiera perezosamente en la primera enmienda, una sesion nunca
 * enmendada no tendria historial y el endpoint tendria que sintetizar la v1 leyendo la cabecera —
 * un segundo camino de lectura que puede divergir del primero—. Escribirla en el cierre hace que
 * <b>toda sesion cerrada tenga historial completo</b> y que consultarlo sea una sola consulta.
 * Las sesiones cerradas antes de {@code V53} la reciben por el backfill de esa migracion.
 *
 * <h2>Por que el autor es de la version y no de la sesion</h2>
 *
 * <p>Quien enmienda no suele ser quien cerro —un supervisor que corrige, el profesional del turno
 * siguiente— y guardar el autor solo en la cabecera perderia exactamente el dato por el que existe
 * el historial. {@link #registradaPor} es por fila. En la v1 vale {@code cerrada_por_cuenta_id}.
 *
 * <h2>El motivo, y por que su ausencia es 400</h2>
 *
 * <p>La v1 no lleva motivo: no enmienda nada. Toda posterior lo exige, aca y en la base
 * ({@code ck_sesion_version_motivo_de_enmienda}). Sin motivo, una enmienda es indistinguible de
 * una correccion de tipeo y el historial deja de servir para lo unico que sirve.
 *
 * <p>Se rechaza con {@link EnmiendaSinMotivoException}, que la capa HTTP mapea a <b>400 y no
 * 409</b>: no hay conflicto de estado —la sesion esta cerrada y el actor es su dueño—, falta un
 * dato del pedido. Un 409 invitaria a reintentar, y reintentar sin motivo vuelve a fallar.
 *
 * <h2>Las dos fabricas leen la sesion, y eso es la garantia</h2>
 *
 * <p>Ni {@link #original} ni {@link #enmienda} reciben contenido: lo <b>copian de la sesion</b>.
 * Por eso la version no puede decir algo distinto de lo que la sesion vigente dice, sin depender
 * de que el servicio recuerde copiar los doce campos en el mismo orden.
 */
@Entity
@Table(name = "sesion_version")
public class SesionVersion {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/**
	 * Tenant propietario. Es derivable de la sesion y se guarda <b>igual</b>.
	 *
	 * <p>Derivarlo obligaria a un join para filtrar por tenant, y el dia que alguien escriba la
	 * consulta sin ese join tiene una fuga que ningun test de la etapa ve, porque los tests de una
	 * etapa corren con un solo tenant. Es el mismo argumento de {@code entrada_clinica_version}.
	 */
	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "sesion_id", nullable = false, updatable = false)
	private Long sesionId;

	@Column(name = "numero_version", nullable = false, updatable = false)
	private int numeroVersion;

	@Column(name = "motivo_clinico", length = 500, updatable = false)
	private String motivoClinico;

	@Column(name = "dolor_eva", updatable = false)
	private Integer dolorEva;

	@Column(name = "dolor_zona", length = 120, updatable = false)
	private String dolorZona;

	@Enumerated(EnumType.STRING)
	@Column(name = "dolor_lateralidad", length = 16, updatable = false)
	private Lateralidad dolorLateralidad;

	@Enumerated(EnumType.STRING)
	@Column(name = "evolucion", length = 16, updatable = false)
	private Evolucion evolucion;

	@Column(name = "objetivo_sesion", length = 500, updatable = false)
	private String objetivoSesion;

	@Column(name = "limitacion_funcional", length = 500, updatable = false)
	private String limitacionFuncional;

	@Column(name = "nota_de_cierre", length = 2000, updatable = false)
	private String notaDeCierre;

	@Column(name = "respuesta_tratamiento", length = 500, updatable = false)
	private String respuestaTratamiento;

	@Enumerated(EnumType.STRING)
	@Column(name = "tolerancia", length = 16, updatable = false)
	private Tolerancia tolerancia;

	@Column(name = "indicaciones", length = 1000, updatable = false)
	private String indicaciones;

	@Enumerated(EnumType.STRING)
	@Column(name = "proxima_conducta", length = 16, updatable = false)
	private ProximaConducta proximaConducta;

	/** El tope que {@code V53} le puso a la columna. Ver {@link #motivoCoherente}. */
	public static final int MOTIVO_MAXIMO = 280;

	@Column(name = "motivo_enmienda", length = MOTIVO_MAXIMO, updatable = false)
	private String motivoEnmienda;

	@Column(name = "registrada_en", nullable = false, updatable = false)
	private Instant registradaEn;

	@Column(name = "registrada_por", nullable = false, updatable = false)
	private Long registradaPor;

	protected SesionVersion() {
		// Requerido por JPA.
	}

	private SesionVersion(
			Sesion sesion, int numeroVersion, String motivoEnmienda,
			Instant registradaEn, Long registradaPor) {

		if (numeroVersion < 1) {
			throw new IllegalArgumentException("La numeracion de versiones empieza en 1");
		}
		this.organizationId = sesion.getOrganizationId();
		this.sesionId = exigirNoNulo(sesion.getId(),
				"La version necesita una sesion ya persistida: sin id no hay a que colgarla");
		this.numeroVersion = numeroVersion;

		this.motivoClinico = sesion.getMotivoClinico();
		this.dolorEva = sesion.getDolorEva();
		this.dolorZona = sesion.getDolorZona();
		this.dolorLateralidad = sesion.getDolorLateralidad();
		this.evolucion = sesion.getEvolucion();
		this.objetivoSesion = sesion.getObjetivoSesion();
		this.limitacionFuncional = sesion.getLimitacionFuncional();
		this.notaDeCierre = sesion.getNotaDeCierre();
		this.respuestaTratamiento = sesion.getRespuestaTratamiento();
		this.tolerancia = sesion.getTolerancia();
		this.indicaciones = sesion.getIndicaciones();
		this.proximaConducta = sesion.getProximaConducta();

		this.motivoEnmienda = motivoCoherente(numeroVersion, motivoEnmienda, this.sesionId);
		this.registradaEn = exigirNoNulo(registradaEn, "La version deja siempre su instante");
		this.registradaPor = exigirNoNulo(registradaPor, "La version deja siempre a su autor");
	}

	/**
	 * La version 1: el contenido con el que la sesion se cerro.
	 *
	 * <p>Su instante y su autor son los del <b>cierre</b>, no los de "ahora": la v1 no es un hecho
	 * nuevo, es el registro de uno que acaba de ocurrir. El {@code CHECK} de {@code V35} garantiza
	 * que los dos existen en cuanto la sesion esta cerrada.
	 */
	public static SesionVersion original(Sesion sesion) {
		if (!sesion.estaCerrada()) {
			throw new IllegalStateException(
					"La version 1 de una sesion se escribe al cerrarla: una sesion abierta todavia "
							+ "no tiene contenido versionado");
		}
		return new SesionVersion(
				sesion, 1, null, sesion.getCerradaEn(), sesion.getCerradaPorCuentaId());
	}

	/**
	 * Una enmienda: el contenido de la sesion <b>despues</b> de aplicarla.
	 *
	 * <p>Se construye a partir de la sesion ya modificada y con el numero que ella misma reservo en
	 * {@code Sesion#enmendar}. El orden importa: llamarla antes de aplicar la enmienda copiaria el
	 * contenido viejo con el numero nuevo, que es la peor fila posible —parece un historial y
	 * miente—.
	 */
	public static SesionVersion enmienda(
			Sesion sesion, String motivo, Instant registradaEn, Long registradaPor) {

		return new SesionVersion(
				sesion, sesion.getUltimoNumeroVersion(), motivo, registradaEn, registradaPor);
	}

	/** {@code true} si esta version es una enmienda y no el contenido original. */
	public boolean esEnmienda() {
		return numeroVersion > 1;
	}

	private static String motivoCoherente(int numeroVersion, String motivo, Long sesionId) {
		String limpio = motivo == null || motivo.isBlank() ? null : motivo.strip();

		if (numeroVersion == 1) {
			// El original no enmienda nada. Un motivo aca no es un error del usuario —la pantalla
			// no lo pide— asi que se descarta en vez de rechazar el cierre entero.
			return null;
		}
		if (limpio == null) {
			throw new EnmiendaSinMotivoException(sesionId);
		}
		if (limpio.length() > MOTIVO_MAXIMO) {
			// El largo se controla aca y no solo en el DTO: del otro lado no hay un error de
			// validacion prolijo sino un VARCHAR truncado, que MySQL reporta como
			// DataIntegrityViolationException y el handler global devuelve como 409 "choca con un
			// dato ya existente" sobre una enmienda que ademas se pierde.
			throw new IllegalArgumentException(
					"El motivo de la enmienda no puede superar los " + MOTIVO_MAXIMO
							+ " caracteres");
		}
		return limpio;
	}

	private static <T> T exigirNoNulo(T valor, String mensaje) {
		if (valor == null) {
			throw new IllegalArgumentException(mensaje);
		}
		return valor;
	}

	public Long getId() {
		return id;
	}

	public Long getOrganizationId() {
		return organizationId;
	}

	public Long getSesionId() {
		return sesionId;
	}

	public int getNumeroVersion() {
		return numeroVersion;
	}

	public String getMotivoClinico() {
		return motivoClinico;
	}

	public Integer getDolorEva() {
		return dolorEva;
	}

	public String getDolorZona() {
		return dolorZona;
	}

	public Lateralidad getDolorLateralidad() {
		return dolorLateralidad;
	}

	public Evolucion getEvolucion() {
		return evolucion;
	}

	public String getObjetivoSesion() {
		return objetivoSesion;
	}

	public String getLimitacionFuncional() {
		return limitacionFuncional;
	}

	public String getNotaDeCierre() {
		return notaDeCierre;
	}

	public String getRespuestaTratamiento() {
		return respuestaTratamiento;
	}

	public Tolerancia getTolerancia() {
		return tolerancia;
	}

	public String getIndicaciones() {
		return indicaciones;
	}

	public ProximaConducta getProximaConducta() {
		return proximaConducta;
	}

	public String getMotivoEnmienda() {
		return motivoEnmienda;
	}

	public Instant getRegistradaEn() {
		return registradaEn;
	}

	public Long getRegistradaPor() {
		return registradaPor;
	}
}
