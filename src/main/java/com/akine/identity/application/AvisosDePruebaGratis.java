package com.akine.identity.application;

import com.akine.notification.spi.NotificationEnqueueCommand;
import com.akine.notification.spi.NotificationOutbox;
import com.akine.notification.spi.NotificationType;
import com.akine.organization.spi.PlatformAdminRoster;
import com.akine.organization.spi.PruebasGratis;
import com.akine.organization.spi.SolicitudDePruebaRecibida;
import com.akine.organization.spi.ZonaDeOrganizacion;
import com.akine.platform.spi.identity.AccountIdentity;
import com.akine.platform.spi.identity.AccountIdentityDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Correos de la prueba gratis (18/11/2026, docs/diseno/AKINE-landing-y-ofertas.md §2.4 y §5).
 *
 * <p>Vive en {@code identity} porque es quien conoce los correos de las cuentas y puede depender
 * a la vez de {@code organization} y de {@code notification}; {@code organization} no puede
 * encolar sin cerrar un ciclo.
 *
 * <ul>
 *   <li><b>A la plataforma</b>, cuando entra un pedido de la landing: en la misma transaccion.</li>
 *   <li><b>Al duenio</b>, a {@link #DIAS_DE_AVISO} dias del fin y cuando termina. La clave del
 *       outbox los hace idempotentes: el worker puede pasar todas las veces que quiera.</li>
 * </ul>
 */
@Service
public class AvisosDePruebaGratis {

	private static final Logger log = LoggerFactory.getLogger(AvisosDePruebaGratis.class);

	/** Cuantos dias antes del fin se avisa. */
	static final int DIAS_DE_AVISO = 7;

	/** Una prueba vencida hace mas de esto ya no se avisa: cubre un worker caido varios dias. */
	static final Duration VENTANA_DE_VENCIDAS = Duration.ofDays(7);

	private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

	private final NotificationOutbox outbox;
	private final PlatformAdminRoster plataforma;
	private final PruebasGratis pruebas;
	private final AccountIdentityDirectory cuentas;
	private final ZonaDeOrganizacion zonas;

	public AvisosDePruebaGratis(NotificationOutbox outbox, PlatformAdminRoster plataforma,
			PruebasGratis pruebas, AccountIdentityDirectory cuentas, ZonaDeOrganizacion zonas) {
		this.outbox = outbox;
		this.plataforma = plataforma;
		this.pruebas = pruebas;
		this.cuentas = cuentas;
		this.zonas = zonas;
	}

	/** Le avisa a cada administrador de plataforma que entro un pedido. */
	@EventListener
	public void alRecibirPedido(SolicitudDePruebaRecibida pedido) {
		Map<String, String> datos = new LinkedHashMap<>();
		datos.put("organizacionNombre", pedido.nombreCentro());
		datos.put("pedidoServicios", pedido.servicios());
		datos.put("pedidoSedes", pedido.sedes());
		for (AccountIdentity cuenta : identidades(plataforma.cuentasConRolDePlataforma(Instant.now()))) {
			Map<String, String> conNombre = new LinkedHashMap<>(datos);
			if (primerNombre(cuenta) != null) {
				conNombre.put("nombre", primerNombre(cuenta));
			}
			outbox.enqueue(new NotificationEnqueueCommand(NotificationType.PEDIDO_DE_PRUEBA_RECIBIDO,
					cuenta.email(), "pedido-prueba-" + pedido.solicitudId() + "-" + cuenta.accountId(),
					null, null, conNombre));
		}
	}

	/**
	 * Encola los avisos de las pruebas que terminan en los proximos {@link #DIAS_DE_AVISO} dias y
	 * de las que terminaron hace menos de {@link #VENTANA_DE_VENCIDAS}. Devuelve cuantos centros
	 * miro.
	 */
	@Transactional
	public int avisarVencimientos(Instant ahora) {
		int centros = 0;
		for (PruebasGratis.PruebaEnCurso prueba : pruebas.queTerminanEntre(
				ahora.minus(VENTANA_DE_VENCIDAS), ahora.plus(Duration.ofDays(DIAS_DE_AVISO)))) {
			boolean vencida = !prueba.pruebaHasta().isAfter(ahora);
			NotificationType tipo = vencida ? NotificationType.PRUEBA_VENCIDA : NotificationType.PRUEBA_POR_VENCER;
			String prefijo = vencida ? "prueba-vencida-" : "prueba-por-vencer-";
			String fin = FECHA.format(prueba.pruebaHasta().atZone(zonas.de(prueba.organizationId())));
			for (AccountIdentity cuenta : identidades(pruebas.administradores(prueba.organizationId()))) {
				Map<String, String> datos = new LinkedHashMap<>();
				if (primerNombre(cuenta) != null) {
					datos.put("nombre", primerNombre(cuenta));
				}
				datos.put("organizacionNombre", prueba.nombreCentro());
				datos.put("pruebaVencimiento", fin);
				outbox.enqueue(new NotificationEnqueueCommand(tipo, cuenta.email(),
						prefijo + prueba.organizationId() + "-" + cuenta.accountId(),
						prueba.organizationId(), null, datos));
			}
			centros++;
		}
		if (centros > 0) {
			log.info("Avisos de prueba gratis revisados: centros={}", centros);
		}
		return centros;
	}

	private Collection<AccountIdentity> identidades(Collection<Long> accountIds) {
		return accountIds.isEmpty() ? java.util.List.of() : cuentas.identidadesDe(accountIds).values();
	}

	private static String primerNombre(AccountIdentity cuenta) {
		String nombre = cuenta.nombreCompleto();
		if (nombre == null || nombre.isBlank()) {
			return null;
		}
		return nombre.strip().split("\\s+")[0];
	}
}
