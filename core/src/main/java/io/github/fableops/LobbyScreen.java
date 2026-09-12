package io.github.fableops;

import java.awt.Font;
import java.net.InetAddress;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.IntArray;
import com.badlogic.gdx.utils.viewport.FitViewport;
import com.badlogic.gdx.utils.viewport.Viewport;

import io.github.fableops.network.SessionLauncher;
import io.github.fableops.ui.TextTexture;

/**
 * LibGDX port of the JavaFX pre-game launcher's neon FableOps menu (see
 * launcher/.../launcher.fxml, launcher.css, LauncherController.java — those remain the
 * visual source of truth). This is the canonical in-game menu: lwjgl3:run reaches it
 * directly (Main.create() with launchMode == null), and Level1Screen.returnToMainMenu()
 * reaches it after any mission-failure ESC.
 *
 * All text is rasterized once via {@link TextTexture} (Java2D/AWT system fonts) rather
 * than scaling LibGDX's own low-resolution default BitmapFont — that scaling was the
 * root cause of a previous, visibly blurry title and oversized/embossed-looking labels.
 * Every texture is generated in flat white and tinted at draw time via
 * SpriteBatch.setColor(...), so hover-color changes never require regenerating a texture.
 *
 * Layout is authored directly in a 1536x960 virtual design space, matching the reference
 * screenshot's own pixel measurements 1:1, so the panel/button/type proportions given in
 * that reference translate into this code without any unit conversion.
 */
public class LobbyScreen implements Screen {

    // ---- palette (launcher.css -fo-* custom properties) ----
    private static final Color BG             = Color.valueOf("050506");
    private static final Color LINE           = Color.valueOf("242220");
    private static final Color LINE_STRONG    = Color.valueOf("4A3D2E");
    private static final Color ORANGE         = Color.valueOf("FF8A3D");
    private static final Color ORANGE_DIM     = Color.valueOf("6B3A16");
    private static final Color CYAN           = Color.valueOf("00E5FF");
    private static final Color CYAN_DIM       = Color.valueOf("0A5C66");
    private static final Color MAGENTA        = Color.valueOf("FF2A6D");
    private static final Color MAGENTA_DIM    = Color.valueOf("661229");
    private static final Color TEXT_PRIMARY   = Color.valueOf("EDEDE8");
    private static final Color TEXT_SECONDARY = Color.valueOf("8C8C86");
    private static final Color TEXT_DIM       = Color.valueOf("4A4A46");
    private static final Color PANEL_BG       = new Color(0.039f, 0.043f, 0.047f, 0.94f);

    // Virtual design space == the reference screenshot's own pixel grid, so every
    // absolute number from that reference is usable here without conversion.
    // FitViewport maps this fixed space onto whatever the real window size/DPI is.
    private static final float VIRTUAL_W = 1536f;
    private static final float VIRTUAL_H = 960f;

    private static final String TITLE_FAMILY = "Arial Narrow";
    private static final String LABEL_FAMILY = "Segoe UI";
    private static final String MONO_FAMILY  = "Consolas";

    private static final float TITLE_H = 48f;  // 5% of viewport height, per reference
    private static final float MICRO_H = 14f;  // ~10px-equivalent chrome/eyebrow/status/labels
    private static final float KEY_H   = 16f;  // ~12px-equivalent shortcut key letters
    private static final float LABEL_H = 17f;  // ~13px-equivalent operation row labels

    private static final int STAR_COUNT = 45;
    private static final float LINK_DIST = 100f; // short local segments only, not long chains
    private static final float STAR_SPEED = 8f;  // virtual units/second
    private static final int MAX_LINKS = 60;     // hard cap on drawn connections per recompute

    private final Main game;

    private OrthographicCamera camera;
    private Viewport viewport;
    private SpriteBatch batch;
    private ShapeRenderer shape;

    private final Vector3 mouseVirtual = new Vector3();
    private final List<TextTexture> staticTextures = new ArrayList<>();

    // ---- static text textures (generated once in the constructor) ----
    private TextTexture titleFableTex, titleOpsTex;
    private TextTexture eyebrowTex;
    private TextTexture chromeLeftDimTex, chromeLeftAccentTex, chromeLinkTex;
    private TextTexture panelHeaderTex;
    private TextTexture keyHTex, keyJTex, keyLTex, keyDTex;
    private TextTexture labelHostTex, labelJoinLanTex, labelJoinLocalTex, labelDebugTex;
    private TextTexture ipLabelTex, ipPlaceholderTex, buildTex;

    // ---- dynamic text textures — regenerated only when their underlying string changes ----
    private TextTexture clockTex;
    private String lastClockString = "";
    private TextTexture statusTex;
    private String lastStatusString = "";
    private TextTexture ipTypedTex; // null while ipInput is empty (placeholder texture used instead)
    private String lastIpString = "";

