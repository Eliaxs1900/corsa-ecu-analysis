# Registros `olog` del bloque `21 01` (para reanálisis)

CSV crudos capturados con el comando `olog` de la consola. Cada línea:
`t_ms,hora,datos_hex` donde `datos_hex` son los bytes del bloque `21 01` **ya sin
el prefijo `61 01`** (offset 0 = primer byte de datos). Ver `../docs/mapa-21-01.md`
para el significado de cada offset.

## Sesión del 28-jul-2026 (motor real, coche Y17DTL)

| Fichero | Estado del motor | Muestras | Notas |
|---|---|---|---|
| `olog-01-20260728-161600.csv` | **Calentamiento** frío→caliente (~10 min) | 1074 | refrigerante (off41) sube 43→87 °C; consigna (off38) fija en ~88 |
| `olog-01-20260728-163040.csv` | **Conducción dinámica 0–120 km/h** (~10 min) | 1193 | acelerones hasta 3092 rpm; turbo (off50) 100→~180 kPa |
| `olog-01-20260728-164159.csv` | **Ralentí caliente** (~2 min) | 294 | referencia base para restar y aislar canales |

Offsets clave confirmados con estos datos: `41`=refrigerante (÷2 °C), `40`=mismo
sensor crudo (NTC inverso), `38`=consigna termostato, `33-34`(y `31-32`)=rpm (÷8),
`30`=tensión (×0.234 V), `50`=presión turbo (kPa), `55`=pedal. Sin canal de
velocidad (la ECU no la recibe; coche sin ABS).
