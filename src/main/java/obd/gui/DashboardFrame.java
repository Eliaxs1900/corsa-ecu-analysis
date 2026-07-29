package obd.gui;

import obd.Elm327;
import obd.Kwp;
import obd.LiveDecoder;
import obd.DtcCatalog;

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
    private final JButton connectBtn = new JButton("Conectar");
    private final JButton recordBtn = new JButton("● Grabar CSV");
    private final JButton dtcBtn = new JButton("Averías");
    private final JLabel statusLbl = new JLabel("Desconectado");

    private JLabel rpmV, turboV, pedalV, coolV, oilV, battV;
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

    public static void launch() {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}
        SwingUtilities.invokeLater(() -> new DashboardFrame().setVisible(true));
    }

    public DashboardFrame() {
        super("Corsa OBD — Y17DTL · ECU motor · KWP2000");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(760, 500);
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
        recordBtn.setEnabled(false);
        dtcBtn.setEnabled(false);

        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) { disconnect(); }
        });
    }

    // ---------- construcción de UI ----------

    private JComponent buildTopBar() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        p.setBackground(BG);
        JButton refresh = new JButton("↻");
        refresh.addActionListener(e -> refreshPorts());
        portBox.setPreferredSize(new Dimension(240, 28));
        for (JComponent c : new JComponent[]{portBox, connectBtn, recordBtn, dtcBtn}) {
            c.setFocusable(false);
        }
        statusLbl.setForeground(ACCENT);
        p.add(new lbl("Puerto:"));
        p.add(portBox); p.add(refresh); p.add(connectBtn); p.add(recordBtn); p.add(dtcBtn);
        p.add(statusLbl);
        return p;
    }

    private JComponent buildCenter() {
        JPanel wrap = new JPanel(new BorderLayout(0, 8));
        wrap.setBackground(BG);

        boostBar = new JProgressBar(0, 120);   // 0 a 1.2 bar
        boostBar.setStringPainted(true);
        boostBar.setString("Turbo");
        boostBar.setForeground(ACCENT);
        wrap.add(boostBar, BorderLayout.NORTH);

        JPanel grid = new JPanel(new GridLayout(2, 3, 8, 8));
        grid.setBackground(BG);
        rpmV = new JLabel("—"); turboV = new JLabel("—"); pedalV = new JLabel("—");
        coolV = new JLabel("—"); oilV = new JLabel("—"); battV = new JLabel("—");
        grid.add(gauge("RPM", rpmV, "rpm"));
        grid.add(gauge("Turbo", turboV, "kPa · bar"));
        grid.add(gauge("Acelerador", pedalV, "%"));
        grid.add(gauge("Refrigerante", coolV, "°C"));
        grid.add(gauge("Temp. aceite", oilV, "°C"));
        grid.add(gauge("Batería", battV, "V"));
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

    private JPanel gauge(String title, JLabel value, String unit) {
        JPanel c = new JPanel();
        c.setLayout(new BoxLayout(c, BoxLayout.Y_AXIS));
        c.setBackground(CARD);
        c.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));
        JLabel t = new lbl(title); t.setForeground(MUTED);
        value.setForeground(Color.WHITE);
        value.setFont(new Font(Font.MONOSPACED, Font.BOLD, 34));
        JLabel u = new lbl(unit); u.setForeground(MUTED); u.setFont(u.getFont().deriveFont(11f));
        t.setAlignmentX(LEFT_ALIGNMENT); value.setAlignmentX(LEFT_ALIGNMENT); u.setAlignmentX(LEFT_ALIGNMENT);
        c.add(t); c.add(value); c.add(u);
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
        for (String p : Elm327.puertosDisponibles()) portBox.addItem(p);
        if (portBox.getItemCount() == 0) portBox.addItem("(sin puertos — empareja el V-LINK)");
    }

    private void toggleConnect() {
        if (connected) disconnect();
        else connect();
    }

    private void connect() {
        Object sel = portBox.getSelectedItem();
        if (sel == null) return;
        String port = sel.toString().trim().split("\\s+")[0];   // "COM8  (...)" -> "COM8"
        if (!port.matches("COM\\d+")) { status("Selecciona un COM válido", DANGER); return; }
        connectBtn.setEnabled(false);
        status("Conectando a " + port + "…", ACCENT);
        new Thread(() -> {
            try {
                Elm327 e = Elm327.abrir(port);
                e.send("ATZ", Elm327.TIMEOUT_INIT_MS);
                e.send("ATE0"); e.send("ATL0");
                Kwp k = new Kwp(e);
                k.init();
                String vin;
                try { vin = k.identificacion(0x90); } catch (IOException ex) { vin = null; }
                int dtcCount;
                try { dtcCount = k.leerDtc().size(); } catch (IOException ex) { dtcCount = -1; }
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
                SwingUtilities.invokeLater(() -> {
                    connectBtn.setEnabled(true);
                    status("Error: " + t.getMessage(), DANGER);
                });
                closeQuietly();
            }
        }, "conectar").start();
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
            connectBtn.setText("Conectar"); connectBtn.setEnabled(true);
            recordBtn.setEnabled(false); dtcBtn.setEnabled(false);
            portBox.setEnabled(true);
            status("Desconectado", MUTED);
        });
    }

    private void closeQuietly() {
        Elm327 e = elm; elm = null; kwp = null;
        if (e != null) try { e.close(); } catch (Exception ignored) {}
    }

    // ---------- actualización del cuadro ----------

    private void update(LiveDecoder.Live l, double hz) {
        boolean run = l.engineRunning();
        rpmV.setText(Integer.toString(l.rpm()));
        rpmV.setForeground(l.rpm() >= 4500 ? DANGER : l.rpm() >= 3000 ? WARN : Color.WHITE);
        // Aceite y turbo solo son fiables con el motor en marcha.
        turboV.setText(run ? l.boostKpa() + "  " + String.format("%+.2f", l.boostBar()) : "—");
        oilV.setText(run ? Integer.toString(l.oilC()) : "—");
        pedalV.setText(Integer.toString(l.pedalPct()));
        coolV.setText(l.coolantC() + "  (obj " + l.coolantTargetC() + ")");
        coolV.setForeground(l.coolantC() >= 105 ? DANGER : l.coolantC() < 60 ? COLD : OK);
        battV.setText(String.format("%.1f", l.voltage()));

        int pct = run ? (int) Math.max(0, Math.min(120, l.boostBar() * 100)) : 0;
        boostBar.setValue(pct);
        boostBar.setString(run ? String.format("Turbo  %+.2f bar", l.boostBar()) : "Turbo — (motor parado)");

        setPill(brakeI, l.brake()); setPill(clutchI, l.clutch());
        setPill(acI, l.ac()); setPill(fullI, l.fullLoad());
        statusLbl.setText(String.format("En vivo · %.1f Hz%s", hz, recording ? " · REC " + recCount : ""));
        statusLbl.setForeground(OK);
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

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