    // ---- background points ----
    private static final class Point {
        float x, y;
        final float vx, vy;
        final boolean accent;

        Point(float x, float y, float vx, float vy, boolean accent) {
            this.x = x; this.y = y; this.vx = vx; this.vy = vy; this.accent = accent;
        }
    }

    private final Point[] points = new Point[STAR_COUNT];
    private final IntArray linkA = new IntArray(MAX_LINKS);
    private final IntArray linkB = new IntArray(MAX_LINKS);
    private int frameCounter = 0;
    private Rectangle quietZone; // central content area — background stays sparse behind it

    // ---- layout (computed once in computeLayout()) ----
    private Rectangle panelRect;
    private Rectangle hostRect, joinLanRect, joinLocalRect, debugRect, ipFieldRect;
    private float eyebrowX, eyebrowY;
    private float titleX, titleBaselineY;
    private float underlineX0, underlineY0, underlineX1, underlineY1;
    private float panelHeaderY;
    private float statusY;
    private float chromeBottomY;

    // ---- interaction state ----
    private String ipInput = "";
    private boolean ipFocused = false;
    private String statusMessage = "AWAITING CONNECTION...";
    private boolean connecting = false; // guards duplicate launches while host/join is in flight
    private boolean hostHover, joinLanHover, joinLocalHover, debugHover, ipHover;

    private final DateTimeFormatter clockFormat = DateTimeFormatter.ofPattern("HH:mm:ss");

    /**
     * Every binding in the game, laid out in two columns to the right of the operations
     * panel. A null key marks a section heading rather than a row.
     *
     * Kept as data rather than a wall of draw calls so a new binding is one line here and
     * the layout re-flows itself — the alternative drifts out of date the first time a key
     * changes, and a controls list that lies is worse than none.
     */
    private static final String[][] CONTROLS_LEFT = {
        {null, "MOVE"},
        {"W A S D", "Player 1"},
        {"ARROWS", "Player 2"},
        {null, "FIGHT"},
        {"F", "Player 1 attack"},
        {"R-SHIFT", "Player 2 attack"},
        {null, "WORLD"},
        {"E", "Use terminal"},
        {"1", "Player 1 inventory"},
        {"2", "Player 2 inventory"},
        {null, "SYSTEM"},
        {"F1", "Collision overlay"},
        {"F2", "UI size / projector"},
        {"K", "Skip stage (debug)"},
        {"ESC", "Back / quit"},
    };

    private static final String[][] CONTROLS_RIGHT = {
        {null, "TERMINAL"},
        {"TAB", "Next position"},
        {"0 - 9", "Enter digit"},
        {"BKSP", "Delete digit"},
        {"ENTER", "Submit"},
        {"SPACE", "Switch terminal"},
        {"ESC", "Close"},
        {null, "INVENTORY"},
        {"WASD / ARR", "Move cursor"},
        {"TAB", "Shared slot"},
        {"R", "Put / take"},
        {"ENTER", "Use item"},
        {"ESC", "Close"},
    };

    private static final float CONTROLS_X = 936f;
    private static final float CONTROLS_W = 580f;
    private static final float CONTROLS_PAD = 18f;
    private static final float CONTROLS_ROW_H = 21f;
    private static final float CONTROLS_HEADER_H = 26f;
    private static final float CONTROLS_KEY_W = 104f;
    private static final float CONTROLS_FONT_SCALE = 1.1f;

    private Rectangle controlsRect;
    private boolean disposed = false; // guards dispose() against running twice

    /**
     * Drawn with the bitmap pixel font rather than {@link TextTexture}, unlike the rest of
     * this screen. Roughly sixty short strings live in this panel; one texture per unique
     * string would mean sixty GL textures and sixty AWT metric probes at startup, for text
     * that never changes. One font page covers all of it, and its hard pixel edges match
     * the in-game panels the player sees next.
     */
    private BitmapFont controlsFont;

    public LobbyScreen(Main game) {
        this.game = game;

        camera = new OrthographicCamera();
        viewport = new FitViewport(VIRTUAL_W, VIRTUAL_H, camera);
        viewport.apply();
        camera.position.set(VIRTUAL_W / 2f, VIRTUAL_H / 2f, 0);
        camera.update();

        batch = new SpriteBatch();
        shape = new ShapeRenderer();

        controlsFont = new BitmapFont(Gdx.files.internal("pixel.fnt"), false);
        controlsFont.getRegion().getTexture()
            .setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        controlsFont.setUseIntegerPositions(false);
        controlsFont.getData().setScale(CONTROLS_FONT_SCALE);

        generateStaticTextures();
        seedPoints();
        computeLayout();
        regenerateClockTexture();
        regenerateStatusTexture();
    }

    private TextTexture reg(TextTexture t) {
        staticTextures.add(t);
        return t;
    }

