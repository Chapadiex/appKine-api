package com.akine.assistant.spi;

/**
 * Una consulta que el asistente sabe responder. La implementa el modulo dueño del dato, en su
 * {@code infrastructure}, igual que {@code reporting.spi.ReporteContributor}: la dependencia va del
 * modulo hacia este {@code spi} y {@code assistant} no importa a nadie.
 *
 * <p>Cada implementacion traduce {@link ActorDelAsistente} a su {@code OperatingActor} y llama a su
 * servicio de aplicacion, que exige el permiso. <b>Solo lectura.</b> Si el servicio rechaza al actor,
 * su {@code AccessDeniedException} se deja propagar: el asistente la convierte en una respuesta "sin
 * permiso" en un solo lugar, no en cada herramienta.
 */
public interface HerramientaDelAsistente {

	/** La intencion que resuelve. Una herramienta por intencion. */
	Intencion intencion();

	ResultadoDeHerramienta ejecutar(ActorDelAsistente actor, PedidoDeHerramienta pedido);
}
