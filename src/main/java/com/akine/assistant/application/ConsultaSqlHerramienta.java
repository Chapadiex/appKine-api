package com.akine.assistant.application;

import com.akine.assistant.domain.AsistenteAjustes;
import com.akine.assistant.domain.PermissionCodes;
import com.akine.assistant.domain.port.LectorDeVistas;
import com.akine.assistant.domain.port.LectorDeVistas.ConsultaInvalidaException;
import com.akine.assistant.spi.ActorDelAsistente;
import com.akine.assistant.spi.ContextoDeConsulta;
import com.akine.assistant.spi.EntradaDeHerramienta;
import com.akine.assistant.spi.HerramientaDelAgente;
import com.akine.assistant.spi.ResultadoDeHerramienta;
import com.akine.organization.spi.PermissionDecision;
import com.akine.organization.spi.PermissionEvaluator;
import com.akine.organization.spi.PermissionQuery;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@code consulta_sql} y {@code consulta_sql_clinica}: el SELECT que escribe el modelo sobre las vistas
 * de {@code asistente_sql} (docs/diseno/AKINE-asistente-sql.md). Las tres barreras siguen donde
 * estaban: {@link ValidadorDeSql}, la transaccion READ ONLY del lector y las vistas recortadas a la
 * sede.
 *
 * <p>Son dos herramientas porque {@code descripcion()} no conoce al actor: la comun lleva el esquema
 * sin datos clinicos y exige {@code asistente:sql}; la clinica lleva ademas las vistas {@code clinico_*}
 * y exige {@code hc:read} sin recorte (las vistas no saben de relacion asistencial). La comun rechaza
 * cualquier {@code clinico_*}. Un error del motor vuelve como {@link IllegalArgumentException} con su
 * mensaje, para que el modelo corrija y reintente.
 */
public final class ConsultaSqlHerramienta implements HerramientaDelAgente {

	public static final String NOMBRE = "consulta_sql";
	public static final String NOMBRE_CLINICA = "consulta_sql_clinica";
	static final int MAX_FILAS = 50;
	private static final String HC_READ = "hc:read";
	private static final Set<String> ALCANCES_RECORTADOS = Set.of("RESTRINGIDO",
			PermissionDecision.ALCANCE_ACTIVIDAD_PROPIA);
	private static final String ESQUEMA = """
			{"type":"object","properties":{"sql":{"type":"string","description":"Una sola sentencia SELECT \
			(o WITH) sobre las vistas descriptas, con LIMIT 50 en los listados"}},\
			"required":["sql"],"additionalProperties":false}""";

	private final boolean clinica;
	private final String descripcion;
	private final LectorDeVistas lector;
	private final AuditoriaDelAsistente auditoria;
	private final PermissionEvaluator permisos;
	private final AsistenteAjustes ajustes;

	public ConsultaSqlHerramienta(boolean clinica, String descripcion, LectorDeVistas lector,
			AuditoriaDelAsistente auditoria, PermissionEvaluator permisos, AsistenteAjustes ajustes) {
		this.clinica = clinica;
		this.descripcion = descripcion;
		this.lector = lector;
		this.auditoria = auditoria;
		this.permisos = permisos;
		this.ajustes = ajustes;
	}

	@Override
	public String nombre() {
		return clinica ? NOMBRE_CLINICA : NOMBRE;
	}

	@Override
	public String descripcion() {
		return descripcion;
	}

	@Override
	public String esquemaEntrada() {
		return ESQUEMA;
	}

	@Override
	public boolean disponiblePara(ActorDelAsistente actor) {
		if (!ajustes.sql() || actor.organizationId() == null || actor.consultorioId() == null
				|| !concedido(actor, PermissionCodes.ASISTENTE_SQL)) {
			return false;
		}
		return !clinica || puedeLeerClinico(actor);
	}

	@Override
	public ResultadoDeHerramienta ejecutar(ActorDelAsistente actor, Map<String, Object> entrada,
			ContextoDeConsulta ctx) {
		String sql = EntradaDeHerramienta.texto(entrada, "sql");
		Optional<String> rechazo = ValidadorDeSql.rechazo(sql, clinica);
		if (rechazo.isPresent()) {
			throw new IllegalArgumentException(rechazo.get() + (!clinica && rechazo.get().contains("datos clinicos")
					? ". Si esta disponible, usa consulta_sql_clinica" : ""));
		}
		List<Map<String, Object>> filas;
		try {
			filas = lector.leer(sql, actor.organizationId(), actor.consultorioId(),
					ctx == null || ctx.zona() == null ? java.time.ZoneOffset.UTC : ctx.zona(), MAX_FILAS + 1);
		}
		catch (ConsultaInvalidaException e) {
			throw new IllegalArgumentException("El motor rechazo la consulta: " + e.getMessage());
		}
		boolean truncado = filas.size() > MAX_FILAS;
		List<Map<String, Object>> devueltas = truncado ? filas.subList(0, MAX_FILAS) : filas;
		Set<String> vistas = ValidadorDeSql.vistasClinicas(sql);
		if (!vistas.isEmpty()) {
			// DP-03: toda lectura clinica se audita, con las vistas y la cantidad de filas; la consulta no.
			auditoria.registrarLecturaClinica(actor.organizationId(), actor.consultorioId(), actor.accountId(),
					vistas, devueltas.size());
		}
		List<String> avisos = truncado
				? List.of("Hay más de " + MAX_FILAS + " resultados; muestro los primeros " + MAX_FILAS) : List.of();
		return new ResultadoDeHerramienta(devueltas, avisos, null, false);
	}

	private boolean concedido(ActorDelAsistente actor, String codigo) {
		return permisos.evaluate(new PermissionQuery(actor.accountId(), codigo, actor.organizationId(),
				actor.consultorioId(), null, Instant.now())).granted();
	}

	/** {@code hc:read} en la sede y sin recorte por paciente: las vistas no pueden recortar filas. */
	private boolean puedeLeerClinico(ActorDelAsistente actor) {
		PermissionDecision decision = permisos.evaluate(new PermissionQuery(actor.accountId(), HC_READ,
				actor.organizationId(), actor.consultorioId(), null, Instant.now()));
		return decision.granted() && !ALCANCES_RECORTADOS.contains(decision.grantedByScope());
	}
}
