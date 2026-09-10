package com.arkhos.stratus.ui;

import com.arkhos.stratus.session.SessionListener;
import com.arkhos.stratus.terminal.ScreenSnapshot;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Ventana Swing que muestra la pantalla de la sesión Stratus VOS en tiempo real.
 *
 * <p>Implementa {@link SessionListener} para recibir actualizaciones directamente
 * desde la sesión. Úsalo como un observador opcional: la automatización funciona
 * igual con o sin él.</p>
 *
 * <pre>
 *   ScreenViewer viewer = new ScreenViewer("Stratus VOS — prod");
 *   viewer.show();
 *   session.addListener(viewer);
 *   session.connect()
 *          .waitForText("Username:", 5_000)
 *          ...
 *   // Capturar pantalla en cualquier momento
 *   viewer.saveScreenshot("capturas/");          // guarda con nombre automático
 *   viewer.saveScreenshot(new File("login.png")); // nombre explícito
 *   BufferedImage img = viewer.captureScreenshot(); // solo imagen, sin guardar
 * </pre>
 *
 * <p>Todos los métodos son seguros para llamar desde cualquier hilo.</p>
 */
public final class ScreenViewer implements SessionListener {

    // ── Colores — terminal clásica verde sobre negro ──────────────────────────
    private static final Color COLOR_BG_PANTALLA  = new Color(0x0d, 0x0d, 0x0d);
    private static final Color COLOR_FG_TEXTO     = new Color(0x39, 0xd3, 0x53);
    private static final Color COLOR_CURSOR       = new Color(0x39, 0xd3, 0x53, 200);
    private static final Color COLOR_BG_STATUSBAR = new Color(0x1a, 0x1a, 0x1a);
    private static final Color COLOR_FG_STATUS    = new Color(0x88, 0x88, 0x88);

    private static final int MARGEN    = 2;   // px de padding alrededor del texto
    private static final int FONT_SIZE = 14;

    // ── Estado ────────────────────────────────────────────────────────────────
    private final String        titulo;
    private final JFrame        frame;
    private final PanelTerminal panel;
    private final JLabel        barraEstado;

    private volatile ScreenSnapshot ultimoSnap;
    private volatile String         estadoConexion = "Sin conexión";

    // ── Constructor ───────────────────────────────────────────────────────────

    /**
     * Crea el visor pero no lo muestra todavía. Llama a {@link #show()} para abrirlo.
     *
     * @param titulo texto que aparece en la barra de título de la ventana
     * @throws IllegalStateException si el entorno no tiene pantalla (servidor headless)
     */
    public ScreenViewer(String titulo) {
        if (GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException(
                    "ScreenViewer requiere entorno gráfico — no disponible en modo headless");
        }
        this.titulo      = titulo;
        this.panel       = new PanelTerminal();
        this.barraEstado = crearBarraEstado();
        this.frame       = crearVentana();
    }

    // ── API pública: ventana ──────────────────────────────────────────────────

    /**
     * Muestra la ventana. Si ya estaba visible, la trae al frente.
     * Seguro de llamar desde cualquier hilo.
     */
    public void show() {
        SwingUtilities.invokeLater(() -> {
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
            frame.toFront();
        });
    }

    /** Oculta la ventana sin destruirla. Se puede volver a mostrar con {@link #show()}. */
    public void hide() {
        SwingUtilities.invokeLater(() -> frame.setVisible(false));
    }

    /** Libera todos los recursos de la ventana. No puede reutilizarse después. */
    public void dispose() {
        SwingUtilities.invokeLater(frame::dispose);
    }

    // ── API pública: screenshot ───────────────────────────────────────────────

    /**
     * Renderiza el estado actual de la pantalla a un {@link BufferedImage}.
     *
     * <p>No requiere que la ventana esté visible. El renderizado es off-screen
     * y siempre produce un resultado consistente independientemente del estado
     * de la ventana.</p>
     *
     * <p>La imagen incluye la barra de estado en la parte inferior con el
     * estado de conexión, posición del cursor y timestamp.</p>
     *
     * @return imagen con la pantalla actual, o {@code null} si aún no se ha
     *         recibido ningún dato del host
     */
    public BufferedImage captureScreenshot() {
        ScreenSnapshot snap = ultimoSnap;
        if (snap == null) return null;

        Font fuente = panel.fuente;
        // FontMetrics requiere un contexto gráfico; usamos el del panel si está inicializado,
        // o creamos uno temporal con un BufferedImage de 1x1
        FontMetrics fm = panel.isDisplayable()
                ? panel.getFontMetrics(fuente)
                : new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB)
                        .createGraphics()
                        .getFontMetrics(fuente);

