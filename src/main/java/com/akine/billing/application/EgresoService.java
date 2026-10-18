package com.akine.billing.application;

import com.akine.billing.domain.Egreso;
import com.akine.billing.domain.TipoBeneficiario;
import com.akine.billing.domain.exception.BeneficiarioNoVinculadoException;
import com.akine.billing.domain.exception.EgresoComprobanteDuplicadoException;
import com.akine.billing.domain.exception.EgresoNoEditableException;
import com.akine.billing.domain.exception.EgresoNotAccessibleException;
import com.akine.billing.domain.exception.OfertaDelEgresoNoAccesibleException;
import com.akine.billing.domain.port.EgresoRepositoryPort;
import com.akine.billing.domain.port.PagoEgresoRepositoryPort;
import com.akine.organization.spi.ConsultorioMembershipDirectory;
import com.akine.organization.spi.ConsultorioMembershipSnapshot;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.offering.spi.OfertaDirectory;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import com.akine.platform.spi.identity.AccountIdentity;
import com.akine.platform.spi.identity.AccountIdentityDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * El compromiso: alta, edicion del borrador, confirmacion, anulacion y consulta (M22).
 *
 * <h2>Esta clase no mueve un peso</h2>
 *
 * <p>Ninguna de sus operaciones toca la caja, y es el punto del diseno. Un egreso es <b>lo que se
 * debe</b>; lo que mueve plata es el pago, y eso vive en {@link PagoEgresoService}. Por eso
 * confirmar no genera ningun movimiento y anular tampoco: un compromiso que solo se debia nunca
 * movio plata.
 *
 * <p>Es la misma separacion que 07.01 y 07.02 sostienen del lado del paciente, y se rompe igual de
 * facil: bastaria que confirmar asentara el egreso en el cajon para que <b>una liquidacion debida y
 * no pagada dejara de poder existir</b> y para que el pago parcial fuera irrepresentable.
 */
@Service
public class EgresoService {

	private static final Logger log = LoggerFactory.getLogger(EgresoService.class);

	private static final int LIMITE_MAXIMO = 200;

	private final EgresoRepositoryPort egresos;
	private final PagoEgresoRepositoryPort pagos;
	private final ConsultorioMembershipDirectory memberships;
	private final AccountIdentityDirectory identidades;
	private final CajaAcceso acceso;
	private final AuditTrail auditTrail;
	private final OfertaDirectory ofertas;

	public EgresoService(
			EgresoRepositoryPort egresos,
			PagoEgresoRepositoryPort pagos,
			ConsultorioMembershipDirectory memberships,
			AccountIdentityDirectory identidades,
			CajaAcceso acceso,
			AuditTrail auditTrail,
			OfertaDirectory ofertas) {

		this.egresos = egresos;
		this.pagos = pagos;
		this.memberships = memberships;
		this.identidades = identidades;
		this.acceso = acceso;
		this.auditTrail = auditTrail;
		this.ofertas = ofertas;
	}

