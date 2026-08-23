package com.akine.platform.infrastructure.audit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.akine.platform.domain.AuditEvent;
import com.akine.platform.infrastructure.AuditEventRepository;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Escritura de la auditoria de la plataforma (decision T-2).
 *
 * <h2>Por que {@code Propagation.MANDATORY} y no {@code REQUIRED}</h2>
 * <p>Las dos cumplirian "escribir en la transaccion del llamador" cuando hay una. La diferencia
 * esta en el caso en que NO la hay, y ese caso es el que importa:
 * <ul>
 *   <li>Con {@code REQUIRED}, la auditoria abriria una transaccion propia y commitearia sola.
 *       El registro quedaria escrito con independencia de si la operacion de negocio se
 *       confirmo, y nadie se enteraria: una auditoria que dice que algo paso cuando no paso es
 *       peor que no tenerla, porque se le cree.</li>
 *   <li>Con {@code MANDATORY}, invocar la auditoria fuera de una transaccion de negocio es un
 *       error inmediato y ruidoso ({@code IllegalTransactionStateException}). El acoplamiento
 *       que T-2 exige —si la auditoria falla, la operacion no se confirma— deja de depender de
 *       que cada llamador se acuerde de abrir transaccion.</li>
 * </ul>
 * <p>Es deliberadamente incomodo. Esa incomodidad es la garantia.
 *
 * <p>No hay listener {@code AFTER_COMMIT} ni escritura post-commit, y no debe agregarse: un
 * listener que falla despues del commit deja la mutacion hecha y sin rastro. Los eventos de
 * dominio —notificaciones, proyecciones— si van post-commit, porque su fallo no debe revertir
 * el negocio. Son dos mecanismos distintos y no se sustituyen.
 *
 * <p>{@code @Component} y no {@code @Service}: {@code servicios_solo_en_application} reserva
 * {@code @Service} para la capa de negocio, y esto es un adaptador de persistencia.
 */
@Component
public class AuditTrailImpl implements AuditTrail {

	/** Marca que reemplaza el valor de una clave sospechosa. Se conserva la clave: saber que se intento registrar un secreto es informacion util; el secreto no. */
	public static final String VALOR_REDACTADO = "[REDACTADO]";

	/** Largo maximo de un valor de {@code details}. Corta payloads que se escaparon de control. */
	public static final int LARGO_MAXIMO_VALOR = 500;

	/** {@code reason} tiene 500 en la tabla; truncar aca evita que una operacion de negocio se caiga por un motivo largo. */
	public static final int LARGO_MAXIMO_REASON = 500;

	/** {@code correlation_id} tiene 64 en la tabla. */
	public static final int LARGO_MAXIMO_CORRELATION_ID = 64;

	/**
	 * Fragmentos de clave cuyo valor NUNCA se persiste.
	 *
	 * <p>Dos familias, por dos motivos distintos:
	 * <ul>
	 *   <li><b>Secretos</b> (password, token, secret, credential, authorization, api key, hash,
	 *       otp, pin): la tabla de auditoria se consulta para investigar incidentes y termina en
	 *       backups. Un secreto ahi es una credencial persistida, que es justo lo que
	 *       RN-M02-003 prohibe.</li>
	 *   <li><b>Contenido clinico</b> (diagnostico, evolucion, anamnesis, sintoma, tratamiento,
	 *       nota, observacion): la auditoria registra QUE paso, QUIEN lo hizo y SOBRE QUE, no el
	 *       contenido del dato. Copiar historia clinica a una segunda tabla la multiplica sin
	 *       control y fuera del modelo de permisos que la protege.</li>
	 * </ul>
	 * <p>La lista es una red de seguridad, no un permiso para ser descuidado: el emisor sigue
	 * siendo responsable de no mandar lo que no corresponde.
	 */
	private static final List<String> CLAVES_SENSIBLES = List.of(
			"password", "contrasena", "contrasenia", "clave", "secret", "token", "credential",
			"credencial", "authorization", "apikey", "api_key", "hash", "otp", "pin",
			"diagnostico", "evolucion", "anamnesis", "sintoma", "tratamiento", "nota",
			"observacion", "historia");

	/**
	 * Serializador propio del adaptador: la forma del JSON persistido es parte del dato
	 * auditado y no debe cambiar porque alguien reconfigure el {@code ObjectMapper} de la API.
	 */
	private static final ObjectMapper JSON = JsonMapper.builder().build();

	private final AuditEventRepository auditEventRepository;

	public AuditTrailImpl(AuditEventRepository auditEventRepository) {
		this.auditEventRepository = auditEventRepository;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void record(AuditEntry entry) {
		if (entry == null) {
			throw new IllegalArgumentException("No se audita una entrada nula");
		}

		AuditEvent event = new AuditEvent(
				entry.organizationId(),
				entry.consultorioId(),
				entry.actorAccountId(),
				entry.eventType(),
				entry.entityType(),
				entry.entityId(),
				entry.previousState(),
				entry.newState(),
				detallesSanitizados(entry.details()),
				truncar(entry.reason(), LARGO_MAXIMO_REASON),
				truncar(entry.correlationId(), LARGO_MAXIMO_CORRELATION_ID),
				entry.occurredAt());

		auditEventRepository.save(event);
	}

	/**
	 * Sanitiza y serializa el detalle.
	 *
	 * @return JSON con el detalle depurado, o {@code null} si no hay nada que registrar (la
	 *         columna queda NULL en vez de con un {@code "{}"} que no significa nada)
	 */
	private String detallesSanitizados(Map<String, String> details) {
		if (details == null || details.isEmpty()) {
			return null;
		}
		Map<String, String> depurado = new LinkedHashMap<>();
		details.forEach((clave, valor) -> {
			if (clave == null) {
				return;
			}
			depurado.put(clave, esSensible(clave) ? VALOR_REDACTADO : truncar(valor, LARGO_MAXIMO_VALOR));
		});
		return JSON.writeValueAsString(depurado);
	}

	private boolean esSensible(String clave) {
		String normalizada = clave.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
		return CLAVES_SENSIBLES.stream()
				.map(sensible -> sensible.replace("_", ""))
				.anyMatch(normalizada::contains);
	}

	private String truncar(String valor, int largoMaximo) {
		if (valor == null || valor.length() <= largoMaximo) {
			return valor;
		}
		return valor.substring(0, largoMaximo);
	}
}
