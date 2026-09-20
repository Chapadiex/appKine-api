package com.akine.clinical.infrastructure;

import com.akine.reporting.spi.AporteDeReporte;
import com.akine.reporting.spi.ConsultaDeReporte;
import com.akine.reporting.spi.IndicadorDeReporte;
import com.akine.reporting.spi.ReporteCode;
import com.akine.reporting.spi.ReporteContributor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Lo que {@code clinical} aporta a los reportes: apertura y cierre de Casos (RF-M23-003).
 *
 * <h2>Tres numeros y ninguno es contenido clinico</h2>
 *
 * <p>Cuantos casos se abrieron en el periodo, cuantos se cerraron, y cuantos siguen activos.
 * <b>Ni un diagnostico presuntivo, ni un objetivo terapeutico, ni un motivo de cierre, ni un
 * paciente.</b> La seccion no tiene detalle: una fila por caso seria un listado de la actividad
 * clinica de la sede, y quien lo necesita entra por M10 con su permiso y su justificacion.
 *
 * <h2>La sede de un caso es {@code oferta_consultorio_id}</h2>
 *
 * <p>Y no una columna {@code consultorio_id}, que no existe. El Caso cuelga de la Historia
 * Clinica, que es de la <b>organizacion</b> (DP-03), asi que su sede es la de la oferta que lo
 * origino. Es la columna que indexa {@code ix_caso_clinico_sede_apertura} en V59: {@code
 * caso_clinico} no tenia ningun indice no-unico.
 *
 * <h2>Dos cortes distintos, declarados</h2>
 *
 * <p>Abiertos y cerrados cortan por su instante dentro del periodo. <b>Los activos son un corte
 * al presente</b>, no una reconstruccion del estado al dia {@code hasta}: reconstruirlo exigiria
 * una segunda formula de la misma cosa, y esa divergencia es lo que esta etapa se propuso no
 * crear. Va declarado en {@code criterioDeFecha} y el cliente lo muestra. Un numero honesto y
 * explicado vale mas que uno exacto y silencioso.
 */
@Component
public class CasosEnElReporte implements ReporteContributor {

	private static final String SECCION = "casos";
	private static final String FUENTE = "M10 caso_clinico";

	private final CasoClinicoRepository casos;

	public CasosEnElReporte(CasoClinicoRepository casos) {
		this.casos = casos;
	}

	@Override
	public Set<ReporteCode> reportes() {
		return Set.of(ReporteCode.CLINICO);
	}

	@Override
	public String seccion() {
		return SECCION;
	}

	@Override
	public String titulo() {
		return "Casos clinicos";
	}

	/** {@code hc:read}: un caso es historia clinica. */
	@Override
	public String permisoRequerido() {
		return "hc:read";
	}

	@Override
	public boolean esClinica() {
		return true;
	}

	@Override
	public AporteDeReporte aportar(ConsultaDeReporte consulta) {
		long abiertos = casos.contarAbiertosEnElReporte(
				consulta.organizationId(), consulta.consultorioId(),
				consulta.desdeInstante(), consulta.hastaInstante());
		long cerrados = casos.contarCerradosEnElReporte(
				consulta.organizationId(), consulta.consultorioId(),
				consulta.desdeInstante(), consulta.hastaInstante());
		long activos = casos.contarActivosEnElReporte(
				consulta.organizationId(), consulta.consultorioId());

		return AporteDeReporte.de(SECCION, titulo(), List.of(
				IndicadorDeReporte.contando("casos-abiertos-en-el-periodo", "Casos abiertos",
						abiertos, FUENTE,
						"abierto_en, instante proyectado a la zona de la sede"),
				IndicadorDeReporte.contando("casos-cerrados-en-el-periodo", "Casos cerrados",
						cerrados, FUENTE,
						"cerrado_en, instante proyectado a la zona de la sede"),
				IndicadorDeReporte.contando("casos-activos-al-corte", "Casos activos hoy",
						activos, FUENTE,
						"estado actual, NO reconstruido a la fecha de corte")));
	}
}