	/**
	 * Carga un egreso nuevo. Nace {@code BORRADOR} (RF-M22-001).
	 *
	 * <p>La idempotencia se evalua <b>antes</b> de resolver el beneficiario y de mirar el
	 * comprobante, por lo mismo que 06.05 la evalua antes de pedir numero: un reintento no puede
	 * producir efectos nuevos ni errores nuevos.
	 *
	 * @throws BeneficiarioNoVinculadoException     la membership no esta vigente (409)
	 * @throws EgresoComprobanteDuplicadoException  ese comprobante de ese beneficiario ya existe (409)
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public EgresoView registrar(OperatingActor actor, long consultorioId, EgresoCommand command) {
		long organizationId = CajaAcceso.exigirContexto(actor);
		acceso.exigirSedeDelTenant(organizationId, consultorioId);
		acceso.exigirOperarCaja(actor, organizationId, consultorioId);

		Optional<Egreso> yaRegistrado = command.idempotencyKey() == null
				? Optional.empty()
				: egresos.findByIdempotencyKey(organizationId, command.idempotencyKey());
		if (yaRegistrado.isPresent()) {
			Egreso existente = yaRegistrado.get();
			if (!command.huella(consultorioId).equals(existente.getRequestHash())) {
				throw new IdempotencyKeyConflictException(command.idempotencyKey());
			}
			return EgresoView.de(existente);
		}

		Beneficiario beneficiario = resolverBeneficiario(organizationId, command);
		exigirOfertaDeLaSede(organizationId, consultorioId, command.ofertaId());
		exigirComprobanteNoDuplicado(
				organizationId, beneficiario.clave(),
				command.comprobanteTipo(), command.comprobanteNumero(), null);

		Instant ahora = Instant.now();
		Egreso egreso = new Egreso(
				organizationId, consultorioId,
				command.categoria(), command.tipoBeneficiario(),
				command.beneficiarioMembershipId(), beneficiario.clave(),
				beneficiario.nombre(), command.beneficiarioDocumento(),
				command.periodoDesde(), command.periodoHasta(),
				command.concepto(), command.importeTotal(), command.moneda(),
				ahora, actor.accountId(),
				command.idempotencyKey(),
				command.idempotencyKey() == null ? null : command.huella(consultorioId));

		egreso.editarBorrador(
				command.categoria(), command.periodoDesde(), command.periodoHasta(),
				command.concepto(), command.importeTotal(),
				command.comprobanteTipo(), command.comprobanteNumero(), command.comprobanteFecha());
		egreso.imputarAOferta(command.ofertaId());

		Egreso guardado = egresos.save(egreso);
		auditar(actor, AuditEvents.EGRESO_REGISTRADO, guardado, null, ahora);

		log.info("Egreso registrado: egresoId={} categoria={} beneficiario={} importe={}",
				guardado.getId(), command.categoria(), beneficiario.clave(),
				command.importeTotal());

		return EgresoView.de(guardado);
	}

	/**
	 * Corrige el borrador (RF-M22-001).
	 *
	 * <p><b>Solo mientras sea borrador.</b> Un importe que cambiara debajo de pagos ya asentados
	 * haria que el saldo dejara de reconciliar con el ledger de caja sin que nada fallara.
	 *
	 * <p><b>Con control optimista explicito.</b> Dos administrativos corrigiendo el mismo borrador
	 * no pueden pisarse en silencio: el que trae una version distinta de la vigente recibe 409
	 * {@code concurrent-modification} (DP-21). La comparacion es contra la version leida en esta
	 * transaccion; si otro commitea en el medio, el {@code @Version} del UPDATE lo frena igual.
	 *
	 * @throws EgresoNoEditableException el egreso ya no es borrador (409)
	 * @throws OptimisticLockingFailureException {@code expectedVersion} no es la vigente (409)
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public EgresoView editar(
			OperatingActor actor, long consultorioId, long egresoId, EgresoCommand command,
			long expectedVersion) {

		long organizationId = CajaAcceso.exigirContexto(actor);
		acceso.exigirSedeDelTenant(organizationId, consultorioId);
		acceso.exigirOperarCaja(actor, organizationId, consultorioId);

		Egreso egreso = exigirEgreso(organizationId, consultorioId, egresoId);
		if (egreso.getVersion() != expectedVersion) {
			throw new OptimisticLockingFailureException(
					"El egreso " + egresoId + " cambio desde que se leyo: version "
							+ expectedVersion + " contra " + egreso.getVersion());
		}
		if (!egreso.getEstado().esEditable()) {
			throw new EgresoNoEditableException(egresoId, egreso.getEstado().name());
		}
		exigirOfertaDeLaSede(organizationId, consultorioId, command.ofertaId());

		exigirComprobanteNoDuplicado(
				organizationId, egreso.getBeneficiarioClave(),
				command.comprobanteTipo(), command.comprobanteNumero(), egresoId);

		egreso.editarBorrador(
				command.categoria(), command.periodoDesde(), command.periodoHasta(),
				command.concepto(), command.importeTotal(),
				command.comprobanteTipo(), command.comprobanteNumero(), command.comprobanteFecha());
		egreso.imputarAOferta(command.ofertaId());

		// El beneficiario NO se edita: cambiarlo seria otro egreso, no una correccion de este, y
		// dejaria el comprobante ya validado colgando de una clave distinta.
		Egreso guardado = egresos.save(egreso);
		egresos.flush();
		return EgresoView.de(guardado);
	}

	/**
	 * Punto de no retorno: el egreso deja de editarse y empieza a admitir pagos (RF-M22-001).
	 *
	 * <p>Exige comprobante, y la validacion vive en la entidad. <b>Confirmar no mueve la caja</b>.
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public EgresoView confirmar(OperatingActor actor, long consultorioId, long egresoId) {
		long organizationId = CajaAcceso.exigirContexto(actor);
		acceso.exigirSedeDelTenant(organizationId, consultorioId);
		acceso.exigirOperarCaja(actor, organizationId, consultorioId);

		Egreso egreso = exigirEgreso(organizationId, consultorioId, egresoId);
		Instant ahora = Instant.now();
		egreso.confirmar(ahora, actor.accountId());

		Egreso guardado = egresos.save(egreso);
		auditar(actor, AuditEvents.EGRESO_CONFIRMADO, guardado, null, ahora);

		log.info("Egreso confirmado: egresoId={} importe={}", egresoId, egreso.getImporteTotal());
		return EgresoView.de(guardado);
	}

	/**
	 * Anula el compromiso (RF-M22-005). <b>No toca la caja</b>: nunca movio plata.
	 *
	 * <p>Se rechaza si queda algun pago vigente. Primero se anulan los pagos —que es lo que devuelve
	 * la plata al cajon— y despues el compromiso.
	 *
	 * <p><b>Compite con el pago y uno de los dos pierde</b>: por eso lee con
	 * {@code SELECT ... FOR UPDATE} en {@code READ_COMMITTED}, que ve el saldo ya commiteado por un
	 * pago concurrente en lugar de una foto previa a el.
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public EgresoView anular(
			OperatingActor actor, long consultorioId, long egresoId, String motivo) {

		long organizationId = CajaAcceso.exigirContexto(actor);
		acceso.exigirSedeDelTenant(organizationId, consultorioId);
		acceso.exigirOperarCaja(actor, organizationId, consultorioId);

		// Con lock de fila y no con la lectura comun: "no tiene pagos" se decide sobre el saldo, y un
		// pago que commiteara entre esta lectura y el flush no se veria. Sin el lock el egreso
		// quedaba ANULADO con un pago CONFIRMADO vigente (web #94). Ver el puerto.
		Egreso egreso = egresos.findByIdInScopeParaEscribir(organizationId, consultorioId, egresoId)
				.orElseThrow(() -> new EgresoNotAccessibleException(egresoId));
		Instant ahora = Instant.now();
		egreso.anular(motivo, ahora, actor.accountId());

		Egreso guardado = egresos.save(egreso);
		auditar(actor, AuditEvents.EGRESO_ANULADO, guardado, motivo, ahora);

		log.info("Egreso anulado: egresoId={} motivo={}", egresoId, motivo);
		return EgresoView.de(guardado);
	}

	/** El detalle, con sus pagos — incluidos los anulados (RF-M22-004). */
	@Transactional(readOnly = true)
	public EgresoView ver(OperatingActor actor, long consultorioId, long egresoId) {
		long organizationId = CajaAcceso.exigirContexto(actor);
		acceso.exigirSedeDelTenant(organizationId, consultorioId);
		acceso.exigirOperarCaja(actor, organizationId, consultorioId);

		Egreso egreso = exigirEgreso(organizationId, consultorioId, egresoId);
		return EgresoView.de(
				egreso,
				pagos.findDelEgreso(organizationId, egresoId).stream()
						.map(PagoEgresoView::de)
						.toList());
	}