    private void generateStaticTextures() {
        int titleStyle = Font.BOLD | Font.ITALIC;
        titleFableTex = reg(TextTexture.renderAtHeight("FABLE", TITLE_FAMILY, titleStyle, TITLE_H));
        titleOpsTex = reg(TextTexture.renderAtHeight("OPS", TITLE_FAMILY, titleStyle, TITLE_H));

        eyebrowTex = reg(TextTexture.renderAtHeight("ACCESS TERMINAL 03", MONO_FAMILY, Font.PLAIN, MICRO_H));
        chromeLeftDimTex = reg(TextTexture.renderAtHeight("SEC.NODE //", MONO_FAMILY, Font.PLAIN, MICRO_H));
        chromeLeftAccentTex = reg(TextTexture.renderAtHeight("REACTOR.LOCKOUT", MONO_FAMILY, Font.PLAIN, MICRO_H));
        chromeLinkTex = reg(TextTexture.renderAtHeight("LINK IDLE", MONO_FAMILY, Font.PLAIN, MICRO_H));

        panelHeaderTex = reg(TextTexture.renderAtHeight("SELECT OPERATION", MONO_FAMILY, Font.PLAIN, MICRO_H));

        keyHTex = reg(TextTexture.renderAtHeight("H", MONO_FAMILY, Font.BOLD, KEY_H));
        keyJTex = reg(TextTexture.renderAtHeight("J", MONO_FAMILY, Font.BOLD, KEY_H));
        keyLTex = reg(TextTexture.renderAtHeight("L", MONO_FAMILY, Font.BOLD, KEY_H));
        keyDTex = reg(TextTexture.renderAtHeight("D", MONO_FAMILY, Font.BOLD, KEY_H));

        labelHostTex = reg(TextTexture.renderAtHeight("Host session", LABEL_FAMILY, Font.PLAIN, LABEL_H));
        labelJoinLanTex = reg(TextTexture.renderAtHeight("Join over LAN", LABEL_FAMILY, Font.PLAIN, LABEL_H));
        labelJoinLocalTex = reg(TextTexture.renderAtHeight("Join local (127.0.0.1)", LABEL_FAMILY, Font.PLAIN, LABEL_H));
        labelDebugTex = reg(TextTexture.renderAtHeight("Debug — no network", LABEL_FAMILY, Font.PLAIN, LABEL_H));

        ipLabelTex = reg(TextTexture.renderAtHeight("HOST IP", MONO_FAMILY, Font.PLAIN, MICRO_H));
        ipPlaceholderTex = reg(TextTexture.renderAtHeight("192.168.1.***", MONO_FAMILY, Font.PLAIN, MICRO_H));
        buildTex = reg(TextTexture.renderAtHeight("build 2026.08.01", MONO_FAMILY, Font.PLAIN, MICRO_H));
    }

    private void seedPoints() {
        Random random = new Random(20260803L); // fixed seed — deterministic layout/motion
        for (int i = 0; i < STAR_COUNT; i++) {
            float x = random.nextFloat() * VIRTUAL_W;
            float y = random.nextFloat() * VIRTUAL_H;
            float angle = random.nextFloat() * (float) Math.PI * 2f;
            boolean accent = random.nextBoolean() && random.nextBoolean(); // ~25%, matches the JavaFX ratio
            points[i] = new Point(x, y, (float) Math.cos(angle) * STAR_SPEED, (float) Math.sin(angle) * STAR_SPEED, accent);
        }
    }

