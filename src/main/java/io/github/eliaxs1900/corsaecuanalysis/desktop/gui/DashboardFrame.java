package io.github.eliaxs1900.corsaecuanalysis.desktop.gui;

import io.github.eliaxs1900.corsaecuanalysis.desktop.Elm327;
import io.github.eliaxs1900.corsaecuanalysis.core.Kwp;
import io.github.eliaxs1900.corsaecuanalysis.core.LiveDecoder;
import io.github.eliaxs1900.corsaecuanalysis.core.DtcCatalog;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Interfaz gráfica de escritorio (Swing) para el diagnóstico en vivo del Corsa:
 * el mismo cuadro de mandos que la app Android, ejecutando el jar con
 * {@code java -jar obd-tools.jar}. Reutiliza {@link Elm327} y {@link Kwp}.
 */
public class DashboardFrame extends JFrame {

    private static final Color BG = new Color(0x1E1E1E);
    private static final Color CARD = new Color(0x2B2B2B);
    private static final Color MUTED = new Color(0x9E9E9E);
    private static final Color ACCENT = new Color(0xB39DDB);
    private static final Color OK = new Color(0x81C784);
    private static final Color WARN = new Color(0xFFB74D);
    private static final Color DANGER = new Color(0xEF5350);
    private static final Color COLD = new Color(0x64B5F6);
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final JComboBox<String> portBox = new JComboBox<>();
    private final JButton connectBtn = new JButton("Conectar con el coche");
    private final JButton recordBtn = new JButton("● Grabar CSV");
    private final JButton dtcBtn = new JButton("Averías");
    private final JButton logsBtn = new JButton("Registros");
    private final JLabel statusLbl = new JLabel("Desconectado");

    private JLabel rpmV, turboV, pedalV, coolV, oilV, battV;          // cifras
    private JLabel rpmS, turboS, pedalS, coolS, oilS, battS;          // línea de contexto
    private JProgressBar boostBar;
    private JLabel brakeI, clutchI, acI, fullI;

    private volatile Elm327 elm;
    private volatile Kwp kwp;
    private volatile boolean connected;
    private Thread poller;
    private final Object bus = new Object();     // serializa el acceso al adaptador

    private PrintWriter rec;
    private int recCount;
    private volatile boolean recording;

    public static void launch() { launch(false); }

