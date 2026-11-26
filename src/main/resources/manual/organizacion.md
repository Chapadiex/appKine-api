---
id: organizacion
titulo: Organización
grupo: Configuración
rutas: ["/organizacion", "/organizacion/suscripcion", "/organizacion/marca", "/organizacion/inteligencia-artificial", "/organizacion/auditoria", "/atencion/politica-de-enmienda"]
permisos: []
relacionadas: [sedes, colaboradores, biblioteca, primeros-pasos]
palabras: [centro, datos fiscales, cuit, razón social, marca, logo, colores, inteligencia artificial, consentimiento, suscripción, plan, cambio de plan, subir de plan, auditoría, enmienda]
---

## Para qué sirve

Reúne los datos y la configuración de **todo el centro**, no de una sede. Arriba tenés pestañas:
**Datos del centro**, **Suscripción**, **Marca**, **Inteligencia artificial**, **Auditoría** y
**Política de enmienda**. Cada persona ve las pestañas que su permiso le deja usar.

> [!consejo]
> Los datos fiscales que cargás acá los usan todas las sedes que no tienen los suyos. No hace falta
> repetirlos sede por sede.

## Tareas

### Editar los datos fiscales del centro

![La tarjeta Datos del centro, con el botón Editar datos fiscales marcado](captura:organizacion/datos-fiscales)

1. Entrá a :icono[configuracion]: **Configuración → Organización**. Se abre la pestaña **Datos del centro**.
2. Tocá **Editar datos fiscales**.
3. Completá **Razón social**, **CUIT o identificación fiscal** y **Domicilio fiscal**. Todos son
   opcionales, y vaciar un dato lo borra.
4. Tocá **Guardar los cambios**.

El nombre del centro y la zona horaria no se cambian desde esta pantalla.

### Cargar los datos de contacto del pie

1. En la pestaña **Datos del centro**, tocá **Editar datos de contacto**.
2. Completá **Teléfono**, **Email**, **Sitio web**, **Dirección** y, si querés, una **Leyenda del
   pie** (por ejemplo, el horario de atención).
3. Tocá **Guardar los cambios**.

Estos datos se ven en el pie de cada pantalla, para todas las personas del centro.

### Cambiar los colores y el logo del centro

![La pestaña Marca, con el tema, una paleta y el botón Guardar los cambios marcados](captura:organizacion/marca)

1. Tocá la pestaña **Marca**.
2. Elegí qué tema vas a editar: **Tema claro** o **Tema oscuro**.
3. Tocá una de las **Paletas**, o una zona de la **Vista previa** (barra lateral, cabecera, tarjetas,
   botones). Ajustá el color con el selector, el código o los botones :icono[menos]: y
   :icono[agregar]: para hacerlo más claro o más oscuro.
4. Tocá **Guardar los cambios**. Si te arrepentiste, tocá **Descartar**.

Para el logo, tocá **Subir logo** (o **Reemplazar**) en cada tema. El logo se guarda apenas lo
subís: PNG, JPG o WebP de hasta 500 kB.

> [!importante]
> Si aparece un **aviso de contraste**, el texto puede no leerse bien con ese color. Tocá **Usar el
> tono sugerido** para corregirlo.

### Aceptar el consentimiento para usar inteligencia artificial

1. Tocá la pestaña **Inteligencia artificial**.
2. Leé el texto de **Consentimiento para usar inteligencia artificial**.
3. Tocá **Acepto en nombre del centro**.

Hasta que el centro lo acepta, ninguna función de inteligencia artificial anda, ni siquiera con el
modelo instalado en el servidor. Queda registrado quién lo aceptó y cuándo. Si el texto cambia, hay
que volver a aceptarlo.

### Prender o apagar funciones de inteligencia artificial

![La pestaña Inteligencia artificial, con el interruptor del Asistente de datos y su estado marcados](captura:organizacion/inteligencia-artificial)

1. Tocá la pestaña **Inteligencia artificial**.
2. En **Funciones del centro**, tocá el interruptor de la función: **Asistente de datos**,
   **Evolución del caso**, **Sugerencias de evolución** o **Biblioteca del centro**.