    /**
     * All layout rectangles/anchors, computed once against the fixed virtual space —
     * never recomputed on resize(), since FitViewport handles real-window scaling.
     * Anchors are taken directly from the reference screenshot's measured pixel
     * positions (given in top-down screen coordinates); converted to this class's
     * bottom-up virtual space via `VIRTUAL_H - screenY`.
     */
    private void computeLayout() {
        chromeBottomY = VIRTUAL_H - 28f; // reference: chrome bar spans screen y 0..27

        float panelX = 613f;
        float panelW = 298f;
        float padX = 20f;

        eyebrowX = panelX;
        eyebrowY = VIRTUAL_H - 296f;

        titleX = panelX;
        titleBaselineY = VIRTUAL_H - 376f; // reference: title spans screen y 328..376

        underlineX0 = panelX;
        underlineY0 = VIRTUAL_H - 381f;
        float underlineW = 95f;
        double tilt = Math.toRadians(8);
        underlineX1 = underlineX0 + (float) (underlineW * Math.cos(tilt));
        underlineY1 = underlineY0 - (float) (underlineW * Math.sin(tilt));

        float panelTop = VIRTUAL_H - 388f; // reference: panel top at screen y 388

        float padY = 19f;
        float headerH = 16f, headerGap = 11f;
        float rowH = 42f, rowGap = 7f;
        float rowW = panelW - 2 * padX;
        float ipGap = 15f, ipRowH = 26f;

        float cursor = panelTop - padY - headerH - headerGap;
        // Vertically centers the (taller) text texture within the nominal headerH slot.
        panelHeaderY = (panelTop - padY - headerH) + (headerH - MICRO_H) / 2f;

        hostRect = new Rectangle(panelX + padX, cursor - rowH, rowW, rowH);
        cursor -= rowH + rowGap;
        joinLanRect = new Rectangle(panelX + padX, cursor - rowH, rowW, rowH);
        cursor -= rowH + rowGap;
        joinLocalRect = new Rectangle(panelX + padX, cursor - rowH, rowW, rowH);
        cursor -= rowH + rowGap;
        debugRect = new Rectangle(panelX + padX, cursor - rowH, rowW, rowH);
        cursor -= rowH;

        cursor -= ipGap;
        ipFieldRect = new Rectangle(panelX + padX + 72f, cursor - ipRowH, rowW - 72f, ipRowH);
        cursor -= ipRowH;

        float panelBottom = cursor - padY;
        panelRect = new Rectangle(panelX, panelBottom, panelW, panelTop - panelBottom);

        statusY = panelBottom - 24f;

        // Controls panel: top-aligned with the operations panel, height driven by whichever
        // column has more entries so adding a binding never needs a hand-tuned number.
        int rows = Math.max(CONTROLS_LEFT.length, CONTROLS_RIGHT.length);
        int headers = Math.max(countHeaders(CONTROLS_LEFT), countHeaders(CONTROLS_RIGHT));
        float controlsH = 2 * CONTROLS_PAD + CONTROLS_HEADER_H
            + (rows - headers) * CONTROLS_ROW_H + headers * CONTROLS_HEADER_H;
        controlsRect = new Rectangle(CONTROLS_X, panelTop - controlsH, CONTROLS_W, controlsH);

        // Background points/links stay sparse directly behind the whole central column
        // (eyebrow through panel bottom), with a small margin — not just the panel itself.
        quietZone = new Rectangle(panelX - 30f, panelBottom - 20f, panelW + 60f, (panelTop - panelBottom) + 140f);
    }

    // ==================== render loop ====================

    @Override
    public void render(float delta) {
        updatePoints(delta);
        updateDynamicTextures();
        if (!connecting) {
            handleInput();
        }
        drawAllShapes();
        drawAllText();
    }

    private void updatePoints(float delta) {
        for (Point p : points) {
            p.x += p.vx * delta;
            p.y += p.vy * delta;
            if (p.x < 0) p.x = VIRTUAL_W; else if (p.x > VIRTUAL_W) p.x = 0;
            if (p.y < 0) p.y = VIRTUAL_H; else if (p.y > VIRTUAL_H) p.y = 0;
        }
        // The O(n^2) link-distance pass is the expensive part, not the draw call — only
        // recompute which pairs are linked every 3rd frame. The cached pairs are still
        // drawn every frame using this frame's positions, so nothing flickers the way
        // skipping the draw itself would on a clear-every-frame renderer.
        if (frameCounter % 3 == 0) recomputeLinks();
        frameCounter++;
    }

    private void recomputeLinks() {
        linkA.clear();
        linkB.clear();
        float linkDistSq = LINK_DIST * LINK_DIST;
        outer:
        for (int i = 0; i < points.length; i++) {
            for (int j = i + 1; j < points.length; j++) {
                if (linkA.size >= MAX_LINKS) break outer;
                float dx = points[i].x - points[j].x;
                float dy = points[i].y - points[j].y;
                if (dx * dx + dy * dy < linkDistSq) {
                    linkA.add(i);
                    linkB.add(j);
                }
            }
        }
    }

    private void updateDynamicTextures() {
        String clockNow = LocalTime.now().format(clockFormat);
        if (!clockNow.equals(lastClockString)) {
            lastClockString = clockNow;
            regenerateClockTexture();
        }
        if (!statusMessage.equals(lastStatusString)) {
            lastStatusString = statusMessage;
            regenerateStatusTexture();
        }
        if (!ipInput.equals(lastIpString)) {
            lastIpString = ipInput;
            if (ipTypedTex != null) ipTypedTex.dispose();
            ipTypedTex = ipInput.isEmpty() ? null : TextTexture.renderAtHeight(ipInput, MONO_FAMILY, Font.PLAIN, MICRO_H);
        }
    }

    private void regenerateClockTexture() {
        if (clockTex != null) clockTex.dispose();
        clockTex = TextTexture.renderAtHeight(lastClockString.isEmpty() ? "00:00:00" : lastClockString, MONO_FAMILY, Font.PLAIN, MICRO_H);
    }

    private void regenerateStatusTexture() {
        if (statusTex != null) statusTex.dispose();
        statusTex = TextTexture.renderAtHeight(statusMessage, MONO_FAMILY, Font.PLAIN, MICRO_H);
    }