	/**
	 * La bandeja, filtrada por fecha, categoria, beneficiario y estado (RF-M22-004).
	 *
	 * <p>Sin los pagos de cada fila: traerlos seria el N+1 del primer listado que lo use.
	 */
	@SuppressWarnings("checkstyle:ParameterNumber")
	@Transactional(readOnly = true)
	public List<EgresoView> buscar(
			OperatingActor actor, long consultorioId, String estado, String categoria,
			Long beneficiarioMembershipId, LocalDate desde, LocalDate hasta,
			int limite, int desplazamiento) {

		long organizationId = CajaAcceso.exigirContexto(actor);
		ConsultorioSnapshot sede = acceso.exigirSedeDelTenant(organizationId, consultorioId);
		acceso.exigirOperarCaja(actor, organizationId, consultorioId);

		String clave = beneficiarioMembershipId == null
				? null
				: claveDeColaborador(beneficiarioMembershipId);

		return egresos.buscar(
						organizationId, consultorioId, estado, categoria, clave,
						CajaAcceso.inicioDelDia(sede, desde), CajaAcceso.finDelDia(sede, hasta),
						Math.min(Math.max(limite, 1), LIMITE_MAXIMO), Math.max(desplazamiento, 0))
				.stream()
				.map(EgresoView::de)
				.toList();
	}

	// =================================================================================
	// Interno
	// =================================================================================

	/** Cross-tenant es <b>404 y nunca 403</b>: un 403 confirma que existe. */
	private Egreso exigirEgreso(long organizationId, long consultorioId, long egresoId) {
		return egresos.findByIdInScope(organizationId, consultorioId, egresoId)
				.orElseThrow(() -> new EgresoNotAccessibleException(egresoId));
	}

