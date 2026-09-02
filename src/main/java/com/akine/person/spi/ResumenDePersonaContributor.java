package com.akine.person.spi;

/**
 * Lo que un modulo aporta al Paciente 360 de una Persona (RF-M07-004).
 *
 * <h2>Por que la dependencia va al reves de lo que uno esperaria</h2>
 *
 * <p>El 360 consolida "cobertura, turnos, casos y situacion economica". El reflejo es que
 * {@code person} le pregunte a {@code scheduling} y a {@code billing}. <b>Eso es un ciclo y
 * ArchUnit lo rechaza:</b> los dos ya dependen de {@code person.spi} —un turno y una obligacion
 * cuelgan de una persona— y hacer que {@code person} los importe cierra el anillo. No es una
 * formalidad: un ciclo entre modulos significa que ninguno de los dos se puede leer, testear ni
 * mover sin el otro.
 *
 * <p>Asi que la dependencia se invierte. {@code person} publica esta interfaz y <b>cada modulo
 * rio abajo la implementa</b>; el consumidor inyecta una {@code List<ResumenDePersonaContributor>}
 * y Spring la llena con las que existan. Es el mismo patron que
 * {@code clinical.spi.EventoClinicoContributor}, con una diferencia que vale la pena: aquel nacio
 * como costura sin implementaciones, este nace con dos.
 *
 * <p>Consecuencia buscada: <b>una seccion nueva del 360 no toca {@code person}.</b> Cuando 03.03
 * traiga coberturas y 03.04 la deuda del paciente, agregan su contributor y aparecen solas.
 *
 * <h2>Lo que un aporte NO puede traer</h2>
 *
 * <p><b>Contenido clinico, nunca.</b> El 360 es la ficha del MOSTRADOR y esta etapa se llama
 * "adjuntos administrativos" por algo. AKINE-04.01 dejo fijado que todo acceso clinico exige
 * justificacion declarada y queda auditado; una pantalla que muestre evoluciones al abrir una
 * ficha convertiria ese control en un formalismo. Por eso el aporte es de indicadores y de hitos
 * datados —"tres turnos futuros", "el proximo es el martes"—, y quien quiera el detalle va al
 * modulo duenio con su propio permiso.
 */
public interface ResumenDePersonaContributor {

	/**
	 * Nombre estable de la seccion que este modulo aporta: {@code turnos}, {@code economia}.
	 *
	 * <p>Viaja al cliente y es lo que la pantalla usa para ubicar el bloque, asi que renombrarlo
	 * es un cambio de contrato aunque no toque ningun DTO.
	 */
	String seccion();

	/**
	 * Codigo de permiso de la matriz que hay que tener para ver esta seccion, o {@code null} si
	 * alcanza con la pertenencia al tenant.
	 *
	 * <p><b>Lo declara el contribuyente y no {@code person}</b>, porque el que sabe con que
	 * permiso se leen los turnos es {@code scheduling}. Si {@code person} mantuviera esa tabla,
	 * el dia que un modulo cambie su permiso el 360 seguiria mostrando lo que ya no corresponde.
	 *
	 * <p>Una seccion sin permiso NO se pide y NO produce un 403: el 360 devuelve lo que el actor
	 * puede ver y <b>declara lo que omitio</b>. Un 403 sobre la ficha entera por no poder ver la
	 * deuda dejaria al recepcionista sin poder abrir a nadie.
	 */
	String permisoRequerido();

	/** El aporte de este modulo para esa persona. Nunca {@code null}: usar el aporte vacio. */
	AporteDeResumen aportar(ConsultaDeResumen consulta);
}