    // ==================== input ====================

    private void handleInput() {
        updateHover();
        handleMouseClick();
        if (ipFocused) {
            handleIpEditingKeys();
        } else {
            handleMenuKeys();
        }
    }

    /** Converts the current mouse position into virtual coordinates exactly once per frame. */
    private void updateMouseVirtual() {
        mouseVirtual.set(Gdx.input.getX(), Gdx.input.getY(), 0);
        viewport.unproject(mouseVirtual);
    }

    private void updateHover() {
        updateMouseVirtual();
        float mx = mouseVirtual.x, my = mouseVirtual.y;
        hostHover = hostRect.contains(mx, my);
        joinLanHover = joinLanRect.contains(mx, my);
        joinLocalHover = joinLocalRect.contains(mx, my);
        debugHover = debugRect.contains(mx, my);
        ipHover = ipFieldRect.contains(mx, my);
    }

    private void handleMouseClick() {
        if (!Gdx.input.justTouched()) return;
        // Mouse clicks work regardless of ipFocused, same as a real scene graph routing
        // a click to whatever was actually clicked, independent of keyboard focus.
        if (hostHover) {
            triggerHost();
        } else if (joinLanHover) {
            onJoinLanAction();
        } else if (joinLocalHover) {
            triggerJoin("127.0.0.1");
        } else if (debugHover) {
            triggerDebug();
        } else if (ipHover) {
            ipFocused = true;
        }
    }