        int cw = anchoCelda(fm);
        int ch = fm.getHeight();
        int as = fm.getAscent();
        int termW = cw * snap.cols() + MARGEN * 2;
        int termH = ch * snap.rows() + MARGEN * 2;

        // Altura de la barra de estado (misma fuente que la del label, tamaño 11)
        FontMetrics fmStatus = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB)
                .createGraphics()
                .getFontMetrics(resolverFuente(11));
        int statusH = fmStatus.getHeight() + 6; // 3px padding arriba + 3px abajo

        BufferedImage imagen = new BufferedImage(termW, termH + statusH,
                                                 BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = imagen.createGraphics();
        try {
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            // Área de terminal
            g2.setColor(COLOR_BG_PANTALLA);
            g2.fillRect(0, 0, termW, termH);
            renderizarTerminal(g2, snap, fuente, fm, cw, ch, as, MARGEN, MARGEN);

            // Barra de estado
            g2.setColor(COLOR_BG_STATUSBAR);
            g2.fillRect(0, termH, termW, statusH);

            String hora = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
            String textoStatus = "  " + estadoConexion
                    + "  |  cursor (" + snap.cursorRow() + ", " + snap.cursorCol() + ")"
                    + "  |  " + hora;
            g2.setFont(resolverFuente(11));
            g2.setColor(COLOR_FG_STATUS);
            g2.drawString(textoStatus, 4, termH + fmStatus.getAscent() + 3);

        } finally {
            g2.dispose();
        }
        return imagen;
    }

    /**
     * Guarda la pantalla actual como imagen PNG en el archivo indicado.
     *
     * <p>Si el directorio padre no existe, se crea automáticamente.</p>
     *
     * @param archivo archivo de destino (debe tener extensión .png)
     * @return el mismo archivo, para encadenamiento o logging
     * @throws IOException           si no se puede escribir el archivo
     * @throws IllegalStateException si no hay datos de pantalla todavía
     */
    public File saveScreenshot(File archivo) throws IOException {
        BufferedImage img = captureScreenshot();
        if (img == null) {
            throw new IllegalStateException(
                    "No hay datos de pantalla todavía — espera a recibir al menos un frame");
        }
        File padre = archivo.getParentFile();
        if (padre != null && !padre.exists()) {
            padre.mkdirs();
        }
        ImageIO.write(img, "PNG", archivo);
        return archivo;
    }

    /**
     * Guarda la pantalla actual como imagen PNG.
     *
     * <p>Si {@code rutaODirectorio} apunta a un directorio existente (o termina en
     * {@code /} o {@code \}), el nombre del archivo se genera automáticamente con el
     * formato {@code stratus-YYYYMMDD-HHmmss-SSS.png}. En caso contrario se usa
     * directamente como ruta del archivo.</p>
     *
     * @param rutaODirectorio ruta del archivo o directorio donde guardar
     * @return el archivo PNG creado
     * @throws IOException           si no se puede escribir el archivo
     * @throws IllegalStateException si no hay datos de pantalla todavía
     */
    public File saveScreenshot(String rutaODirectorio) throws IOException {
        File destino = new File(rutaODirectorio);
        if (destino.isDirectory() || rutaODirectorio.endsWith("/")
                                  || rutaODirectorio.endsWith("\\")) {
            String nombre = "stratus-"
                    + new SimpleDateFormat("yyyyMMdd-HHmmss-SSS").format(new Date())
                    + ".png";
            destino = new File(destino, nombre);
        }
        return saveScreenshot(destino);
    }

    // ── SessionListener ───────────────────────────────────────────────────────

    @Override
    public void onScreenUpdated(ScreenSnapshot snap) {
        ultimoSnap = snap;
        String hora   = new SimpleDateFormat("HH:mm:ss").format(new Date());
        String estado = estadoConexion;
        SwingUtilities.invokeLater(() -> {
            panel.repaint();
            barraEstado.setText(
                "  " + estado
                + "  |  cursor (" + snap.cursorRow() + ", " + snap.cursorCol() + ")"
                + "  |  " + hora
            );
        });
    }

    @Override
    public void onConnected() {
        estadoConexion = "Conectado";
        SwingUtilities.invokeLater(() -> frame.setTitle(titulo + " — Conectado"));
    }

    @Override
    public void onDisconnected() {
        estadoConexion = "Desconectado";
        SwingUtilities.invokeLater(() -> {
            frame.setTitle(titulo + " — Desconectado");
            actualizarBarra("Desconectado");
        });
    }

    @Override
    public void onError(Exception e) {
        String msg = "Error: " + e.getMessage();
        estadoConexion = msg;
        SwingUtilities.invokeLater(() -> actualizarBarra(msg));
    }

    // ── Renderizado compartido ────────────────────────────────────────────────

    /**
     * Núcleo de renderizado usado tanto por {@link PanelTerminal#paintComponent}
     * como por {@link #captureScreenshot()}.
     *
     * <p>Dibuja el cursor (bloque semitransparente) y el texto encima, con el
     * origen en (ox, oy) para respetar el margen.</p>
     */
    private static void renderizarTerminal(Graphics2D g2, ScreenSnapshot snap,
                                           Font fuente, FontMetrics fm,
                                           int cw, int ch, int as,
                                           int ox, int oy) {
        g2.setFont(fuente);

        // Cursor
        int curR = snap.cursorRow();
        int curC = snap.cursorCol();
        if (curR >= 1 && curC >= 1) {
            g2.setColor(COLOR_CURSOR);
            g2.fillRect(ox + (curC - 1) * cw, oy + (curR - 1) * ch, cw, ch);
        }

        // Texto
        g2.setColor(COLOR_FG_TEXTO);
        for (int r = 1; r <= snap.rows(); r++) {
            g2.drawString(snap.getLine(r), ox, oy + (r - 1) * ch + as);
        }
    }

    /** Ancho de celda calculado con una muestra de 20 caracteres para mayor precisión. */
    private static int anchoCelda(FontMetrics fm) {
        return fm.stringWidth("WWWWWWWWWWWWWWWWWWWW") / 20;
    }

    // ── Construcción de la ventana ────────────────────────────────────────────

    private JFrame crearVentana() {
        JFrame f = new JFrame(titulo);
        f.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        f.setBackground(COLOR_BG_PANTALLA);

        JPanel contenido = new JPanel(new BorderLayout(0, 0));
        contenido.setBackground(COLOR_BG_PANTALLA);
        contenido.add(panel,        BorderLayout.CENTER);
        contenido.add(barraEstado,  BorderLayout.SOUTH);

        f.setContentPane(contenido);
        return f;
    }

    private JLabel crearBarraEstado() {
        JLabel lbl = new JLabel("  Sin conexión");
        lbl.setFont(resolverFuente(11));
        lbl.setForeground(COLOR_FG_STATUS);
        lbl.setBackground(COLOR_BG_STATUSBAR);
        lbl.setOpaque(true);
        lbl.setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 4));
        return lbl;
    }

    private void actualizarBarra(String texto) {
        barraEstado.setText("  " + texto);
    }

    /**
     * Intenta fuentes monoespaciadas de buena calidad antes de usar la del sistema.
     * Consolas (Windows) y Menlo (Mac) tienen mejor legibilidad que Courier New.
     */
    private static Font resolverFuente(int size) {
        String[] candidatas = {"Consolas", "Menlo", "DejaVu Sans Mono",
                               "Lucida Console", "Courier New", Font.MONOSPACED};
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        java.util.Set<String> disponibles = new java.util.HashSet<String>(
                java.util.Arrays.asList(ge.getAvailableFontFamilyNames()));
        for (String nombre : candidatas) {
            if (disponibles.contains(nombre) || nombre.equals(Font.MONOSPACED)) {
                return new Font(nombre, Font.PLAIN, size);
            }
        }
        return new Font(Font.MONOSPACED, Font.PLAIN, size);
    }

    // ── Panel de renderizado ──────────────────────────────────────────────────

    /**
     * Componente que dibuja la pantalla VT100 carácter a carácter sobre fondo negro.
     * Delega en {@link ScreenViewer#renderizarTerminal} para compartir la lógica
     * con el método de captura off-screen.
     */
    private class PanelTerminal extends JPanel {

        final Font fuente = resolverFuente(FONT_SIZE);

        PanelTerminal() {
            setBackground(COLOR_BG_PANTALLA);
            setFont(fuente);
        }

        @Override
        public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(fuente);
            ScreenSnapshot snap = ultimoSnap;
            int cols = snap != null ? snap.cols() : 80;
            int rows = snap != null ? snap.rows() : 24;
            return new Dimension(anchoCelda(fm) * cols + MARGEN * 2,
                                 fm.getHeight() * rows + MARGEN * 2);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);

            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            ScreenSnapshot snap = ultimoSnap;
            if (snap == null) {
                g2.setFont(fuente);
                g2.setColor(COLOR_FG_TEXTO);
                g2.drawString("Esperando conexión...", MARGEN + 10,
                              MARGEN + g2.getFontMetrics().getAscent() + 10);
                return;
            }

            FontMetrics fm = g2.getFontMetrics(fuente);
            renderizarTerminal(g2, snap, fuente, fm,
                               anchoCelda(fm), fm.getHeight(), fm.getAscent(),
                               MARGEN, MARGEN);
        }
    }
}