    /**
     * @param automatico si es true, además de mostrar la ventana escucha comandos
     *                   por la entrada estándar para pilotarla sin manos.
     */
    public static void launch(boolean automatico) {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}
        SwingUtilities.invokeLater(() -> {
            DashboardFrame f = new DashboardFrame();
            f.setVisible(true);
            if (automatico) f.escucharComandos();
        });
    }

    public DashboardFrame() {
        super("Corsa OBD — Y17DTL · ECU motor · KWP2000");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(900, 560);
        setMinimumSize(new Dimension(820, 520));
        setLocationRelativeTo(null);
        getContentPane().setBackground(BG);
        setLayout(new BorderLayout(8, 8));
        ((JComponent) getContentPane()).setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        add(buildTopBar(), BorderLayout.NORTH);
        add(buildCenter(), BorderLayout.CENTER);
        add(buildSwitches(), BorderLayout.SOUTH);

        refreshPorts();
        connectBtn.addActionListener(e -> toggleConnect());
        recordBtn.addActionListener(e -> toggleRecord());
        dtcBtn.addActionListener(e -> showDtc());
        logsBtn.addActionListener(e -> showLogs());   // disponible siempre, sin conectar
        recordBtn.setEnabled(false);
        dtcBtn.setEnabled(false);

        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) { disconnect(); }
        });
    }

    // ---------- construcción de UI ----------

    private JComponent buildTopBar() {
        JPanel col = new JPanel(new GridLayout(2, 1, 0, 2));
        col.setBackground(BG);

        // Fila 1: la acción principal. Buscar el adaptador solo, como en el móvil.
        JPanel fila1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        fila1.setBackground(BG);
        connectBtn.setFont(connectBtn.getFont().deriveFont(Font.BOLD));
        fila1.add(connectBtn);
        fila1.add(recordBtn); fila1.add(dtcBtn); fila1.add(logsBtn);
        fila1.add(statusLbl);

        // Fila 2: selección manual del puerto, solo por si hace falta.
        JPanel fila2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        fila2.setBackground(BG);
        JButton refresh = new JButton("↻");
        refresh.addActionListener(e -> refreshPorts());
        portBox.setPreferredSize(new Dimension(300, 24));
        JLabel avanzado = new lbl("Puerto (opcional):");
        avanzado.setForeground(MUTED);
        avanzado.setFont(avanzado.getFont().deriveFont(11f));
        fila2.add(avanzado); fila2.add(portBox); fila2.add(refresh);

        for (JComponent c : new JComponent[]{portBox, connectBtn, recordBtn, dtcBtn, logsBtn, refresh}) {
            c.setFocusable(false);
        }
        statusLbl.setForeground(ACCENT);
        col.add(fila1); col.add(fila2);
        return col;
    }

    private JComponent buildCenter() {
        JPanel wrap = new JPanel(new BorderLayout(0, 8));
        wrap.setBackground(BG);

        boostBar = new JProgressBar(0, 120);   // 0 a 1.2 bar
        boostBar.setStringPainted(true);
        boostBar.setString("Turbo");
        boostBar.setForeground(ACCENT);
        wrap.add(boostBar, BorderLayout.NORTH);

        JPanel grid = new JPanel(new GridLayout(2, 3, 10, 10));
        grid.setBackground(BG);
        rpmV = new JLabel("—"); turboV = new JLabel("—"); pedalV = new JLabel("—");
        coolV = new JLabel("—"); oilV = new JLabel("—"); battV = new JLabel("—");
        rpmS = new JLabel(); turboS = new JLabel(); pedalS = new JLabel();
        coolS = new JLabel(); oilS = new JLabel(); battS = new JLabel();
        grid.add(gauge("RPM", rpmV, rpmS, "rpm"));
        grid.add(gauge("Turbo", turboV, turboS, "kPa"));
        grid.add(gauge("Acelerador", pedalV, pedalS, "%"));
        grid.add(gauge("Refrigerante", coolV, coolS, "°C"));
        grid.add(gauge("Admisión", oilV, oilS, "°C estimado"));
        grid.add(gauge("Batería", battV, battS, "voltios"));
        wrap.add(grid, BorderLayout.CENTER);
        return wrap;
    }

    private JComponent buildSwitches() {
        JPanel p = new JPanel(new GridLayout(1, 4, 8, 0));
        p.setBackground(BG);
        brakeI = pill("Freno"); clutchI = pill("Embrague"); acI = pill("A/C"); fullI = pill("Plena carga");
        p.add(brakeI); p.add(clutchI); p.add(acI); p.add(fullI);
        return p;
    }

    /**
     * Tarjeta de métrica al estilo Fluent: título pequeño arriba, cifra grande
     * y una línea de contexto debajo (unidad y detalle). El contexto va en su
     * propia etiqueta, no pegado a la cifra: así nunca se corta el texto.
     */
    private JPanel gauge(String title, JLabel value, JLabel sub, String unitPorDefecto) {
        JPanel c = new JPanel();
        c.setLayout(new BoxLayout(c, BoxLayout.Y_AXIS));
        c.setBackground(CARD);
        c.setBorder(BorderFactory.createEmptyBorder(8, 14, 8, 14));

        JLabel t = new lbl(title);
        t.setForeground(MUTED);
        t.setFont(t.getFont().deriveFont(Font.PLAIN, 12f));

        value.setForeground(Color.WHITE);
        value.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 30));

        sub.setText(unitPorDefecto);
        sub.setForeground(MUTED);
        sub.setFont(sub.getFont().deriveFont(Font.PLAIN, 11f));

        t.setAlignmentX(LEFT_ALIGNMENT);
        value.setAlignmentX(LEFT_ALIGNMENT);
        sub.setAlignmentX(LEFT_ALIGNMENT);
        c.add(t);
        c.add(Box.createVerticalGlue());
        c.add(value);
        c.add(sub);
        c.add(Box.createVerticalGlue());
        return c;
    }

    private JLabel pill(String text) {
        JLabel l = new JLabel(text, SwingConstants.CENTER);
        l.setOpaque(true);
        l.setBackground(new Color(0x3A3A3A));
        l.setForeground(MUTED);
        l.setBorder(BorderFactory.createEmptyBorder(8, 6, 8, 6));
        return l;
    }

    /** Etiqueta con color de texto claro por defecto. */
    private static class lbl extends JLabel {
        lbl(String t) { super(t); setForeground(Color.WHITE); }
    }

    // ---------- conexión ----------

    private void refreshPorts() {
        portBox.removeAllItems();
        // Primera opción: que lo busque la app. Solo hace falta tocar esto si falla.
        portBox.addItem("(buscar automáticamente)");
        for (String p : Elm327.puertosDisponibles()) portBox.addItem(p);
        portBox.setSelectedIndex(0);
    }

    private void toggleConnect() {
        if (connected) disconnect();
        else connect();
    }

    private void connect() {
        Object sel = portBox.getSelectedItem();
        // "(buscar automáticamente)" o cualquier cosa que no sea un COM => autodetección
        String elegido = sel == null ? "" : sel.toString().trim().split("\\s+")[0];
        final String port = elegido.matches("COM\\d+") ? elegido : null;
        log("clic en Conectar; puerto = " + (port == null ? "AUTODETECTAR" : port));
        connectBtn.setEnabled(false);
        status(port == null ? "Buscando el adaptador…" : "Conectando a " + port + "…", ACCENT);
        new Thread(() -> {
            try {
                Elm327 e;
                if (port == null) {
                    e = Elm327.detectarAdaptador(msg -> {
                        log(msg);
                        SwingUtilities.invokeLater(() -> status(msg, ACCENT));
                    });
                    if (e == null) {
                        throw new IOException("No se encontró ningún adaptador. Comprueba que está "
                                + "enchufado al coche, encendido (pulsa su botón si lo tiene) y emparejado.");
                    }
                    e.setTraza(true);
                } else {
                    log("abriendo " + port + "…");
                    e = Elm327.abrir(port);
                    e.setTraza(true);                    // vuelca la conversación a consola
                    log("puerto abierto; enviando ATZ");
                    log("ATZ -> " + e.send("ATZ", Elm327.TIMEOUT_INIT_MS));
                }
                e.send("ATE0"); e.send("ATL0");
                log("iniciando sesión KWP2000 con la ECU 0x11");
                Kwp k = new Kwp(e);
                k.init();
                log("init KWP OK");
                String vin;
                try { vin = k.identificacion(0x90); } catch (IOException ex) { vin = null; log("VIN falló: " + ex.getMessage()); }
                int dtcCount;
                try { dtcCount = k.leerDtc().size(); } catch (IOException ex) { dtcCount = -1; log("DTC falló: " + ex.getMessage()); }
                log("VIN=" + vin + " DTCs=" + dtcCount);
                this.elm = e; this.kwp = k; this.connected = true;
                final String fvin = vin; final int fdtc = dtcCount;
                SwingUtilities.invokeLater(() -> {
                    connectBtn.setText("Desconectar"); connectBtn.setEnabled(true);
                    recordBtn.setEnabled(true); dtcBtn.setEnabled(true);
                    portBox.setEnabled(false);
                    String d = fdtc < 0 ? "" : (fdtc == 0 ? " · Sin averías" : " · " + fdtc + " DTC");
                    status("En vivo" + (fvin != null ? " · " + fvin : "") + d, OK);
                });
                startPolling();
            } catch (Throwable t) {
                log("FALLO: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                t.printStackTrace(System.out);
                SwingUtilities.invokeLater(() -> {
                    connectBtn.setEnabled(true);
                    status("Error: " + t.getMessage(), DANGER);
                });
                closeQuietly();
            }
        }, "conectar").start();
    }

    /** Traza con marca de tiempo a la consola (para diagnóstico). */
    private static void log(String msg) {
        System.out.println("[" + LocalTime.now().format(HORA) + "] " + msg);
        System.out.flush();
    }

    // ---------- automatización: pulsar los controles por comando ----------

    /**
     * Ejecuta una acción de la interfaz **emulando la pulsación real** del control
     * ({@code doClick()}), no llamando al método por detrás: así se prueba el mismo
     * camino que sigue el usuario. Pensado para auto-diagnóstico sin manos.
     *
     * <p>Comandos: {@code ports}, {@code port COM8}, {@code connect}, {@code rec},
     * {@code dtc}, {@code logs}, {@code status}, {@code datos}, {@code quit}.
     */
    public void ejecutarComando(String linea) {
        String[] p = linea.trim().split("\\s+", 2);
        String cmd = p[0].toLowerCase();
        String arg = p.length > 1 ? p[1].trim() : "";
        switch (cmd) {
            case "ports" -> {
                log("puertos en el desplegable:");
                for (int i = 0; i < portBox.getItemCount(); i++) log("  [" + i + "] " + portBox.getItemAt(i));
            }
            case "port" -> {
                boolean hallado = false;
                for (int i = 0; i < portBox.getItemCount(); i++) {
                    if (portBox.getItemAt(i).startsWith(arg)) {
                        final int idx = i;
                        SwingUtilities.invokeLater(() -> portBox.setSelectedIndex(idx));
                        log("seleccionado " + portBox.getItemAt(i));
                        hallado = true;
                        break;
                    }
                }
                if (!hallado) log("no encontrado el puerto '" + arg + "'");
            }
            case "connect" -> { log("[auto] clic en Conectar"); SwingUtilities.invokeLater(connectBtn::doClick); }
            case "rec" -> { log("[auto] clic en Grabar"); SwingUtilities.invokeLater(recordBtn::doClick); }
            case "dtc" -> { log("[auto] clic en Averías"); SwingUtilities.invokeLater(dtcBtn::doClick); }
            case "logs" -> { log("[auto] clic en Registros"); SwingUtilities.invokeLater(logsBtn::doClick); }
            case "status" -> log("estado: " + statusLbl.getText()
                    + " | conectado=" + connected
                    + " | botón=" + connectBtn.getText());
            case "datos" -> log("valores: rpm=" + rpmV.getText() + " turbo=" + turboV.getText()
                    + " pedal=" + pedalV.getText() + " refrig=" + coolV.getText()
                    + " admision=" + oilV.getText() + " bateria=" + battV.getText());
            case "quit" -> { log("[auto] saliendo"); disconnect(); System.exit(0); }
            default -> log("comando desconocido: '" + cmd + "'");
        }
    }

    /**
     * Lee comandos de la entrada estándar y los ejecuta sobre la interfaz.
     * Permite pilotar la ventana desde un script mientras se ve en pantalla.
     */
    public void escucharComandos() {
        Thread t = new Thread(() -> {
            log("modo automático listo (ports | port COMx | connect | rec | dtc | logs | status | datos | quit)");
            try (java.io.BufferedReader in = new java.io.BufferedReader(
                    new java.io.InputStreamReader(System.in))) {
                String linea;
                while ((linea = in.readLine()) != null) {
                    if (!linea.isBlank()) ejecutarComando(linea);
                }
            } catch (Exception e) {
                log("fin de la entrada de comandos: " + e.getMessage());
            }
        }, "auto-comandos");
        t.setDaemon(true);
        t.start();
    }

    private void startPolling() {
        poller = new Thread(() -> {
            long lastTs = System.currentTimeMillis();
            while (connected) {
                long t0 = System.currentTimeMillis();
                try {
                    List<Integer> block;
                    synchronized (bus) { block = kwp.datosLocales(0x01); }
                    long dur = System.currentTimeMillis() - t0;
                    List<Integer> payload = block.subList(2, block.size());   // sin 61 01
                    if (recording) writeSample(t0, dur, payload, kwp.ultimaRespuestaCruda());
                    LiveDecoder.Live live = LiveDecoder.decode(payload);
                    long now = System.currentTimeMillis();
                    double hz = now > lastTs ? 1000.0 / (now - lastTs) : 0;
                    lastTs = now;
                    if (live != null) SwingUtilities.invokeLater(() -> update(live, hz));
                } catch (Throwable t) {
                    SwingUtilities.invokeLater(() -> status("Reintentando: " + t.getMessage(), WARN));
                    try { synchronized (bus) { kwp.init(); } } catch (Exception ignored) {}
                    sleep(500);
                }
                sleep(300);
            }
        }, "sondeo");
        poller.setDaemon(true);
        poller.start();
    }

    private void disconnect() {
        connected = false;
        stopRecording();
        if (poller != null) poller.interrupt();
        closeQuietly();
        SwingUtilities.invokeLater(() -> {
            connectBtn.setText("Conectar con el coche"); connectBtn.setEnabled(true);
            recordBtn.setEnabled(false); dtcBtn.setEnabled(false);
            portBox.setEnabled(true);
            limpiarValores();     // que no queden datos viejos aparentando estar en vivo
            status("Desconectado", MUTED);
        });
    }

    /** Deja el cuadro en blanco: los datos de la sesión anterior ya no son válidos. */
    private void limpiarValores() {
        for (JLabel v : new JLabel[]{rpmV, turboV, pedalV, coolV, oilV, battV}) {
            v.setText("—");
            v.setForeground(Color.WHITE);
        }
        rpmS.setText("rpm"); turboS.setText("kPa"); pedalS.setText("%");
        coolS.setText("°C"); oilS.setText("°C estimado"); battS.setText("voltios");
        boostBar.setValue(0);
        boostBar.setString("Turbo");
        setPill(brakeI, false); setPill(clutchI, false);
        setPill(acI, false); setPill(fullI, false);
    }

    private void closeQuietly() {
        Elm327 e = elm; elm = null; kwp = null;
        if (e != null) try { e.close(); } catch (Exception ignored) {}
    }

    // ---------- actualización del cuadro ----------

    private void update(LiveDecoder.Live l, double hz) {
        boolean pw = l.ecuPowered();            // llave en 2 o motor en marcha => datos válidos
        boolean map = pw && l.boostKpa() > 0;   // el MAP solo se refresca girando el motor
        // Cifra grande + línea de contexto aparte (nunca se corta el texto).
        rpmV.setText(pw ? Integer.toString(l.rpm()) : "—");
        rpmV.setForeground(l.rpm() >= 4500 ? DANGER : l.rpm() >= 3000 ? WARN : Color.WHITE);
        rpmS.setText(pw ? (l.rpm() == 0 ? "rpm · motor parado" : "rpm") : "sin alimentación");

        turboV.setText(map ? Integer.toString(l.boostKpa()) : "—");
        turboS.setText(map ? String.format("kPa · %+.2f bar", l.boostBar())
                : (pw ? "motor parado" : "sin alimentación"));

        pedalV.setText(pw ? Integer.toString(l.pedalPct()) : "—");
        pedalS.setText(pw ? "%" : "sin alimentación");

        coolV.setText(pw ? Integer.toString(l.coolantC()) : "—");
        coolV.setForeground(l.coolantC() >= 105 ? DANGER : l.coolantC() < 60 ? COLD : OK);
        coolS.setText(pw ? "°C · objetivo " + l.coolantTargetC() + " °C" : "sin alimentación");

        oilV.setText(pw ? "≈" + l.intakeApproxC() : "—");
        oilS.setText(pw ? "°C estimado · lectura " + l.intakeRaw() : "sin alimentación");

        battV.setText(String.format("%.1f", l.voltage()));
        battS.setText(l.voltage() > 13 ? "voltios · cargando" : "voltios");

        int pct = map ? (int) Math.max(0, Math.min(120, l.boostBar() * 100)) : 0;
        boostBar.setValue(pct);
        boostBar.setString(map ? String.format("Turbo  %+.2f bar", l.boostBar())
                : (pw ? "Turbo — (motor parado)" : "ECU sin alimentación"));

        setPill(brakeI, l.brake()); setPill(clutchI, l.clutch());
        setPill(acI, l.ac()); setPill(fullI, l.fullLoad());
        if (pw) {
            statusLbl.setText(String.format("En vivo · %.1f Hz%s", hz, recording ? " · REC " + recCount : ""));
            statusLbl.setForeground(OK);
        } else {
            statusLbl.setText("ECU sin alimentacion (llave en 0) - datos no validos");
            statusLbl.setForeground(DANGER);
        }
    }

    private void setPill(JLabel p, boolean on) {
        p.setBackground(on ? ACCENT : new Color(0x3A3A3A));
        p.setForeground(on ? Color.BLACK : MUTED);
    }

    private void status(String s, Color c) { statusLbl.setText(s); statusLbl.setForeground(c); }

    // ---------- grabación CSV ----------

    private void toggleRecord() {
        if (recording) stopRecording();
        else startRecording();
    }

    private void startRecording() {
        try {
            Path dir = Path.of("logs");
            Files.createDirectories(dir);
            String name = "gui-01-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".csv";
            rec = new PrintWriter(Files.newBufferedWriter(dir.resolve(name)));
            rec.println("t_ms,hora,dur_ms,datos_hex,raw_completo");
            recCount = 0; recording = true;
            recordBtn.setText("■ Detener");
            status("Grabando en logs/" + name, ACCENT);
        } catch (IOException ex) {
            status("No se pudo grabar: " + ex.getMessage(), DANGER);
        }
    }

    private synchronized void writeSample(long t0, long dur, List<Integer> data, String raw) {
        if (rec == null) return;
        StringBuilder hex = new StringBuilder();
        for (int b : data) hex.append(String.format("%02X ", b));
        String rawClean = raw.replace("\r", " ").replace("\n", " ").replace(",", ";").trim();
        rec.printf("%d,%s,%d,%s,%s%n", t0, LocalTime.now().format(HORA), dur, hex.toString().trim(), rawClean);
        rec.flush();
        recCount++;
    }

    private void stopRecording() {
        recording = false;
        if (rec != null) { rec.flush(); rec.close(); rec = null; }
        SwingUtilities.invokeLater(() -> recordBtn.setText("● Grabar CSV"));
    }

    // ---------- averías ----------

    private void showDtc() {
        dtcBtn.setEnabled(false);
        new Thread(() -> {
            List<Kwp.Dtc> dtcs;
            try { synchronized (bus) { dtcs = kwp.leerDtc(); } }
            catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(this, "Error al leer averías: " + ex.getMessage());
                    dtcBtn.setEnabled(true);
                });
                return;
            }
            StringBuilder sb = new StringBuilder();
            if (dtcs.isEmpty()) sb.append("No hay averías almacenadas en la ECU del motor.");
            else for (Kwp.Dtc d : dtcs) {
                sb.append(d.codigo()).append("  ").append(DtcCatalog.describe(d.codigo()))
                        .append(d.activo() ? "  [ACTIVO]" : "").append("\n");
            }
            SwingUtilities.invokeLater(() -> {
                Object[] opts = dtcs.isEmpty() ? new Object[]{"Cerrar"} : new Object[]{"Borrar averías", "Cerrar"};
                int r = JOptionPane.showOptionDialog(this, sb.toString(), "Averías (DTC)",
                        JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, opts, opts[opts.length - 1]);
                if (!dtcs.isEmpty() && r == 0) clearDtc();
                dtcBtn.setEnabled(true);
            });
        }, "dtc").start();
    }

    private void clearDtc() {
        new Thread(() -> {
            boolean ok;
            try { synchronized (bus) { ok = kwp.borrarDtc(); } } catch (Exception ex) { ok = false; }
            final boolean fok = ok;
            SwingUtilities.invokeLater(() ->
                    JOptionPane.showMessageDialog(this, fok ? "Averías borradas." : "La ECU no confirmó el borrado."));
        }, "clear-dtc").start();
    }

    // ---------- gestión de registros (consultar / exportar / eliminar) ----------

    /**
     * Ventana de registros: lista a la izquierda, detalle a la derecha y acciones
     * abajo. Redimensionable y con el mismo tema oscuro que el resto de la app.
     */
    private void showLogs() {
        java.io.File dir = new java.io.File("logs");

        JDialog d = new JDialog(this, "Registros guardados", true);
        d.getContentPane().setBackground(BG);
        d.setLayout(new BorderLayout(10, 10));
        ((JComponent) d.getContentPane()).setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        DefaultListModel<java.io.File> modelo = new DefaultListModel<>();
        JList<java.io.File> lista = new JList<>(modelo);
        lista.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        lista.setBackground(CARD);
        lista.setForeground(Color.WHITE);
        lista.setFixedCellHeight(46);
        lista.setCellRenderer(new FichaRegistro());

        // Panel de detalle: campos con formato, no texto plano
        JPanel detalle = new JPanel();
        detalle.setLayout(new BoxLayout(detalle, BoxLayout.Y_AXIS));
        detalle.setBackground(CARD);
        detalle.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));

        JLabel tituloDet = new lbl("Selecciona un registro");
        tituloDet.setFont(tituloDet.getFont().deriveFont(Font.BOLD, 13f));
        JLabel[] campos = new JLabel[6];
        detalle.add(tituloDet);
        detalle.add(Box.createVerticalStrut(10));
        for (int i = 0; i < campos.length; i++) {
            campos[i] = new lbl(" ");
            campos[i].setForeground(MUTED);
            campos[i].setAlignmentX(LEFT_ALIGNMENT);
            detalle.add(campos[i]);
            detalle.add(Box.createVerticalStrut(4));
        }
        tituloDet.setAlignmentX(LEFT_ALIGNMENT);
        detalle.add(Box.createVerticalGlue());

        JScrollPane scrollLista = new JScrollPane(lista);
        scrollLista.setBorder(BorderFactory.createEmptyBorder());
        scrollLista.getViewport().setBackground(CARD);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, scrollLista, detalle);
        split.setDividerLocation(320);
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setBackground(BG);
        d.add(split, BorderLayout.CENTER);

        JLabel resumenTotal = new lbl(" ");
        resumenTotal.setForeground(MUTED);
        d.add(resumenTotal, BorderLayout.NORTH);

        // --- acciones ---
        JButton abrir = new JButton("Abrir carpeta");
        JButton exportar = new JButton("Exportar…");
        JButton borrar = new JButton("Eliminar");
        JButton borrarTodos = new JButton("Eliminar todos");
        JButton cerrar = new JButton("Cerrar");
        JPanel acciones = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
        acciones.setBackground(BG);
        acciones.add(abrir); acciones.add(exportar); acciones.add(borrar);
        acciones.add(borrarTodos); acciones.add(cerrar);
        d.add(acciones, BorderLayout.SOUTH);

        Runnable recargar = () -> {
            modelo.clear();
            java.io.File[] fs = dir.listFiles((p, n) -> n.endsWith(".csv"));
            if (fs != null) {
                java.util.Arrays.sort(fs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                long total = 0;
                for (java.io.File f : fs) { modelo.addElement(f); total += f.length(); }
                resumenTotal.setText(String.format("%d registro(s) · %.1f KB en total", fs.length, total / 1024.0));
            } else {
                resumenTotal.setText("No hay registros todavía. Pulsa «Grabar CSV» durante una sesión en vivo.");
            }
            boolean hay = modelo.size() > 0;
            for (JButton b : new JButton[]{exportar, borrar, borrarTodos}) b.setEnabled(hay);
            if (hay) lista.setSelectedIndex(0);
            else { tituloDet.setText("Sin registros"); for (JLabel c : campos) c.setText(" "); }
        };

        lista.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || lista.getSelectedValue() == null) return;
            java.io.File f = lista.getSelectedValue();
            tituloDet.setText(f.getName());
            for (JLabel c : campos) c.setText("  analizando…");
            new Thread(() -> {
                String[] datos = resumirCsv(f);
                SwingUtilities.invokeLater(() -> {
                    for (int i = 0; i < campos.length; i++) {
                        campos[i].setText(i < datos.length ? datos[i] : " ");
                    }
                });
            }, "resumen-csv").start();
        });

        abrir.addActionListener(e -> {
            try { Desktop.getDesktop().open(dir.getAbsoluteFile()); }
            catch (Exception ex) { JOptionPane.showMessageDialog(d, "No se pudo abrir la carpeta: " + ex.getMessage()); }
        });
        exportar.addActionListener(e -> {
            java.io.File f = lista.getSelectedValue();
            if (f == null) return;
            JFileChooser fc = new JFileChooser();
            fc.setSelectedFile(new java.io.File(f.getName()));
            fc.setDialogTitle("Exportar registro");
            if (fc.showSaveDialog(d) == JFileChooser.APPROVE_OPTION) {
                try {
                    Files.copy(f.toPath(), fc.getSelectedFile().toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    JOptionPane.showMessageDialog(d, "Exportado a:\n" + fc.getSelectedFile().getAbsolutePath());
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(d, "No se pudo exportar: " + ex.getMessage());
                }
            }
        });
        borrar.addActionListener(e -> {
            java.io.File f = lista.getSelectedValue();
            if (f == null) return;
            int c = JOptionPane.showConfirmDialog(d,
                    "¿Eliminar «" + f.getName() + "»?\nExpórtalo antes si quieres conservarlo.",
                    "Confirmar borrado", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (c == JOptionPane.YES_OPTION) { f.delete(); recargar.run(); }
        });
        borrarTodos.addActionListener(e -> {
            int c = JOptionPane.showConfirmDialog(d,
                    "¿Eliminar los " + modelo.size() + " registros?\nEsta acción no se puede deshacer.",
                    "Confirmar borrado", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (c == JOptionPane.YES_OPTION) {
                for (int i = 0; i < modelo.size(); i++) modelo.get(i).delete();
                recargar.run();
            }
        });
        cerrar.addActionListener(e -> d.dispose());

        recargar.run();
        d.setSize(860, 480);
        d.setMinimumSize(new Dimension(720, 400));
        d.setLocationRelativeTo(this);
        d.setVisible(true);
    }

    /** Dibuja cada registro con su nombre y, debajo, tamaño y fecha en tono suave. */
    private class FichaRegistro extends JPanel implements ListCellRenderer<java.io.File> {
        private final JLabel nombre = new JLabel();
        private final JLabel meta = new JLabel();
        private final java.time.format.DateTimeFormatter df =
                java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

        FichaRegistro() {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
            nombre.setFont(nombre.getFont().deriveFont(Font.BOLD, 12f));
            meta.setFont(meta.getFont().deriveFont(Font.PLAIN, 11f));
            nombre.setAlignmentX(LEFT_ALIGNMENT); meta.setAlignmentX(LEFT_ALIGNMENT);
            add(nombre); add(meta);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends java.io.File> l, java.io.File f,
                                                      int i, boolean sel, boolean foco) {
            nombre.setText(f.getName());
            String fecha = java.time.LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(f.lastModified()), java.time.ZoneId.systemDefault()).format(df);
            meta.setText(String.format("%.1f KB · %s", f.length() / 1024.0, fecha));
            setBackground(sel ? ACCENT.darker() : CARD);
            nombre.setForeground(sel ? Color.WHITE : Color.WHITE);
            meta.setForeground(sel ? Color.WHITE : MUTED);
            setOpaque(true);
            return this;
        }
    }

    /** Lee un CSV grabado y devuelve sus datos ya formateados, una línea por campo. */
    private String[] resumirCsv(java.io.File f) {
        int muestras = 0, errores = 0;
        long t0 = 0, t1 = 0;
        int rpmMin = Integer.MAX_VALUE, rpmMax = Integer.MIN_VALUE;
        int refMin = Integer.MAX_VALUE, refMax = Integer.MIN_VALUE;
        int turMin = Integer.MAX_VALUE, turMax = Integer.MIN_VALUE;
        int pedMin = Integer.MAX_VALUE, pedMax = Integer.MIN_VALUE;
        try (java.io.BufferedReader in = java.nio.file.Files.newBufferedReader(f.toPath())) {
            String ln;
            while ((ln = in.readLine()) != null) {
                String[] p = ln.split(",");
                if (p.length < 3 || p[0].equals("t_ms")) continue;
                // formato antiguo: t_ms,hora,datos_hex | nuevo: t_ms,hora,dur_ms,datos_hex,raw
                String datos = p.length >= 5 ? p[3] : p[2];
                if (datos.contains("ERROR")) { errores++; continue; }
                List<Integer> bytes = new java.util.ArrayList<>();
                for (String tok : datos.trim().split("\\s+")) {
                    if (tok.matches("[0-9A-Fa-f]{2}")) bytes.add(Integer.parseInt(tok, 16));
                }
                if (bytes.size() < 60) continue;
                LiveDecoder.Live l = LiveDecoder.decode(bytes);
                if (l == null) continue;
                muestras++;
                try {
                    long t = Long.parseLong(p[0]);
                    if (t0 == 0) t0 = t;
                    t1 = t;
                } catch (NumberFormatException ignored) { }
                if (l.ecuPowered()) {
                    rpmMin = Math.min(rpmMin, l.rpm()); rpmMax = Math.max(rpmMax, l.rpm());
                    refMin = Math.min(refMin, l.coolantC()); refMax = Math.max(refMax, l.coolantC());
                    pedMin = Math.min(pedMin, l.pedalPct()); pedMax = Math.max(pedMax, l.pedalPct());
                    if (l.boostKpa() > 0) {
                        turMin = Math.min(turMin, l.boostKpa()); turMax = Math.max(turMax, l.boostKpa());
                    }
                }
            }
        } catch (Exception ex) {
            return new String[]{"  No se pudo leer: " + ex.getMessage()};
        }
        long seg = (t1 - t0) / 1000;
        String duracion = seg >= 60 ? String.format("%d min %d s", seg / 60, seg % 60) : seg + " s";
        return new String[]{
                String.format("  %,d muestras   ·   %s%s", muestras, duracion,
                        errores > 0 ? "   ·   " + errores + " errores" : ""),
                rpmMin <= rpmMax ? String.format("  Régimen          %,d – %,d rpm", rpmMin, rpmMax) : " ",
                refMin <= refMax ? String.format("  Refrigerante     %d – %d °C", refMin, refMax) : " ",
                turMin <= turMax ? String.format("  Turbo            %d – %d kPa", turMin, turMax) : " ",
                pedMin <= pedMax ? String.format("  Acelerador       %d – %d %%", pedMin, pedMax) : " ",
                "  " + f.getParent(),
        };
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