    private void handleMenuKeys() {
        if (Gdx.input.isKeyJustPressed(Input.Keys.H)) {
            triggerHost();
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.J)) {
            ipFocused = true;
            ipInput = "";
            statusMessage = "TYPE HOST IP, THEN PRESS ENTER";
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.L)) {
            triggerJoin("127.0.0.1");
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.D)) {
            triggerDebug();
        }
    }

    private void handleIpEditingKeys() {
        for (int k = Input.Keys.NUM_0; k <= Input.Keys.NUM_9; k++) {
            if (Gdx.input.isKeyJustPressed(k)) {
                ipInput += (char) ('0' + (k - Input.Keys.NUM_0));
            }
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.PERIOD)) ipInput += ".";
        if (Gdx.input.isKeyJustPressed(Input.Keys.BACKSPACE) && !ipInput.isEmpty()) {
            ipInput = ipInput.substring(0, ipInput.length() - 1);
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.ENTER) && !ipInput.isBlank()) {
            triggerJoin(ipInput);
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            ipFocused = false;
            ipInput = "";
            statusMessage = "AWAITING CONNECTION...";
        }
    }

    /** Mirrors LauncherController.onJoinLan(): try to join if an IP is already typed, else focus. */
    private void onJoinLanAction() {
        if (ipInput.isBlank()) {
            ipFocused = true;
            statusMessage = "ENTER A HOST IP ABOVE, THEN PRESS J OR ENTER";
        } else {
            triggerJoin(ipInput.trim());
        }
    }

    // ==================== session triggers ====================

    private void triggerHost() {
        if (connecting) return;
        connecting = true;
        statusMessage = "HOSTING... YOUR IP: " + getLocalIP();
        SessionLauncher.host(game, message -> {
            statusMessage = message;
            connecting = false;
        });
    }

    private void triggerJoin(String ip) {
        if (connecting) return;
        connecting = true;
        ipFocused = false;
        statusMessage = "CONNECTING TO " + ip + "...";
        SessionLauncher.join(game, ip, () -> {
            statusMessage = "CONNECTION FAILED. CHECK IP AND TRY AGAIN.";
            connecting = false;
        });
    }

    private void triggerDebug() {
        if (connecting) return;
        connecting = true;
        SessionLauncher.debug(game);
    }

    private String getLocalIP() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "unknown";
        }
    }

    // ==================== drawing — shapes pass ====================

    private void drawAllShapes() {
        Gdx.gl.glClearColor(BG.r, BG.g, BG.b, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        // Ensure alpha blending is actually active for every ShapeRenderer draw this
        // frame — without this, low-alpha colors (button fills, background lines) are
        // written at full opacity instead of blending, which is what previously made
        // near-transparent white button fills render as solid white.
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        shape.setProjectionMatrix(camera.combined);

        shape.begin(ShapeRenderer.ShapeType.Line);
        drawBackgroundLinks();
        shape.end();

        shape.begin(ShapeRenderer.ShapeType.Filled);
        drawBackgroundDots();
        drawChromeBarShape();
        drawEyebrowMarker();
        drawTitleUnderline();
        drawPanelShapes();
        drawControlsShapes();
        drawOpRowShapes(hostRect, ORANGE, ORANGE_DIM, hostHover);
        drawOpRowShapes(joinLanRect, CYAN, CYAN_DIM, joinLanHover);
        drawOpRowShapes(joinLocalRect, CYAN, CYAN_DIM, joinLocalHover);
        drawOpRowShapes(debugRect, MAGENTA, MAGENTA_DIM, debugHover);
        drawIpRowShapes();
        shape.end();
    }

    private void drawBackgroundLinks() {
        for (int k = 0; k < linkA.size; k++) {
            Point a = points[linkA.get(k)];
            Point b = points[linkB.get(k)];
            if (segmentNearQuietZone(a, b)) continue;
            float dx = a.x - b.x, dy = a.y - b.y;
            float dist = (float) Math.sqrt(dx * dx + dy * dy);
            float alpha = Math.max(0f, (1f - dist / LINK_DIST) * 0.28f);
            shape.setColor(CYAN.r, CYAN.g, CYAN.b, alpha);
            shape.line(a.x, a.y, b.x, b.y);
        }
    }

    private boolean segmentNearQuietZone(Point a, Point b) {
        float midX = (a.x + b.x) / 2f, midY = (a.y + b.y) / 2f;
        return quietZone.contains(midX, midY) || quietZone.contains(a.x, a.y) || quietZone.contains(b.x, b.y);
    }

    private void drawBackgroundDots() {
        for (Point p : points) {
            if (quietZone.contains(p.x, p.y)) continue;
            float alpha = p.accent ? 0.55f : 0.25f;
            Color c = p.accent ? ORANGE : TEXT_PRIMARY;
            shape.setColor(c.r, c.g, c.b, alpha);
            shape.rect(p.x, p.y, 2f, 2f);
        }
    }

    private void drawChromeBarShape() {
        shape.setColor(BG.r, BG.g, BG.b, 0.7f);
        shape.rect(0, chromeBottomY, VIRTUAL_W, VIRTUAL_H - chromeBottomY);
        shape.setColor(LINE);
        shape.rect(0, chromeBottomY - 2f, VIRTUAL_W, 2f);
    }

    private void drawEyebrowMarker() {
        shape.setColor(MAGENTA);
        shape.rect(eyebrowX, eyebrowY, 5f, 5f);
    }

    private void drawTitleUnderline() {
        shape.setColor(ORANGE);
        shape.rectLine(underlineX0, underlineY0, underlineX1, underlineY1, 4.5f);
    }

    private void drawPanelShapes() {
        shape.setColor(PANEL_BG);
        shape.rect(panelRect.x, panelRect.y, panelRect.width, panelRect.height);

        shape.setColor(LINE_STRONG);
        shape.rect(panelRect.x, panelRect.y, panelRect.width, 2f);
        shape.rect(panelRect.x, panelRect.y + panelRect.height - 2f, panelRect.width, 2f);
        shape.rect(panelRect.x, panelRect.y, 2f, panelRect.height);
        shape.rect(panelRect.x + panelRect.width - 2f, panelRect.y, 2f, panelRect.height);

        // Header marks: two filled + one hollow square, right-aligned in the panel.
        float mx = panelRect.x + panelRect.width - 20f - 24f;
        float my = panelHeaderY - 4f;
        shape.setColor(ORANGE_DIM);
        shape.rect(mx, my, 5f, 5f);
        shape.rect(mx + 9f, my, 5f, 5f);
        shape.rect(mx + 18f, my, 5f, 1f);
        shape.rect(mx + 18f, my + 4f, 5f, 1f);
        shape.rect(mx + 18f, my, 1f, 5f);
        shape.rect(mx + 22f, my, 1f, 5f);
    }

    private static int countHeaders(String[][] rows) {
        int n = 0;
        for (String[] row : rows) {
            if (row[0] == null) n++;
        }
        return n;
    }

    /** Same chrome as the operations panel, so the two read as one interface. */
    private void drawControlsShapes() {
        shape.setColor(PANEL_BG);
        shape.rect(controlsRect.x, controlsRect.y, controlsRect.width, controlsRect.height);

        shape.setColor(LINE_STRONG);
        shape.rect(controlsRect.x, controlsRect.y, controlsRect.width, 2f);
        shape.rect(controlsRect.x, controlsRect.y + controlsRect.height - 2f, controlsRect.width, 2f);
        shape.rect(controlsRect.x, controlsRect.y, 2f, controlsRect.height);
        shape.rect(controlsRect.x + controlsRect.width - 2f, controlsRect.y, 2f, controlsRect.height);

        // Divider between the two columns, so the eye doesn't read across the gap.
        shape.setColor(LINE);
        shape.rect(controlsRect.x + controlsRect.width / 2f - 1f,
            controlsRect.y + CONTROLS_PAD,
            2f, controlsRect.height - 2 * CONTROLS_PAD - CONTROLS_HEADER_H);
    }

    private void drawOpRowShapes(Rectangle r, Color accent, Color accentDim, boolean hover) {
        // base fill — explicit color every time, never inherited from a previous draw.
        float baseAlpha = hover ? 0.06f : 0.02f;
        shape.setColor(1f, 1f, 1f, baseAlpha);
        shape.rect(r.x, r.y, r.width, r.height);

        Color border = hover ? accentDim : LINE_STRONG;
        shape.setColor(border);
        shape.rect(r.x, r.y, r.width, 2f);
        shape.rect(r.x, r.y + r.height - 2f, r.width, 2f);
        shape.rect(r.x + r.width - 2f, r.y, 2f, r.height);

        shape.setColor(hover ? accent : accentDim);
        shape.rect(r.x, r.y, 4f, r.height);

        float keyBoxSize = 21f;
        float keyBoxX = r.x + 12f;
        float keyBoxY = r.y + (r.height - keyBoxSize) / 2f;
        shape.setColor(hover ? accent : accentDim);
        shape.rect(keyBoxX, keyBoxY, keyBoxSize, 2f);
        shape.rect(keyBoxX, keyBoxY + keyBoxSize - 2f, keyBoxSize, 2f);
        shape.rect(keyBoxX, keyBoxY, 2f, keyBoxSize);
        shape.rect(keyBoxX + keyBoxSize - 2f, keyBoxY, 2f, keyBoxSize);

        // small dim right-pointing triangle
        float ax = r.x + r.width - 20f, ay = r.y + r.height / 2f;
        shape.setColor(TEXT_DIM);
        shape.triangle(ax, ay + 4f, ax, ay - 4f, ax + 6f, ay);
    }

    private void drawIpRowShapes() {
        shape.setColor(LINE);
        shape.rect(panelRect.x + 20f, ipFieldRect.y + ipFieldRect.height + 13f, panelRect.width - 40f, 2f);

        shape.setColor(BG);
        shape.rect(ipFieldRect.x, ipFieldRect.y, ipFieldRect.width, ipFieldRect.height);
        Color fieldBorder = ipFocused || ipHover ? ORANGE_DIM : LINE_STRONG;
        shape.setColor(fieldBorder);
        shape.rect(ipFieldRect.x, ipFieldRect.y, ipFieldRect.width, 2f);
        shape.rect(ipFieldRect.x, ipFieldRect.y + ipFieldRect.height - 2f, ipFieldRect.width, 2f);
        shape.rect(ipFieldRect.x, ipFieldRect.y, 2f, ipFieldRect.height);
        shape.rect(ipFieldRect.x + ipFieldRect.width - 2f, ipFieldRect.y, 2f, ipFieldRect.height);
    }

    // ==================== drawing — text pass ====================

    private void drawAllText() {
        batch.setProjectionMatrix(camera.combined);
        batch.begin();

        drawTex(eyebrowTex, eyebrowX + 11f, eyebrowY - 1f, MAGENTA);
        drawTex(chromeLeftDimTex, 20f, chromeTextY(), TEXT_DIM);
        drawTex(chromeLeftAccentTex, 20f + chromeLeftDimTex.width + 8f, chromeTextY(), ORANGE);
        float rightEdge = VIRTUAL_W - 20f;
        drawTex(chromeLinkTex, rightEdge - chromeLinkTex.width, chromeTextY(), TEXT_DIM);
        drawTex(clockTex, rightEdge - chromeLinkTex.width - 20f - clockTex.width, chromeTextY(), TEXT_DIM);

        drawTitle();

        drawTex(panelHeaderTex, panelRect.x + 20f, panelHeaderY, TEXT_SECONDARY);

        drawOpRowText(hostRect, keyHTex, labelHostTex, ORANGE, ORANGE_DIM, hostHover);
        drawOpRowText(joinLanRect, keyJTex, labelJoinLanTex, CYAN, CYAN_DIM, joinLanHover);
        drawOpRowText(joinLocalRect, keyLTex, labelJoinLocalTex, CYAN, CYAN_DIM, joinLocalHover);
        drawOpRowText(debugRect, keyDTex, labelDebugTex, MAGENTA, MAGENTA_DIM, debugHover);

        drawIpRowText();
        drawControlsText();

        drawTex(statusTex, panelRect.x, statusY, TEXT_DIM);
        drawTex(buildTex, panelRect.x + panelRect.width - buildTex.width, statusY, TEXT_DIM);

        batch.end();
    }

    private void drawControlsText() {
        float top = controlsRect.y + controlsRect.height - CONTROLS_PAD;

        controlsFont.setColor(TEXT_SECONDARY);
        controlsFont.draw(batch, "CONTROLS", controlsRect.x + CONTROLS_PAD, top);

        float columnW = (controlsRect.width - 2 * CONTROLS_PAD) / 2f;
        float bodyTop = top - CONTROLS_HEADER_H;
        drawControlsColumn(CONTROLS_LEFT, controlsRect.x + CONTROLS_PAD, bodyTop, columnW);
        drawControlsColumn(CONTROLS_RIGHT, controlsRect.x + CONTROLS_PAD + columnW + 12f,
            bodyTop, columnW - 12f);
    }

    /**
     * Section headings take the accent colour and a taller slot; rows are key then label.
     * The key column is fixed width so every label starts on the same vertical line, which
     * is what makes a list this long scannable rather than a wall of text.
     */
    private void drawControlsColumn(String[][] rows, float x, float top, float width) {
        float y = top;
        for (String[] row : rows) {
            if (row[0] == null) {
                controlsFont.setColor(ORANGE);
                controlsFont.draw(batch, row[1], x, y);
                y -= CONTROLS_HEADER_H;
            } else {
                controlsFont.setColor(CYAN);
                controlsFont.draw(batch, row[0], x, y);
                controlsFont.setColor(TEXT_SECONDARY);
                controlsFont.draw(batch, row[1], x + CONTROLS_KEY_W, y,
                    width - CONTROLS_KEY_W, Align.left, false);
                y -= CONTROLS_ROW_H;
            }
        }
    }

    private float chromeTextY() {
        return chromeBottomY + (VIRTUAL_H - chromeBottomY - MICRO_H) / 2f;
    }

    private void drawTex(TextTexture tex, float x, float y, Color color) {
        batch.setColor(color);
        batch.draw(tex.texture, x, y, tex.width, tex.height);
        batch.setColor(Color.WHITE);
    }

    private void drawTitle() {
        // FABLE stays perfectly crisp — one 1:1 draw, no glow. OPS gets a small,
        // restrained glow as a separate slightly-larger, low-alpha copy drawn first
        // (underneath), then the crisp 1:1 copy on top — the glow never touches the
        // sharp layer's own pixels, so it can't reduce its sharpness.
        drawTex(titleFableTex, titleX, titleBaselineY, TEXT_PRIMARY);

        float opsX = titleX + titleFableTex.width + 2f;
        float glowPad = 3f;
        batch.setColor(ORANGE.r, ORANGE.g, ORANGE.b, 0.20f);
        batch.draw(titleOpsTex.texture, opsX - glowPad, titleBaselineY - glowPad,
            titleOpsTex.width + glowPad * 2f, titleOpsTex.height + glowPad * 2f);
        batch.setColor(Color.WHITE);
        drawTex(titleOpsTex, opsX, titleBaselineY, ORANGE);
    }

    private void drawOpRowText(Rectangle r, TextTexture keyTex, TextTexture labelTex, Color accent, Color accentDim, boolean hover) {
        float keyBoxSize = 21f;
        float keyBoxX = r.x + 12f;
        float keyBoxY = r.y + (r.height - keyBoxSize) / 2f;
        drawTex(keyTex, keyBoxX + (keyBoxSize - keyTex.width) / 2f, keyBoxY + (keyBoxSize - keyTex.height) / 2f,
            hover ? accent : TEXT_SECONDARY);

        drawTex(labelTex, keyBoxX + keyBoxSize + 12f, r.y + (r.height - labelTex.height) / 2f, TEXT_PRIMARY);
    }

    private void drawIpRowText() {
        drawTex(ipLabelTex, panelRect.x + 20f, ipFieldRect.y + (ipFieldRect.height - ipLabelTex.height) / 2f, TEXT_SECONDARY);

        if (ipInput.isEmpty()) {
            drawTex(ipPlaceholderTex, ipFieldRect.x + 8f, ipFieldRect.y + (ipFieldRect.height - ipPlaceholderTex.height) / 2f, TEXT_DIM);
        } else if (ipTypedTex != null) {
            drawTex(ipTypedTex, ipFieldRect.x + 8f, ipFieldRect.y + (ipFieldRect.height - ipTypedTex.height) / 2f, ORANGE);
        }
    }

    // ==================== Screen ====================

    @Override public void show() {}

    @Override
    public void resize(int width, int height) {
        viewport.update(width, height, true);
    }

    @Override public void pause() {}
    @Override public void resume() {}
    /**
     * Releases this screen's GPU resources when it is navigated away from.
     *
     * libGDX's Game.setScreen() only calls hide() on the outgoing screen — never dispose()
     * — so without this every lobby leaked its batch, shape renderer, text textures and
     * font each time a session started. Safe here because a lobby is never returned to:
     * both Main.create() and Level1Screen.returnToMainMenu() always construct a fresh one.
     */
    @Override
    public void hide() {
        dispose();
    }

    @Override
    public void dispose() {
        if (disposed) return; // hide() and an explicit dispose() can both reach here
        disposed = true;
        shape.dispose();
        batch.dispose();
        controlsFont.dispose();
        for (TextTexture t : staticTextures) t.dispose();
        if (clockTex != null) clockTex.dispose();
        if (statusTex != null) statusTex.dispose();
        if (ipTypedTex != null) ipTypedTex.dispose();
    }
}
