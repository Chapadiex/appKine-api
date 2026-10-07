package com.akine.reporting.spi;

import java.util.Set;

/**
 * Lo que un modulo aporta a los reportes del MVP (M23).
 *
 * <h2>Por que la dependencia va al reves de lo que uno esperaria</h2>
 *
 * <p>El reflejo es que {@code reporting} le pregunte a {@code scheduling}, a {@code encounter}, a
 * {@code clinical} y a {@code billing}. <b>Eso pone a {@code reporting} a un solo paso de cerrar un
 * ciclo con cualquiera de los cuatro</b>, y no es una preocupacion teorica: se comprobo con una
 * clase sonda antes de escribir este archivo. Con las aristas de este diseno,
 * {@code ModuleArchitectureTest} da 5/5 en verde; agregando <b>una sola linea</b> —un campo
 * {@code CasoDirectory} en el servicio de {@code reporting}— ArchUnit detecta
 * {@code Slice clinical -> Slice reporting} y el build se cae.
 *
 * <p>Asi que la dependencia se invierte: {@code reporting} publica esta interfaz y <b>cada modulo
 * rio abajo la implementa</b>. Es el mismo patron de {@code person.spi.ResumenDePersonaContributor}
 * (03.02) y de {@code clinical.spi.EventoClinicoContributor} (04.02), con una diferencia que vale
 * la pena nombrar: <b>alla las fuentes eran dos, aca son cinco</b>, y el riesgo escala con ellas.
 *
 * <p>Hay una segunda razon, tan fuerte como la del ciclo: <b>el que sabe como se cuenta una
 * obligacion es {@code billing}</b>. Que estados suman, cual es la columna de corte, que significa
 * una baja logica en esa tabla. Si {@code reporting} mantuviera esa tabla de conocimiento, el dia
 * que un modulo cambie una regla el reporte seguiria mostrando lo que ya no corresponde.
 *
 * <p>Consecuencia buscada: <b>una seccion nueva no toca {@code reporting}.</b> Cuando exista
 * {@code activity}, RF-M23-007 agrega su contribuyente y aparece sola.
 *
 * <h2>Lo que un aporte NO puede traer</h2>
 *
 * <p><b>Contenido clinico, nunca</b>, y <b>ningun identificador de persona</b>. La minimizacion es
 * la misma que trazo el Paciente 360 y la razon aca es todavia mas concreta: un
 * {@code ADMINISTRATIVO} tiene {@code cobro:register} y por lo tanto ve la seccion economica, pero
 * la matriz de permisos dice que ese rol ve reportes "sin contenido clinico". Una fila economica
 * por paciente y por oferta le permitiria deducir que prestacion recibio quien, sin {@code hc:read}
 * y sin dejar rastro.
 */
public interface ReporteContributor {

	/**
	 * A que reportes aporta esta seccion.
	 *
	 * <p>Un contribuyente puede servir a varios: los turnos alimentan tanto {@link
	 * ReporteCode#TURNOS} como el tablero {@link ReporteCode#OPERATIVO}, y calcularlos dos veces
	 * con dos formulas seria la misma divergencia que la etapa se propuso no crear.
	 */
	Set<ReporteCode> reportes();

	/** Nombre estable de la seccion: {@code turnos}, {@code economia}. Viaja al cliente. */
	String seccion();

	/** Titulo legible de la seccion. */
	String titulo();

	/**
	 * Codigo de permiso de la matriz que hay que tener para ver esta seccion, o {@code null} si
	 * alcanza con la pertenencia al tenant.
	 *
	 * <p><b>Lo declara el contribuyente y no {@code reporting}</b>, por la misma razon que en el
	 * 360: el que sabe con que permiso se leen los turnos es {@code scheduling}.
	 *
	 * <p>Una seccion sin permiso <b>no se pide, no se calcula y no produce 403</b>: el reporte
	 * devuelve lo que el actor puede ver y <b>declara lo que omitio</b>. Un 403 sobre el tablero
	 * entero por no poder ver la deuda dejaria al recepcionista sin poder abrir ninguno; y omitir en
	 * silencio seria peor todavia, porque leeria "cero turnos" donde dice "no podes ver los turnos".
	 */
	String permisoRequerido();

	/**
	 * Si esta seccion lee datos clinicos. Por defecto no.
	 *
	 * <p>AKINE-04.01 dejo fijado que <b>toda lectura clinica se audita, no solo las mutaciones</b>.
	 * Lo declara el contribuyente porque el unico que sabe de que tabla lee es el modulo duenio, y
	 * deducirlo desde {@code reporting} por el codigo de permiso seria una tabla de equivalencias
	 * que se desactualiza sola.
	 *
	 * <p>Vale <b>aunque el aporte sea agregado</b>: lo que la regla protege es el acceso, no el
	 * volumen. Un conteo de sesiones por caso sigue siendo alguien mirando la actividad clinica de
	 * una sede.
	 */
	default boolean esClinica() {
		return false;
	}

	/**
	 * Si esta seccion sabe recortarse a la actividad propia del actor. Por defecto no.
	 *
	 * <p>AKINE-G-1 (DP-15): un {@code PROFESIONAL} ve "solo reportes de su propia actividad"
	 * (matriz §4). Cuando la consulta llega recortada —{@link
	 * ConsultaDeReporte#recortadaAActividadPropia()}—, solo se invocan las secciones que devuelven
	 * {@code true} aca; <b>las demas se omiten y se declaran</b>, nunca se calculan sin recorte.
	 *
	 * <p>El default es {@code false} a proposito: una seccion nueva que se olvide de declararlo
	 * queda afuera del reporte limitado en vez de mostrarle al profesional la actividad de todo el
	 * equipo. Ante la duda, cerrado.
	 */
	default boolean filtraPorActividadPropia() {
		return false;
	}

	/** El aporte de este modulo. Nunca {@code null}: usar {@link AporteDeReporte#vacio(String)}. */
	AporteDeReporte aportar(ConsultaDeReporte consulta);
}
