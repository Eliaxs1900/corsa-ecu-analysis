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
java -jar target/obd-tools.jar [COMx]
```

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

## Siguiente paso si `probe` no encuentra nada

KWP2000 crudo con direccionamiento Opel por K-line:
`ATSP 5`, `ATSH 81 11 F1` (formato + ECU motor 0x11 + tester F1) y
`StartCommunication` (`81`), después `1A 90` (VIN) o `21 xx` (datos locales).
