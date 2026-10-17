package com.akine.activity.application;

import com.akine.activity.domain.AsistenciaActividad;
import com.akine.activity.domain.ClaseProgramada;
import com.akine.activity.domain.ResultadoAsistencia;
import com.akine.billing.spi.DevengoDeClases;
import com.akine.billing.spi.DevengoDeClases.ClaseAsistida;
import com.akine.billing.spi.DevengoDeClases.DeudaDeClase;
import com.akine.offering.spi.EsquemaDeCobro;
import com.akine.offering.spi.OfertaDirectory;
import com.akine.offering.spi.OfertaSnapshot;
import com.akine.offering.spi.PrecioDeOferta;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * La deuda de una clase asistida en una oferta {@code POR_CLASE} (U-39, RF-M18-008, RF-M19-009).
 *
 * <h2>La regla, decidida por el usuario el 17/10/2026</h2>
 *
 * <p>En una oferta {@code POR_CLASE} la deuda nace con la <b>asistencia</b>: cuando la persona
 * estuvo ({@code PRESENTE} o {@code PRESENTE_TARDE}). La ausencia <b>no</b> devenga
 * ({@code devenga_no_show = 0}, el default de V64), y el cierre de la clase, que ausenta a los
 * pendientes, tampoco. La regla mira la <b>transicion</b> entre el resultado anterior y el nuevo:
 *
 * <pre>
 *   alta presente, o correccion ausente -&gt; presente   devenga (idempotente: una viva por asistencia)
 *   correccion presente -&gt; ausente                    anula la viva (sin borrarla); con cobros, no
 *   presente &lt;-&gt; presente con demora, ausente sin previa  nada
 * </pre>
 *
 * <p>Mirar la transicion y no "estuvo y no tiene deuda" es lo que respeta una deuda que alguien
 * anulo a mano desde la cuenta corriente (una bonificacion): corregir presente a presente con
 * demora no la vuelve a devengar.
 *
 * <h2>El importe se congela</h2>
 *
 * <p>Es el precio vigente de la oferta <b>el dia de la clase en la zona de la sede</b> —el precio
 * particular que cubre esa fecha, o el de lista ({@link OfertaDirectory#precioVigenteEl})—, el
 * mismo criterio que el cierre de una sesion (RF-M16-009) y que la vigencia del pase (08.07).
 * Tomar la lista el lunes de una clase del viernes no puede cambiar lo que se debe. El pagador es
 * siempre el paciente ({@code PARTICULAR}).
 *
 * <h2>Sin precio, la asistencia se registra igual</h2>
 *
 * <p>Asistencia y economia son independientes (CA-M19-009-06): una oferta sin precio ese dia no
 * impide tomar lista. El resultado lo informa ({@code SIN_PRECIO}) y queda auditado, como el
 * {@code SIN_PASE_ELEGIBLE} de una oferta {@code POR_PACK}. Es la diferencia declarada con DP-17,
 * que bloquea el cierre de una <b>sesion</b> sin precio: la sesion es una atencion que el
 * profesional cierra, y la lista de una clase se toma con la gente adelante.
 *
 * <h2>Con cobros, no se anula</h2>
 *
 * <p>La misma regla que el resto de {@code billing}: lo cobrado se reintegra (RF-M19-010), no se
 * anula. Corregir a ausente una asistencia cuya deuda ya se cobro <b>corrige la asistencia</b> y
 * deja la deuda viva, auditada como {@code ASISTENCIA_DEUDA_CON_COBROS}; el resultado lo dice
 * ({@code CON_COBROS}). Volver a presente despues no devenga otra: la viva sigue siendo esa.
 *
 * <p>Corre en la transaccion del registro de asistencia ({@code MANDATORY}), despues del consumo de
 * creditos: el orden de locks del modulo queda {@code clase -> inscripcion -> asistencia -> pase
 * -> obligacion}. {@code billing} no toma ningun lock de {@code activity}.
 */
@Component
public class DevengoPorClaseService {

	private static final Logger log = LoggerFactory.getLogger(DevengoPorClaseService.class);

	private static final String ENTIDAD = "ASISTENCIA_ACTIVIDAD";
	private static final int LARGO_MOTIVO = 280;

	private final DevengoDeClases devengo;
	private final OfertaDirectory ofertas;
	private final AuditTrail auditTrail;

	public DevengoPorClaseService(DevengoDeClases devengo, OfertaDirectory ofertas, AuditTrail auditTrail) {
		this.devengo = devengo;
		this.ofertas = ofertas;
		this.auditTrail = auditTrail;
	}

	/**
	 * Lleva la deuda de la asistencia a lo que pide la transicion de su resultado.
	 *
	 * @param anterior el resultado antes de esta correccion; {@code null} si la asistencia se acaba
	 *                 de crear en esta transaccion
	 * @param motivo   el de la correccion, si la hubo; viaja en la anulacion
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public DevengoDeClase aplicar(
			ClaseProgramada clase, AsistenciaActividad asistencia, ResultadoAsistencia anterior,
			long actorCuentaId, String motivo, Instant ahora, LecturasDeConsumo lecturas) {

		boolean estuvo = asistencia.getResultado().estuvo();
		boolean estuvoAntes = anterior != null && anterior.estuvo();
		if (!estuvo && !estuvoAntes) {
			// La ausencia no devenga (U-39: sin no-show). Ni siquiera hace falta saber el esquema.
			return DevengoDeClase.noAplica();
		}
		long organizationId = asistencia.getOrganizationId();
		boolean porClase = lecturas.esquema(clase.getOfertaId(), () -> ofertas.esquemaDeCobro(
						organizationId, clase.getConsultorioId(), clase.getOfertaId()))
				.filter(esquema -> esquema == EsquemaDeCobro.POR_CLASE)
				.isPresent();
		if (!porClase) {
			return DevengoDeClase.noAplica();
		}
		if (estuvo == estuvoAntes) {
			return DevengoDeClase.of(DevengoDeClase.Efecto.SIN_CAMBIO);
		}
		if (estuvo) {
			return devengar(clase, asistencia, anterior != null, actorCuentaId, ahora, lecturas);
		}
		DeudaDeClase deuda = devengo.anular(organizationId, asistencia.getId(), actorCuentaId,
				motivoDeAnulacion("Asistencia corregida a ausente", motivo), ahora);
		if (deuda.efecto() == DeudaDeClase.Efecto.CON_COBROS) {
			auditarConCobros(asistencia.getOrganizationId(), asistencia.getConsultorioId(),
					asistencia.getId(), asistencia.getClaseId(), deuda, actorCuentaId, motivo, ahora);
		}
		return vista(deuda);
	}

	/**
	 * Anula las deudas de las asistencias de una clase que se acaba de cancelar (RF-M28-006 paso 7,
	 * RN-M28-009): el gemelo de la devolucion de creditos. Las que tienen cobros quedan vivas y
	 * auditadas. Lo llama {@code ClaseService.cancelar} solo cuando hubo transicion.
	 *
	 * <p>No mira el esquema de la oferta: anula lo que haya, aunque la oferta haya dejado de ser
	 * {@code POR_CLASE} despues de devengar.
	 *
	 * @return cuantas deudas se anularon
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public int anularPorCancelacion(ClaseProgramada clase, long actorCuentaId, String motivo, Instant ahora) {
		List<DeudaDeClase> deudas = devengo.anularPorCancelacion(
				clase.getOrganizationId(), clase.getId(), actorCuentaId,
				motivoDeAnulacion("Clase cancelada", motivo), ahora);
		int anuladas = 0;
		for (DeudaDeClase deuda : deudas) {
			if (deuda.efecto() == DeudaDeClase.Efecto.ANULADA) {
				anuladas++;
			} else if (deuda.efecto() == DeudaDeClase.Efecto.CON_COBROS) {
				auditarConCobros(clase.getOrganizationId(), clase.getConsultorioId(), null, clase.getId(),
						deuda, actorCuentaId, motivo, ahora);
			}
		}
		if (!deudas.isEmpty()) {
			log.info("Deudas de clase resueltas por cancelacion: claseId={} anuladas={} conCobros={}",
					clase.getId(), anuladas, deudas.size() - anuladas);
		}
		return anuladas;
	}

	private DevengoDeClase devengar(
			ClaseProgramada clase, AsistenciaActividad asistencia, boolean buscarPrevia,
			long actorCuentaId, Instant ahora, LecturasDeConsumo lecturas) {

		long organizationId = asistencia.getOrganizationId();
		long consultorioId = clase.getConsultorioId();
		long ofertaId = clase.getOfertaId();
		// El dia de la CLASE en la zona de la sede, no el dia en que alguien marca la lista.
		LocalDate dia = LocalDate.ofInstant(clase.getInicio(), lecturas.zona());
		Optional<PrecioDeOferta> precio = lecturas.precio(ofertaId, dia,
						() -> ofertas.precioVigenteEl(organizationId, consultorioId, ofertaId, dia))
				.filter(PrecioDeOferta::estaTarifada);
		if (precio.isEmpty()) {
			Map<String, String> detalles = detalles(asistencia.getId(), asistencia.getClaseId());
			detalles.put("ofertaId", String.valueOf(ofertaId));
			detalles.put("dia", dia.toString());
			auditTrail.record(new AuditEntry(
					organizationId, asistencia.getConsultorioId(), actorCuentaId,
					"ASISTENCIA_POR_CLASE_SIN_PRECIO", ENTIDAD, asistencia.getId(),
					null, null, detalles, null, null, ahora));
			log.info("Clase asistida sin precio: no se devenga. asistenciaId={} ofertaId={} dia={}",
					asistencia.getId(), ofertaId, dia);
			return DevengoDeClase.of(DevengoDeClase.Efecto.SIN_PRECIO);
		}
		String nombre = lecturas.oferta(ofertaId, () -> ofertas.find(organizationId, consultorioId, ofertaId))
				.map(OfertaSnapshot::nombreComercial)
				.orElse("Clase");
		DeudaDeClase deuda = devengo.devengar(new ClaseAsistida(
				organizationId, consultorioId, asistencia.getId(), clase.getId(), asistencia.getPersonaId(),
				ofertaId, nombre, precio.get().precioBase(), precio.get().moneda(), actorCuentaId, ahora),
				buscarPrevia);
		return vista(deuda);
	}

	private void auditarConCobros(
			long organizationId, long consultorioId, Long asistenciaId, long claseId, DeudaDeClase deuda,
			long actorCuentaId, String motivo, Instant ahora) {

		Map<String, String> detalles = detalles(asistenciaId, claseId);
		detalles.put("obligacionId", String.valueOf(deuda.obligacionId()));
		detalles.put("importe", deuda.importe().toPlainString());
		detalles.put("saldo", deuda.saldo().toPlainString());
		detalles.put("moneda", deuda.moneda());
		auditTrail.record(new AuditEntry(
				organizationId, consultorioId, actorCuentaId, "ASISTENCIA_DEUDA_CON_COBROS",
				asistenciaId == null ? "CLASE_PROGRAMADA" : ENTIDAD,
				asistenciaId == null ? claseId : asistenciaId,
				null, null, detalles, motivo, null, ahora));
	}

	private static Map<String, String> detalles(Long asistenciaId, long claseId) {
		Map<String, String> detalles = new LinkedHashMap<>();
		if (asistenciaId != null) {
			detalles.put("asistenciaId", String.valueOf(asistenciaId));
		}
		detalles.put("claseId", String.valueOf(claseId));
		return detalles;
	}

	private static String motivoDeAnulacion(String causa, String motivo) {
		String texto = motivo == null || motivo.isBlank() ? causa : causa + ": " + motivo.strip();
		return texto.length() <= LARGO_MOTIVO ? texto : texto.substring(0, LARGO_MOTIVO);
	}

	private static DevengoDeClase vista(DeudaDeClase deuda) {
		return new DevengoDeClase(
				DevengoDeClase.Efecto.valueOf(deuda.efecto().name()), deuda.obligacionId(), deuda.importe(),
				deuda.saldo(), deuda.moneda(), deuda.estado());
	}
}
