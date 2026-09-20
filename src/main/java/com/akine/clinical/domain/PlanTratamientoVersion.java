package com.akine.clinical.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * El contenido de una version de {@link PlanTratamiento} (RF-M11-004).
 *
 * <h2>Los items cuelgan de esto, no del plan</h2>
 *
 * <p>Es la decision que ordena la etapa y hay que mirarla de frente. Si {@link PlanItem} colgara
 * del plan, cambiar la cantidad estimada de una practica <b>reescribiria el pasado</b>: el avance
 * de hace dos meses se recalcularia contra un plan que entonces no existia. Colgados de la version,
 * cada version tiene su foto de cantidades.
 *
 * <p>La consecuencia hay que aceptarla y mostrarla: el avance <b>"8 de 20" de la version 1 y el
 * "8 de 24" de la version 2 son dos numeros distintos y los dos correctos</b>. Por eso la consulta
 * de avance recibe el numero de version, y la pantalla tiene que decir de cual habla.
 *
 * <h2>Esta fila no cambia y no se da de baja. Nunca</h2>
 *
 * <p>No hay {@code active}, no hay {@code deleted_at}, no hay {@code @Version} y no hay ni un
 * metodo que modifique el contenido — igual que {@link EntradaClinicaVersion} y por lo mismo: una
 * version es un <b>hecho pasado</b>, y darla de baja seria reescribir historia clinica (ADR-0011).
 *
 * <p>Con una sola excepcion declarada: un plan en <b>BORRADOR</b> se edita en el lugar, sobre su
 * version 1, sin versionar (RF-M11-004). Un plan que nunca se activo no tiene historia que
 * preservar, y versionar cada tecleo llenaria la tabla de ruido. Lo permite {@link #editarEnBorrador},
 * que el servicio solo invoca mientras el plan esta en BORRADOR.
 *
 * <h2>La frecuencia es una propuesta, no una agenda</h2>
 *
 * <p>{@link #frecuenciaSemanal} y {@link #duracionSemanas} son la regla de recurrencia que el plan
 * <b>sugiere</b>. Este modulo no crea turnos, no crea series y no importa {@code scheduling} ni por
 * el {@code spi}: un plan que agenda es la regla maestra 2 rota (challenge seccion 4.2).
 */
@Entity
@Table(name = "plan_tratamiento_version")
public class PlanTratamientoVersion extends MarcaTemporal {

	/** Largo de {@code objetivos} e {@code indicaciones}, tal como los declara {@code V49}. */
	public static final int TEXTO_MAXIMO = 2000;

	/** Largo del motivo de modificacion, tal como lo declara {@code V49}. */
	public static final int MOTIVO_MAXIMO = 500;

	/**
	 * Tope de sesiones por semana que el CHECK de {@code V49} admite.
	 *
	 * <p>Tres por dia es mucho mas de lo que un tratamiento real propone; esta para que un tipeo
	 * de mas no quede escrito como decision clinica.
	 */
	public static final int FRECUENCIA_MAXIMA = 21;

	/** Tope de semanas que el CHECK de {@code V49} admite: diez años de tratamiento. */
	public static final int DURACION_MAXIMA = 520;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/**
	 * Tenant propietario. Es derivable del plan y se guarda <b>igual</b>.
	 *
	 * <p>Derivarlo obligaria a un join para filtrar por tenant, y el dia que alguien escriba la
	 * consulta sin ese join tiene una fuga que ningun test de la etapa ve, porque los tests de una
	 * etapa corren con un solo tenant.
	 */
	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "plan_tratamiento_id", nullable = false, updatable = false)
	private Long planTratamientoId;

	/**
	 * Orden de la version dentro del plan. Sale del contador de la cabecera, nunca de un
	 * {@code MAX + 1}.
	 */
	@Column(name = "numero_version", nullable = false, updatable = false)
	private Integer numeroVersion;

	/** Contenido clinico. No se copia a la auditoria ni al historial de estados. */
	@Column(name = "objetivos", nullable = false, length = TEXTO_MAXIMO)
	private String objetivos;

	@Column(name = "indicaciones", length = TEXTO_MAXIMO)
	private String indicaciones;

	@Column(name = "frecuencia_semanal")
	private Integer frecuenciaSemanal;

	@Column(name = "duracion_semanas")
	private Integer duracionSemanas;

	/**
	 * Por que se modifico. {@code null} en la version 1 —no modifica nada— y obligatorio despues.
	 *
	 * <p>Lo exige tambien {@code ck_plan_version_motivo_de_modificacion}. Sin motivo, una
	 * modificacion es indistinguible de una correccion de tipeo y el historial deja de servir para
	 * lo unico que sirve.
	 */
	@Column(name = "motivo_modificacion", updatable = false, length = MOTIVO_MAXIMO)
	private String motivoModificacion;

	@Column(name = "registrada_en", nullable = false, updatable = false)
	private Instant registradaEn;

	/**
	 * Autor de <b>esta</b> version, no del plan.
	 *
	 * <p>Quien modifica no suele ser quien escribio la original, y guardarlo solo en la cabecera
	 * perderia exactamente el dato por el que existe el historial.
	 */
	@Column(name = "registrada_por", nullable = false, updatable = false)
	private Long registradaPor;

	protected PlanTratamientoVersion() {
		// Requerido por JPA.
	}

	@SuppressWarnings("checkstyle:ParameterNumber")
	public PlanTratamientoVersion(
			Long organizationId,
			Long planTratamientoId,
			int numeroVersion,
			String objetivos,
			String indicaciones,
			Integer frecuenciaSemanal,
			Integer duracionSemanas,
			String motivoModificacion,
			Instant registradaEn,
			Long registradaPor) {

		this.organizationId =
				exigirNoNulo(organizationId, "La version pertenece a una organizacion");
		this.planTratamientoId =
				exigirNoNulo(planTratamientoId, "La version cuelga siempre de un plan");
		if (numeroVersion < 1) {
			throw new IllegalArgumentException("El numero de version arranca en 1");
		}
		this.numeroVersion = numeroVersion;
		aplicarContenido(objetivos, indicaciones, frecuenciaSemanal, duracionSemanas);

		String motivo = vacioEsNulo(motivoModificacion);
		if (numeroVersion > 1 && motivo == null) {
			throw new IllegalArgumentException(
					"Modificar un plan vigente exige declarar por que");
		}
		this.motivoModificacion = numeroVersion == 1 ? null : exigirLargo(
				motivo, MOTIVO_MAXIMO, "El motivo");
		this.registradaEn = exigirNoNulo(registradaEn, "La version registra siempre su instante");
		this.registradaPor = exigirNoNulo(registradaPor, "La version registra siempre a su autor");
	}

	/**
	 * Reescribe el contenido <b>sin</b> crear una version nueva.
	 *
	 * <p><b>La unica escritura destructiva del modulo clinico, y esta acotada a proposito.</b> Solo
	 * se invoca sobre la version 1 de un plan en BORRADOR: un plan que nunca se activo no tiene
	 * historia que preservar (RF-M11-004). En cuanto el plan pasa a ACTIVO, el servicio deja de
	 * llamar a esto y toda modificacion crea una fila nueva.
	 *
	 * <p>No toca {@link #motivoModificacion} ni {@link #registradaPor}: la version 1 no lleva motivo
	 * y su autor es quien creo el plan.
	 */
	public void editarEnBorrador(
			String objetivos,
			String indicaciones,
			Integer frecuenciaSemanal,
			Integer duracionSemanas) {

		if (numeroVersion != 1) {
			throw new IllegalStateException(
					"Solo la version 1 de un plan en borrador se edita en el lugar");
		}
		aplicarContenido(objetivos, indicaciones, frecuenciaSemanal, duracionSemanas);
	}

	private void aplicarContenido(
			String objetivos,
			String indicaciones,
			Integer frecuenciaSemanal,
			Integer duracionSemanas) {

		String limpios = vacioEsNulo(objetivos);
		if (limpios == null) {
			throw new IllegalArgumentException(
					"El plan exige declarar sus objetivos terapeuticos");
		}
		this.objetivos = exigirLargo(limpios, TEXTO_MAXIMO, "Los objetivos");
		this.indicaciones = exigirLargo(vacioEsNulo(indicaciones), TEXTO_MAXIMO, "Las indicaciones");
		this.frecuenciaSemanal =
				exigirRango(frecuenciaSemanal, FRECUENCIA_MAXIMA, "La frecuencia semanal");
		this.duracionSemanas =
				exigirRango(duracionSemanas, DURACION_MAXIMA, "La duracion en semanas");
	}

	private static <T> T exigirNoNulo(T valor, String mensaje) {
		if (valor == null) {
			throw new IllegalArgumentException(mensaje);
		}
		return valor;
	}

	private static String exigirLargo(String valor, int tope, String que) {
		if (valor != null && valor.length() > tope) {
			throw new IllegalArgumentException(
					que + " no pueden superar los " + tope + " caracteres");
		}
		return valor;
	}

	private static Integer exigirRango(Integer valor, int tope, String que) {
		if (valor == null) {
			return null;
		}
		if (valor <= 0 || valor > tope) {
			throw new IllegalArgumentException(que + " tiene que estar entre 1 y " + tope);
		}
		return valor;
	}

	private static String vacioEsNulo(String valor) {
		return valor == null || valor.isBlank() ? null : valor.strip();
	}

	public Long getId() {
		return id;
	}

	public Long getOrganizationId() {
		return organizationId;
	}

	public Long getPlanTratamientoId() {
		return planTratamientoId;
	}

	public Integer getNumeroVersion() {
		return numeroVersion;
	}

	public String getObjetivos() {
		return objetivos;
	}

	public String getIndicaciones() {
		return indicaciones;
	}

	public Integer getFrecuenciaSemanal() {
		return frecuenciaSemanal;
	}

	public Integer getDuracionSemanas() {
		return duracionSemanas;
	}

	public String getMotivoModificacion() {
		return motivoModificacion;
	}

	public Instant getRegistradaEn() {
		return registradaEn;
	}

	public Long getRegistradaPor() {
		return registradaPor;
	}
}