	/**
	 * Resuelve quien cobra y congela su nombre.
	 *
	 * <p>El nombre se copia una sola vez y no se vuelve a resolver nunca: una liquidacion de
	 * septiembre tiene que poder leerse en marzo sin depender de que la membership siga existiendo.
	 * Mismo criterio que {@code obligacion.snapshot_nombre}.
	 *
	 * <p>La vigencia de la membership se exige <b>aca y en ningun otro lado</b>: confirmar y pagar
	 * un egreso cuyo beneficiario ya se desvinculo tiene que funcionar, porque si el profesional se
	 * fue el 30 de septiembre el centro le sigue debiendo septiembre.
	 */
	private Beneficiario resolverBeneficiario(long organizationId, EgresoCommand command) {
		if (command.tipoBeneficiario() != TipoBeneficiario.COLABORADOR) {
			String nombre = command.beneficiarioNombre();
			if (nombre == null || nombre.isBlank()) {
				throw new IllegalArgumentException(
						"Un beneficiario externo necesita un nombre: si no, el egreso no dice a quien se le pago");
			}
			String identificador = command.beneficiarioDocumento() == null
					|| command.beneficiarioDocumento().isBlank()
					? nombre : command.beneficiarioDocumento();
			return new Beneficiario(
					"E:" + normalizar(identificador), nombre.trim());
		}

		Long membershipId = command.beneficiarioMembershipId();
		ConsultorioMembershipSnapshot vinculo = memberships.find(organizationId, membershipId)
				.filter(m -> m.validAt(Instant.now()))
				.orElseThrow(() -> new BeneficiarioNoVinculadoException(membershipId));

		AccountIdentity identidad = identidades
				.identidadesDe(List.of(vinculo.accountId()))
				.get(vinculo.accountId());

		// Sin identidad resuelta el egreso igual tiene que poder cargarse: dejar de pagarle a
		// alguien porque su cuenta no resuelve seria peor que mostrar un nombre generico.
		String nombre = identidad == null
				? "Colaborador " + membershipId
				: identidad.nombreCompleto();

		return new Beneficiario(claveDeColaborador(membershipId), nombre);
	}

	/**
	 * La oferta a la que se imputa el costo tiene que ser de esta sede (DP-39). {@code find} filtra por
	 * organizacion y sede, asi que una de otro tenant o de otra sede no aparece: <b>404</b>. Una oferta
	 * dada de baja si se acepta: un egreso de septiembre puede ser costo de un servicio que se dejo de
	 * prestar en octubre.
	 */
	private void exigirOfertaDeLaSede(long organizationId, long consultorioId, Long ofertaId) {
		if (ofertaId != null && ofertas.find(organizationId, consultorioId, ofertaId).isEmpty()) {
			throw new OfertaDelEgresoNoAccesibleException(ofertaId);
		}
	}

	private static String claveDeColaborador(long membershipId) {
		return "M:" + membershipId;
	}

	/**
	 * Normaliza el identificador del externo para que la clave sea estable.
	 *
	 * <p>Sin esto, {@code "Estudio Perez"} y {@code "estudio perez "} serian dos beneficiarios
	 * distintos y el unique del comprobante no detectaria la factura duplicada.
	 */
	private static String normalizar(String texto) {
		String limpio = texto.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
		return limpio.length() > 78 ? limpio.substring(0, 78) : limpio;
	}

	/**
	 * El caso borde "factura externa duplicada", con un 409 legible.
	 *
	 * <p>El unique lo impide igualmente; esto existe para no dejar que reviente una constraint, que
	 * ademas dejaria la transaccion marcada para rollback y haria fallar cualquier consulta
	 * posterior — la trampa que este repositorio ya pago cuatro veces.
	 *
	 * @param egresoIdPropio el egreso que se esta editando, para no chocar consigo mismo
	 */
	private void exigirComprobanteNoDuplicado(
			long organizationId, String beneficiarioClave,
			String comprobanteTipo, String comprobanteNumero, Long egresoIdPropio) {

		if (comprobanteTipo == null || comprobanteTipo.isBlank()
				|| comprobanteNumero == null || comprobanteNumero.isBlank()) {
			return;
		}
		egresos.findVigentePorComprobante(
						organizationId, beneficiarioClave, comprobanteTipo, comprobanteNumero)
				.filter(otro -> !otro.getId().equals(egresoIdPropio))
				.ifPresent(otro -> {
					throw new EgresoComprobanteDuplicadoException(
							comprobanteTipo, comprobanteNumero, otro.getId());
				});
	}

	private void auditar(
			OperatingActor actor, String eventType, Egreso egreso, String motivo, Instant ahora) {

		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("estado", egreso.getEstado().name());
		detalles.put("categoria", egreso.getCategoria().name());
		detalles.put("beneficiario", egreso.getBeneficiarioClave());
		detalles.put("importeTotal", egreso.getImporteTotal().toPlainString());
		detalles.put("saldoPendiente", egreso.getSaldoPendiente().toPlainString());
		if (egreso.getOfertaId() != null) {
			detalles.put("ofertaId", egreso.getOfertaId().toString());
		}

		auditTrail.record(new AuditEntry(
				actor.contextOrganizationId(),
				actor.consultorioId(),
				actor.accountId(),
				eventType,
				AuditEvents.ENTITY_EGRESO,
				egreso.getId(),
				null,
				null,
				detalles,
				motivo,
				AuditEvents.correlationId(),
				ahora));
	}

	/** Quien cobra, ya resuelto: la clave estable y el nombre que se congela. */
	private record Beneficiario(String clave, String nombre) {
	}
}
