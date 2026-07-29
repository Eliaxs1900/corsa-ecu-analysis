# corsa-obd-tools

Consola de diagnóstico OBD en Java para un **Opel Corsa C 1.7 DI (Y17DTL, 65 CV, 2003)**
usando un adaptador **Vgate iCar2 BT3.0** (ELM327 por Bluetooth SPP).

## Contexto importante

El EOBD solo fue obligatorio en diésel en la UE desde **2004**, así que este coche
está en zona gris: puede responder al modo OBD-II genérico, solo con init forzado
por K-line (ISO 9141-2 / ISO 14230 KWP2000), o únicamente con direccionamiento
propietario de Opel (estilo OP-COM). El comando `probe` recorre esa escalera.

## Preparación

1. Enchufa el iCar2 al conector OBD (bajo el volante) y pon el contacto (ignición).
2. En Windows: *Agregar dispositivo Bluetooth* → empareja el iCar2 (PIN típico `1234`).
3. Windows crea dos puertos COM; el útil es el **saliente** (Panel de control →
   Dispositivos Bluetooth → puertos COM).

## Compilar y ejecutar

```
mvn -q package
java -jar target/obd-tools.jar
```

Al ejecutar el jar se abre la **interfaz gráfica** (Swing): elige el puerto COM,
pulsa **Conectar** y verás el cuadro de mandos en vivo (rpm, turbo con barra,
acelerador, refrigerante, temp. aceite, batería + interruptores), igual que la app
Android. Incluye **grabación a CSV** y lectura de **averías (DTC)** con descripciones.

Para la consola de texto de siempre: `java -jar target/obd-tools.jar console [COMx]`.

## Comandos de la consola

| Comando | Qué hace |
|---|---|
| `ports` | Lista los puertos COM disponibles |
| `open COM5` | Abre el puerto e identifica el adaptador (ATZ) |
| `probe` | Detección de protocolo: auto → KWP rápido → KWP lento → ISO 9141-2 |
| `dtc` | Lee estado MIL (0101) y códigos de avería (modo 03) |
| `clear si` | Borra DTCs (modo 04) — requiere confirmación explícita |
| `live` | PIDs básicos: refrigerante, rpm, velocidad, presión admisión, carga |
| `trace on/off` | Vuelca la conversación cruda con el adaptador |
| *cualquier otra cosa* | Se envía tal cual al ELM327 (`ATI`, `ATRV`, `0100`, ...) |

### Comandos Opel (KWP2000, los que funcionan en este coche)

| Comando | Qué hace |
|---|---|
| `opel` | Abre sesión KWP2000 con la ECU del motor (0x11) y lee el VIN |
| `odtc` | Lee averías (servicio 18) |
| `oclear si` | Borra averías (servicio 14) |
| `oid` | Identificaciones de la ECU (servicio 1A) |
| `o21 <id>` | Lee un bloque de datos local (servicio 21) |
| `o21w <id> [n] [seg]` | Muestrea un bloque N veces y resalta los bytes que cambian |
| `oscan` | Barre los 256 identificadores del servicio 21 |
| `ohunt` | Busca ECUs probando direcciones KWP en la K-line |
| `kwp <hex...>` | Servicio KWP arbitrario con cabecera automática |

## Documentación

- [`docs/mapa-21-01.md`](docs/mapa-21-01.md) — mapa empírico del bloque de
  datos en vivo (rpm, temperaturas, pedal, tensión...), con la evidencia.
- [`docs/referencias.md`](docs/referencias.md) — hallazgos de la investigación
  en red (pinout Opel, protocolo KW-82, avería típica del EDU...).
- [`docs/fuentes/`](docs/fuentes/) — documentación externa archivada: datasheet
  del ELM327, esquema eléctrico Haynes del Y17DT, fusibles del Corsa C.
- [`fotografías-evidencias/`](fotografías-evidencias/) — fotos de la sesión
  inicial (conector, cuadro, display).

## Estado: protocolo resuelto (validado en el coche real)

Este coche **no responde a EOBD genérico** (`probe` agota la escalera), pero
habla **KWP2000 con init rápido y direccionamiento físico**: `ATSP 5` +
`ATSH 81 11 F1` + `ATFI` — es lo que hace el comando `opel`. Solo la ECU del
motor (0x11) es accesible por el pin 7; el resto de módulos del coche
(inmovilizador, display, airbag) cuelgan de los pines 3/8/12 con protocolo
KW-82, fuera del alcance de un ELM327 (haría falta un OP-COM).