3. Mirá el estado a la derecha: **Activa** o **Apagada para el centro**.

Al apagar una función, su pantalla desaparece del menú para todo el centro. En **Motor de
modelos** podés tocar **Cargar en memoria** para que la primera pregunta no espere, y **Probar**
para ver cuánto tarda en responder.

### Pedir un cambio de plan

1. Tocá la pestaña **Suscripción**.
2. En la tarjeta **Cambio de plan**, tocá **Pedir un cambio de plan**.
3. Elegí el **Plan**, marcá los servicios que querés tener (**Kinesiología y rehabilitación**,
   **Actividades y clases**) y si sumás la **Inteligencia artificial**.
4. Revisá **Lo que pedís**. Si querés, dejá un **Comentario**.
5. Tocá **Enviar el pedido**.

La administración de AKINE lo revisa. Mientras espera, la tarjeta dice **Esperando respuesta** y
podés tocar **Cancelar el pedido**. Si lo aprueba, el cambio se aplica solo; si lo rechaza, ves el
motivo. Bajar de plan no borra nada: solo se rechazan las altas nuevas que pasen el tope.

### Consultar la auditoría

1. Tocá la pestaña **Auditoría**.
2. En **Buscar por**, elegí un solo criterio: **Historial de una entidad**, **Actividad de una
   persona** o **Ventana temporal**.
3. Completá los datos que pide ese criterio.
4. Tocá **Buscar**.

La auditoría muestra quién hizo qué, sobre qué, cuándo y con qué motivo. Al entrar no muestra
nada hasta que buscás.

### Fijar cuántos días se puede enmendar una sesión

1. Tocá la pestaña **Política de enmienda**.
2. En **Días para enmendar**, escribí un número de 1 a 3650.
3. Mirá **Así se aplica** para ver cómo queda.
4. Tocá **Guardar la ventana**.

Una enmienda es una corrección de una sesión ya cerrada, siempre con motivo. Pasada la ventana, la
atención queda como se registró.

> [!importante]
> Achicar la ventana alcanza también a las sesiones ya cerradas: las que queden afuera dejan de
> poder enmendarse desde hoy.

## Qué significa cada estado

- **Activa** (estado operativo del centro): el centro opera con normalidad.
- **Suspendida**: el centro queda en solo lectura hasta que se reactive. Nada se borra.
- **Cancelada** o **Dada de baja**: el centro ya no opera. Solo se puede consultar.
- **En prueba** (suscripción): el centro está en la prueba gratis y opera normalmente.
- **Prueba vencida**: terminó la prueba. Se puede consultar y exportar, pero no cargar nada nuevo
  hasta elegir un plan.
- **Apagada para el centro** (función de IA): el centro la apagó; su pantalla no se ve.
- **No disponible en el servidor**: la instalación no ofrece esa función. No se prende desde acá.
- **Necesita …**: la función depende de otra que está apagada. Por ejemplo, las sugerencias
  necesitan Evolución y Biblioteca.

## Problemas comunes

- **No anda ninguna función de inteligencia artificial**: el centro todavía no aceptó el
  consentimiento. Lo acepta quien administra el centro, en la pestaña **Inteligencia artificial**.
- **No veo las pestañas Marca ni Inteligencia artificial**: hacen falta permisos para editar la
  organización. Pedíselo a quien administra el centro. Lo mismo pasa con **Auditoría** y
  **Política de enmienda**, que tienen su propio permiso.
- **«La política cambió mientras la editabas»**: otra persona la guardó antes. Volvé a cargar la
  pantalla y repetí el cambio.
- **«El motor no responde»**: el programa que corre los modelos no está levantado. Las funciones
  prendidas van a dar error hasta que vuelva. Avisale a quien administra la instalación.
- **Cambié los colores y nadie más los ve**: todavía no guardaste. Mientras no toques **Guardar los
  cambios**, los cambios los ves solo vos.
