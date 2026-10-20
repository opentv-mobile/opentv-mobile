# Plan de pruebas manuales — roadmap 1.1

Este plan valida el APK de depuración final de la rama `feature/roadmap-1.1`. Las funciones están
implementadas en esa rama; la publicación de 1.1.0 sigue pendiente de commit, push, tag y CI.
Ejecuta primero el bloque de humo; después, los casos de función y las regresiones.

## Estado de validación para 1.1.0

El usuario aprobó las pruebas manuales generales y solicitó explícitamente preparar una nueva
release. Esa aprobación no equivale a haber ejecutado todos los casos de esta matriz: no hay un
resultado individual registrado para cada dispositivo, idioma, migración o caso límite. Los casos
sin evidencia específica siguen como no verificados; este plan se conserva para futuras regresiones.
La aprobación tampoco acredita una compilación local firmada para producción ni una publicación.

Validación automatizada local de preparación (20 de octubre de 2026):

- `./gradlew test lintDebug assembleDebug --stacktrace`: correcto; 131 pruebas debug y 131 release,
  sin fallos, errores ni omisiones. Lint debug y generación del APK debug completados correctamente.
- Corregido el fixture de `BrowsingViewportTest`: registra la actividad host de Compose solo en el
  PackageManager simulado de Robolectric antes de lanzar la regla, sin depender de un manifest debug.
  Sus cuatro casos pasan en ambas variantes; no se han deshabilitado ni excluido pruebas.
- Retirada la dependencia `ui-test-manifest`; los manifests fusionados de debug y release no declaran
  la actividad host `androidx.activity.ComponentActivity`.
- No se ha generado ni verificado un APK firmado de producción; la firma y publicación por CI siguen
  pendientes. El bloqueo local de configuración de pruebas release está resuelto.

## Preparación e instalación

Necesitas un teléfono físico con Android 6.0 o posterior. Para PiP conviene Android 8.0 o posterior
y un launcher que admita widgets redimensionables. Prepara:

- al menos un proveedor con TV en directo, películas, series y XMLTV;
- si es posible, un segundo proveedor que reutilice algún identificador o nombre de categoría;
- una lista IPTV local `.m3u` válida y una segunda versión con canales añadidos y eliminados;
- una lista vacía o mal formada y un manifiesto HLS (`#EXT-X-...`) para los casos negativos;
- un programa pasado con catch-up, uno en emisión y uno futuro, si el proveedor los ofrece.

El APK esperado es `app/build/outputs/apk/debug/app-debug.apk`. Desde la raíz del repositorio:

```bash
./gradlew assembleDebug
~/Library/Android/sdk/platform-tools/adb devices
~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

El build `debug` actual usa el paquete `app.opentvmobile.debug`, por lo que normalmente se instala
al lado de `app.opentvmobile` y **no** actualiza ni hereda los datos de la versión release. Además,
Android solo permite una actualización sobre el mismo paquete cuando la firma coincide: un APK
firmado con la clave de depuración puede ser rechazado al intentar instalarlo sobre uno firmado para
release.

No desinstales una instalación que contenga los datos con los que quieras comprobar una migración.
Haz antes una copia de seguridad o una exportación válida para esa versión. Si hace falta validar una
actualización real conservando datos, solicita un APK con el mismo `applicationId` y la misma firma
que la instalación de origen; el APK `debug` normal no demuestra esa migración.

Anota teléfono, versión de Android, escala de fuente, idioma, proveedor, commit probado y si fue una
instalación limpia o una actualización compatible.

## P0 — humo

Detén las pruebas de función si falla cualquiera de estos casos.

| Caso | Pasos | Resultado esperado |
|---|---|---|
| H1. Instalación y arranque | Instala con el comando anterior y abre la app desde el launcher. | Android acepta el APK, la app no se cierra y muestra la configuración inicial o los datos que ya pertenecían a ese paquete debug. |
| H2. Datos retenidos en actualización | Solo sobre una instalación anterior **del mismo paquete y firma**, marca un canal favorito, conserva al menos dos proveedores y deja una película o episodio a medio ver. Instala el nuevo APK con `adb install -r`. | Siguen presentes los proveedores y el favorito; «Continuar viendo» conserva el punto de reanudación. No uses una instalación limpia como evidencia de este caso. |
| H3. Selector de proveedor | En un teléfono vertical, abre «Añadir proveedor» y aumenta la fuente del sistema. Repite con un idioma RTL, por ejemplo árabe. | Los tres segmentos Xtream, M3U y Stalker son accesibles, mantienen orden/dirección coherentes con el idioma, tienen área táctil útil y el texto no hace desaparecer una opción. |
| H4. TV en directo | Reproduce un canal, cambia a otro y vuelve atrás. | El vídeo y audio arrancan, el cambio no bloquea el reproductor y volver regresa a TV en directo. |
| H5. VOD y reanudación | Reproduce una película o episodio más de 15 s, sal y vuelve a abrirlo. | Reanuda cerca de la posición guardada y los controles responden. |
| H6. Guía | Cambia la vista móvil de TV en directo a «Guía», desplaza tiempo y canales y pulsa un programa. | La cuadrícula se mueve sin bloqueo, los programas corresponden al canal y se abre el diálogo de acciones. |
| H7. Catálogo paginado | En Películas y Series, entra en «Ver todo», desplázate varias pantallas y abre un título. | Aparecen más títulos al avanzar, no hay duplicados obvios ni congelación y el detalle corresponde a la tarjeta. |
| H8. Widget | Reproduce varios canales, vuelve al launcher y añade el widget de canales recientes. Pulsa uno. | El widget muestra recientes permitidos y abre el canal correcto. |

## P1 — casos de función

### Selector en teléfono

1. Prueba el selector Xtream/M3U/Stalker en vertical con fuente predeterminada y con la fuente más
   grande que permita el dispositivo. Cambia entre las tres opciones y comprueba que aparecen los
   campos correctos: credenciales Xtream, URL o archivo M3U y MAC de Stalker.
2. Cambia la app a árabe o persa y reiníciala. Repite la selección y la navegación Atrás.
3. Gira el teléfono mientras editas campos, si la rotación de navegación está habilitada.

Pasa si las tres opciones siguen seleccionables, no se solapan con los campos y no se pierde lo
escrito por un simple cambio de orientación. No se exige en esta rama más pulido funcional de los
proveedores ni compatibilidad nueva con paneles concretos.

### Importación de M3U local

1. En «Añadir proveedor», selecciona M3U → «Archivo local», elige una `.m3u` o `.m3u8` IPTV válida
   menor de 50 MiB e impórtala. Comprueba nombre, categorías, canales y reproducción.
2. Cancela el selector de documentos sin elegir archivo. No debe crearse un proveedor ni alterarse
   otro existente.
3. Intenta importar, por separado, un archivo con extensión incorrecta, uno vacío o mal formado, un
   manifiesto HLS y una lista con rutas locales o URL relativas. Debe aparecer un error y no quedar
   un catálogo parcial.
4. Marca un canal favorito. Desde Ajustes → Proveedores usa «Volver a importar» con la segunda
   versión válida. Comprueba que los canales nuevos aparecen, los retirados desaparecen y el estado
   del canal estable —incluido el favorito— se conserva.
5. Inicia una reimportación y cancela antes de elegir archivo. La lista anterior debe seguir intacta.
   Si puedes interrumpir una copia grande navegando fuera, vuelve y comprueba también que el catálogo
   anterior continúa utilizable.
6. Después de una importación correcta, borra o mueve el archivo original con la app de archivos y
   reinicia OpenTV. La copia privada debe seguir cargando y reproduciendo la lista.
7. Ejecuta la actualización manual global. El catálogo de una lista local debe omitirse: no debe
   vaciarse ni fingir que relee el archivo original. Los proveedores remotos sí se actualizan.
8. Configura en «Avanzado» una URL XMLTV para la lista local y fuerza la actualización. La guía debe
   sincronizarse y emparejar canales. Repite sin URL XMLTV: la lista debe funcionar sin intentar
   obtener una guía desde el archivo M3U seleccionado.
9. Elimina el proveedor local, confirma la acción y reinicia. Sus canales y categorías no deben
   reaparecer. La app no ofrece borrar el documento original elegido por el usuario; solo elimina su
   copia privada.

### Reproductor: directo y VOD

Ejecuta la matriz con un canal en directo, una película y un episodio:

1. En «Tamaño», selecciona **Ajustar**, **Rellenar** y **Estirar**. El cambio debe aplicarse sin
   reconstruir la reproducción y mantenerse al salir y abrir otro contenido.
2. Verifica visualmente: Ajustar conserva toda la imagen con posibles bandas; Rellenar conserva la
   proporción y puede recortar; Estirar ocupa el marco y puede deformar.
3. En VOD, pausa y pulsa el botón PiP: al no haber reproducción activa, no debe entrar en PiP.
   Reanuda y repite: debe abrir la ventana flotante si el dispositivo lo admite.
4. Con vídeo reproduciéndose, pulsa Home. Debe entrar automáticamente en PiP. Cierra la ventana PiP
   desde el control del sistema: el audio/vídeo debe detenerse, no continuar oculto.
5. Desde el menú de orientación prueba Paisaje, Retrato, Sistema y Bloquear. Paisaje permite ambos
   sentidos horizontales; Retrato, ambos verticales; Sistema sigue la rotación autorizada por Android;
   Bloquear conserva la orientación física capturada al seleccionarlo.
6. Abre un contenido nuevo sin escoger orientación. El valor inicial de cada sesión debe ser Paisaje.
   Sal del reproductor: en teléfonos, la navegación vuelve por defecto a retrato salvo que esté
   habilitada la rotación automática de navegación. Repite tras entrar y salir de PiP; PiP no debe
   sobrescribir la elección de la sesión.

### Guía táctil del teléfono

1. Alterna «Lista» y «Guía». En Lista, confirma que ahora/siguiente siguen referidos al reloj actual,
   aunque antes hayas navegado a otro día en la cuadrícula.
2. Desliza horizontalmente por las horas y verticalmente por al menos 40 canales. El encabezado y
   cada fila deben conservar la misma ventana temporal y no mezclar programas entre canales.
3. Usa día anterior, día siguiente y «Ahora». Los límites deben coincidir con los datos XMLTV; los
   botones de día se deshabilitan al alcanzar el principio o el final disponible.
4. En una zona/fecha con cambio horario, comprueba un día de 23 h y uno de 25 h. Las etiquetas deben
    mostrar `HH:mm` local y añadir el desfase solo en horas repetidas (p. ej. `02:30 +02:00` y
    `02:30 +01:00`), sin huecos inventados, horas desplazadas ni programas duplicados.
5. Prueba programas de pocos minutos. Aunque el texto quede recortado, el bloque mantiene su duración
   proporcional y el programa también aparece en «Detalles» del canal con su horario completo.
6. Pulsa un programa pasado, uno en emisión y uno futuro. Según datos y capacidades reales deben
   aparecer las acciones aplicables: ver desde el inicio solo con catch-up, grabar/detener en emisión,
   programar grabación y recordatorio/cambio automático en el futuro, grabar serie y ver el canal.
   Cancela una acción y confirma que no crea nada.

### Paging en películas y series

1. Con dos proveedores que tengan una categoría con el mismo ID o nombre, selecciona «Todos» y abre
   cada categoría etiquetada con su proveedor. Cada una debe mostrar solo títulos de su origen.
2. Selecciona un proveedor concreto. Las categorías deben limitarse a él y la selección anterior debe
   restablecerse al inicio, no reutilizar una categoría del otro proveedor.
3. Abre una categoría vacía. Tras terminar la carga debe verse «No hay títulos en esta selección», no
   un indicador infinito. Si se corta el acceso a datos durante una carga, debe aparecer «Reintentar».
4. En «Ver todo» y en una categoría grande, desplázate mucho más de las 96 tarjetas iniciales. Busca
   saltos, duplicados, pérdidas en el límite de página y pausas perceptibles.
5. Desde una posición alejada, abre un detalle y vuelve. Debe restaurarse la posición de esa misma
   cuadrícula. Cambia de categoría o proveedor: el nuevo filtro debe empezar arriba. Repite en
   Películas y Series.

### Navegación horizontal en teléfono (fuera del reproductor)

1. Habilita la rotación de navegación y gira a horizontal. En Películas y Series, comprueba que no
   hay cabecera de marca ni pestañas inferiores fijas: los destinos están en la tira lateral
   desplazable y el logo abre búsqueda, actualización, perfiles y ajustes.
2. Búsqueda, proveedor (si hay varios), categorías/inicio y «Ver todo» comparten una sola fila
   desplazable horizontalmente. Abre los selectores, filtra categorías, cambia proveedor y vuelve
   desde un detalle abierto lejos del inicio de una cuadrícula: conserva selección y posición;
   cambiar filtro sigue empezando arriba. Repite con fuente grande y RTL.
3. En Directo, desplaza la fila de controles para acceder a Lista/Guía, búsqueda, categorías,
   recientes, favoritos y orden. La guía conserva espacio para filas bajo día/hora; bordes negros,
   sincronía horizontal y acciones de programa siguen intactos. Durante una sincronización, el
   estado inferior ocupa una sola línea, no un bloque multilínea.
4. En Ajustes, el título del índice sale con la lista al desplazar; en Pantalla las preferencias
   y cabecera siguen desplazándose juntas. Ningún control queda detrás de un recorte lateral.
   Vuelve a vertical y comprueba el diseño anterior; TV/tablet y controles/orientación del
   reproductor deben seguir iguales.

### Widget de canales recientes

1. Con la app cerrada y su proceso fuera de memoria, pulsa un canal del widget. Debe hacer arranque
   en frío y reproducir ese canal, sin abrir antes otro por «reanudar último canal».
2. Con la app abierta o en segundo plano, pulsa otro canal. Debe reanudar la actividad existente y
   dar prioridad al canal pulsado.
3. Activa PIN parental y oculta una categoría presente entre los recientes. El widget debe retirar
   sus canales y no volver a mostrarlos aunque la categoría se desbloquee solo para la sesión dentro
   de la app. Pulsa un widget que aún estuviera visualmente desactualizado: debe volver a validar y
   no abrir contenido protegido.
4. Elimina un proveedor que aportaba un canal reciente. El canal debe desaparecer; pulsar una entrada
   obsoleta no debe reproducir ni cerrar la app.
5. Desactiva TV en directo. El widget debe quedar vacío. Vuelve a activarla y comprueba la actualización.
6. Cambia la altura del widget. Debe mostrar entre cuatro y seis entradas según el espacio; cambia
   también el ancho y confirma que nombres/logos no salen del marco.

## P2 — regresiones

- Actualiza cada proveedor remoto y reinicia: favoritos, canales ocultos, nombres personalizados,
  orden y categorías seleccionadas deben sobrevivir cuando el canal conserva su identidad.
- Repite H2 con varios perfiles y confirma que cada perfil conserva su propio «Continuar viendo».
- Comprueba búsqueda de canales, películas y series; abre un resultado y vuelve sin perder navegación.
- Verifica reproducción de una descarga sin red y que guarda/reanuda posición.
- En TV en directo, cambia canales rápidamente y alterna calidades si existen; no debe quedar el
  reproductor bloqueado.
- Comprueba subtítulos, pista de audio, barra de progreso, avance/retroceso y autoocultado de controles
  en VOD después de usar los nuevos menús de tamaño y orientación.
- Fuerza una actualización XMLTV fallida después de haber cargado una guía válida. La guía anterior
  debe continuar disponible.
- Reinicia después de crear un recordatorio o una grabación futura; deben seguir listados y rearmados.
- Prueba tema claro/oscuro, fuente grande y un idioma RTL en las pantallas tocadas: selector, guía,
  catálogos paginados, reproductor y widget.

Google Cast/Chromecast y un pulido adicional de compatibilidad M3U/Stalker no forman parte de lo
implementado para validar en esta rama; no deben registrarse como fallos de aceptación de roadmap 1.1.

## Registro del resultado

Para cada fallo anota el caso, pasos exactos, resultado, dispositivo y proveedor. Adjunta un extracto
de log sin credenciales:

```bash
~/Library/Android/sdk/platform-tools/adb logcat -c
~/Library/Android/sdk/platform-tools/adb logcat -v threadtime > testrun.log
```

Reproduce el fallo, detén el comando con Ctrl+C y revisa el archivo antes de compartirlo. No publiques
URL, usuario, contraseña, MAC ni contenido privado de listas.
