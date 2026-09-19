package com.akine.clinical.domain.port;

import java.util.Optional;

/**
 * El almacenamiento del CONTENIDO de un adjunto clinico, del lado del dominio.
 *
 * <h2>Por que hay un puerto y no un {@code LONGBLOB}</h2>
 *
 * <p>Los motivos de que el binario no entre a la base estan en la cabecera de {@code V46} y son
 * los mismos de {@code V40}: backups y buffer pool, portabilidad a almacenamiento de objetos, y
 * que una fila sin binario es un estado detectable mientras que un binario sin fila no existe. Lo
 * que este puerto agrega es la consecuencia de diseño: <b>el dia que esto corra contra
 * almacenamiento de objetos, migrar es escribir otro adaptador</b>, y ninguna regla clinica se
 * entera.
 *
 * <h2>Por que este puerto existe teniendo {@code person} el suyo</h2>
 *
 * <p>Se evaluo extraer un unico adaptador a {@code platform} y compartirlo. Se rechazo, y el
 * diseño (seccion 4) lo argumenta en las dos direcciones:
 *
 * <ul>
 *   <li><b>Contra compartir:</b> {@code person} ya tiene el suyo cerrado y verificado, y moverlo
 *       es retrabajo sobre una etapa cerrada con riesgo de regresion a cambio de ahorrar ~120
 *       lineas. Ademas ArchUnit prohibe que {@code clinical} importe nada de {@code person} que
 *       no sea su {@code spi}, asi que "reusar" significaria publicar una decision interna de
 *       {@code person} como contrato hacia todos los modulos.</li>
 *   <li><b>A favor de duplicar:</b> raices de disco separadas son <b>segregacion fisica de
 *       binarios clinicos</b> —otro volumen, otro cifrado en reposo, otra politica de backup y de
 *       retencion—, que es un control real y no una consecuencia del codigo.</li>
 * </ul>
 *
 * <p><b>La condicion de salida esta escrita y es vinculante: si aparece un TERCER consumidor, se
 * extrae a {@code platform.spi} y los tres adaptadores pasan a ser configuracion.</b> Dos
 * implementaciones son una decision; tres son un descuido.
 *
 * <h2>Por que {@code byte[]} y no {@code InputStream}</h2>
 *
 * <p>Porque el tamano ya esta acotado antes de llegar aca y porque el contenido hay que recorrerlo
 * entero de todos modos para calcular su SHA-256 y detectar su tipo real. Un {@code InputStream}
 * que hay que consumir dos veces obliga a bufferearlo, o sea a tener el arreglo igual, con la
 * diferencia de que el ciclo de vida del stream queda repartido entre capas. Si algun dia se
 * admiten estudios grandes —una serie de imagenes—, la firma cambia y el compilador va a nombrar
 * todos los lugares a revisar.
 *
 * <h2>La clave es opaca y la ruta la deriva el adaptador</h2>
 *
 * <p>{@code storageKey} no lleva el nombre del archivo, ni la organizacion, ni la historia, ni la
 * extension. El adaptador es el unico que sabe donde cae. RN-M25-002 pide no exponer rutas
 * internas; esto ademas garantiza que <b>ninguna cadena de origen externo participe de un
 * path</b>.
 */
public interface ContenidoClinicoStoragePort {

	/**
	 * Escribe el contenido bajo esa clave.
	 *
	 * @throws java.io.UncheckedIOException si el almacenamiento no pudo aceptarlo. El llamador lo
	 *                                      deja propagar: la transaccion revierte y no queda fila
	 *                                      apuntando a un contenido que no existe
	 */
	void guardar(String storageKey, byte[] contenido);

	/** El contenido, o vacio si el almacenamiento no lo tiene. */
	Optional<byte[]> leer(String storageKey);

	/**
	 * Tope de bytes que este almacenamiento acepta por archivo.
	 *
	 * <p>Vive en el puerto y no en una {@code @ConfigurationProperties} inyectada en el servicio
	 * por una razon de arquitectura y no de estilo: {@code application} <b>no puede importar
	 * {@code infrastructure}</b> —ArchUnit lo verifica— y el valor es configuracion, que es
	 * infraestructura por definicion. Ademas es conceptualmente correcto: el tope es una propiedad
	 * del almacenamiento, y el dia que haya un adaptador contra almacenamiento de objetos con otro
	 * limite, la regla de negocio no cambia.
	 */
	long tamanoMaximo();
}
