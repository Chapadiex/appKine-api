package com.akine.contracting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Plan de cobertura de un financiador (M15).
 *
 * <h2>RN-M15-001 sostenida por la estructura</h2>
 *
 * <p>"Un plan pertenece a un financiador": {@link #financiadorId} es {@code NOT NULL} y
 * {@code updatable = false}. Lo segundo es lo que hace que la regla valga tambien <i>despues</i>
 * del alta — un plan que pudiera mudarse de financiador reescribiria el significado de todas las
 * coberturas que ya lo referencian, sin que ninguna de ellas participe de esa edicion.
 *
 * <h2>Ciclo de vida y vigencia son DOS cosas, y esa es la distincion central de la clase</h2>
 *
 * <pre>
 *   CICLO DE VIDA   active / deleted_at.  Administrativo. "Este plan ya no se usa mas."
 *   VIGENCIA        vigencia_desde / hasta. Operativo.   "Este plan vale entre estas fechas."
 * </pre>
 *
 * <p>Se pueden combinar de las cuatro maneras y las cuatro tienen sentido. El caso borde que la
 * etapa nombra —"plan sin nuevas altas pero con pacientes vigentes"— es justamente uno de ellos:
 * un plan cuya {@link #vigenciaHasta} ya paso <b>no se ofrece</b> para una cobertura nueva
 * (RN-M15-002) y sin embargo las coberturas firmadas mientras estuvo vigente siguen resolviendo,
 * porque esas guardan su propia copia congelada. La misma separacion que {@code Espacio} (V19),
 * {@code ProfesionalDisponibilidad} (V23) y {@code OfertaServicioConsultorio} (V24) ya usan.
 *
 * <h2>{@link #vigenciaHasta} es INCLUSIVA</h2>
 *
 * <p>Es el ultimo dia en que el plan se puede elegir. Se declara porque V24 dejo una ambiguedad
 * real: su cabecera llama "EXCLUSIVA" a {@code oferta.vigencia_hasta} y {@code OfertaSnapshot}
 * la evalua inclusiva. Aca las dos mitades dicen lo mismo, {@code ck_plan_vigencia_coherente}
 * admite {@code hasta = desde} —un plan que vale un solo dia es un estado real— y
 * {@link #vigenteEl(LocalDate)} lo hace ejecutable.
 *
 * <h2>El copago es {@code BigDecimal} y jamas {@code double}</h2>
 *
 * <p>AGENT.md §5, y ArchUnit lo verifica ({@code sin_punto_flotante_en_el_dominio}). {@code null}
 * significa "sin copago declarado", que <b>no</b> es lo mismo que cero: cero es una decision
 * tomada, {@code null} es una decision que nadie tomo todavia. Viaja siempre con
 * {@link #moneda}, porque un importe sin moneda no es un importe.
 */
@Entity
@Table(name = "plan_cobertura")
public class PlanCobertura extends PlanBase {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	/** RN-M15-001. Inmutable: ver la cabecera de la clase. */
	@Column(name = "financiador_id", nullable = false, updatable = false)
	private Long financiadorId;

	/**
	 * Plan del catalogo global del que se copio este plan al adoptar su financiador (DP-43), o
	 * {@code null} si el centro lo cargo a mano. INFORMATIVO.
	 */
	@Column(name = "catalogo_id", updatable = false)
	private Long catalogoId;

	protected PlanCobertura() {
		// Requerido por JPA.
	}

	public PlanCobertura(
			Long organizationId,
			Long financiadorId,
			String codigo,
			String nombre,
			String descripcion,
			LocalDate vigenciaDesde,
			LocalDate vigenciaHasta,
			boolean requiereAutorizacion,
			boolean requiereCredencial,
			BigDecimal copago,
			String moneda) {

		super(codigo, nombre, descripcion, vigenciaDesde, vigenciaHasta, requiereAutorizacion,
				requiereCredencial, copago, moneda);
		this.organizationId =
				exigirNoNulo(organizationId, "El plan pertenece siempre a una organizacion");
		this.financiadorId =
				exigirNoNulo(financiadorId, "Un plan pertenece siempre a un financiador (RN-M15-001)");
	}

	/**
	 * La copia de un plan del catalogo global bajo el financiador que el centro acaba de adoptar
	 * (DP-43). Copia los datos tal como estan, vigencia incluida: un plan vencido se copia vencido
	 * y no se ofrece para selecciones nuevas, igual que uno cargado a mano.
	 */
	public static PlanCobertura adoptado(
			Long organizationId, Long financiadorId, PlanCatalogo origen) {

		PlanCobertura copia = new PlanCobertura(
				organizationId,
				financiadorId,
				origen.getCodigo(),
				origen.getNombre(),
				origen.getDescripcion(),
				origen.getVigenciaDesde(),
				origen.getVigenciaHasta(),
				origen.isRequiereAutorizacion(),
				origen.isRequiereCredencial(),
				origen.getCopago(),
				origen.getMoneda());
		copia.catalogoId = exigirNoNulo(origen.getId(), "El plan del catalogo no tiene id");
		return copia;
	}

	public Long getId() {
		return id;
	}

	public Long getOrganizationId() {
		return organizationId;
	}

	public Long getFinanciadorId() {
		return financiadorId;
	}

	public Long getCatalogoId() {
		return catalogoId;
	}
}
