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
import java.util.OptionalInt;

/**
 * Correos de la prueba gratis (18/11/2026, docs/diseno/AKINE-landing-y-ofertas.md §2.4 y §5).
 *
 * <p>Vive en {@code identity} porque es quien conoce los correos de las cuentas y puede depender
 * a la vez de {@code organization} y de {@code notification}; {@code organization} no puede
 * encolar sin cerrar un ciclo.
 *
 * <ul>
 *   <li><b>A la plataforma</b>, cuando entra un pedido de la landing: en la misma transaccion.</li>
 *   <li><b>Al duenio</b>, a 15, 10, 5, 2 y 1 dias del fin ({@link HitosDeAviso}, desde el 19/11/2026;
 *       antes era uno solo a 7 dias) y cuando termina. La clave del outbox lleva el hito y el fin de
 *       la prueba, asi que el worker puede pasar todas las veces que quiera y una prueba que se
 *       extiende vuelve a avisar.</li>
 * </ul>
 */
@Service
public class AvisosDePruebaGratis {

	private static final Logger log = LoggerFactory.getLogger(AvisosDePruebaGratis.class);

	/** Una prueba vencida hace mas de esto ya no se avisa: cubre un worker caido varios dias. */
	static final Duration VENTANA_DE_VENCIDAS = Duration.ofDays(7);

	static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

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
		for (AccountIdentity cuenta : identidades(cuentas, plataforma.cuentasConRolDePlataforma(Instant.now()))) {
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
	 * Encola los avisos de las pruebas que terminan en los proximos 15 dias y de las que terminaron
	 * hace menos de {@link #VENTANA_DE_VENCIDAS}. Devuelve cuantos centros avisaron.
	 */
	@Transactional
	public int avisarVencimientos(Instant ahora) {
		int centros = 0;
		Duration primerHito = Duration.ofDays(HitosDeAviso.DIAS.getFirst());
		for (PruebasGratis.PruebaEnCurso prueba : pruebas.queTerminanEntre(
				ahora.minus(VENTANA_DE_VENCIDAS), ahora.plus(primerHito))) {
			boolean vencida = !prueba.pruebaHasta().isAfter(ahora);
			OptionalInt hito = HitosDeAviso.alcanzado(prueba.pruebaHasta(), ahora);
			if (!vencida && hito.isEmpty()) {
				continue;
			}
			NotificationType tipo = vencida ? NotificationType.PRUEBA_VENCIDA : NotificationType.PRUEBA_POR_VENCER;
			String prefijo = vencida ? "prueba-vencida-" : "prueba-por-vencer-" + hito.getAsInt() + "-";
			String clave = prefijo + prueba.organizationId() + "-" + prueba.pruebaHasta().getEpochSecond();
			String fin = FECHA.format(prueba.pruebaHasta().atZone(zonas.de(prueba.organizationId())));
			for (AccountIdentity cuenta : identidades(cuentas, pruebas.administradores(prueba.organizationId()))) {
				Map<String, String> datos = new LinkedHashMap<>();
				if (primerNombre(cuenta) != null) {
					datos.put("nombre", primerNombre(cuenta));
				}
				datos.put("organizacionNombre", prueba.nombreCentro());
				datos.put("pruebaVencimiento", fin);
				if (!vencida) {
					datos.put("diasRestantes", String.valueOf(HitosDeAviso.diasRestantes(prueba.pruebaHasta(), ahora)));
				}
				outbox.enqueue(new NotificationEnqueueCommand(tipo, cuenta.email(),
						clave + "-" + cuenta.accountId(), prueba.organizationId(), null, datos));
			}
			centros++;
		}
		if (centros > 0) {
			log.info("Avisos de prueba gratis revisados: centros={}", centros);
		}
		return centros;
	}

	static Collection<AccountIdentity> identidades(AccountIdentityDirectory cuentas, Collection<Long> accountIds) {
		return accountIds.isEmpty() ? java.util.List.of() : cuentas.identidadesDe(accountIds).values();
	}

	static String primerNombre(AccountIdentity cuenta) {
		String nombre = cuenta.nombreCompleto();
		if (nombre == null || nombre.isBlank()) {
			return null;
		}
		return nombre.strip().split("\\s+")[0];
	}
}
