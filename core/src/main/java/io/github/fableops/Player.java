package io.github.fableops;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Animation;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;

import java.util.ArrayList;
import java.util.List;

import io.github.fableops.network.PlayerInput;

public class Player {

    /** Row order in the sprite sheet: row 0 = down, 1 = up, 2 = left, 3 = right. */
    private enum Direction { DOWN, UP, LEFT, RIGHT }

    private static final int SHEET_COLUMNS = 8;
    private static final int SHEET_ROWS = 4;
    private static final float FRAME_DURATION = 0.1f; // 8 frames * 0.1s = 0.8s per walk cycle
    private static final int ALPHA_THRESHOLD = 20; // ignore faint anti-aliasing dust in gutters

    // ---- procedural combat visual feedback (walk sprites only — no dedicated frames) ----
    private static final float ATTACK_VISUAL_DURATION = 0.18f; // seconds, within the 0.16-0.20 target
    private static final float HURT_FLASH_DURATION = 0.18f;    // seconds, within the 0.15-0.22 target
    private static final float HURT_TINT_STRENGTH = 0.65f;     // how far G/B channels drop at peak flash
    private static final float ATTACK_LUNGE_DISTANCE = 10f;    // draw-offset only, world x/y never move
    private static final float ATTACK_SCALE_AMOUNT = 0.06f;    // subtle +/-6% pulse at the peak of the lunge
    private static final float DEAD_ROTATION_DEGREES = 85f;
    private static final float DEAD_ALPHA = 0.65f;

    public float x, y;
    public static final float SIZE = 100f;
    private static final float SPEED = 220f;
    public static final float MAX_HEALTH = 100f;
    public float health = MAX_HEALTH;

    private Texture spriteSheet;
    private Animation<TextureRegion>[] walkAnimations; // indexed by Direction.ordinal()
    private float stateTime = 0f;
    private Direction facing = Direction.DOWN;
    private boolean isMoving = false;

    // Visual-only timers — never affect x/y, collision, or camera. Death has no timer of
    // its own; it's read directly off `health <= 0`, the same source of truth the rest of
    // the game already uses, so it can never drift out of sync or need its own removal logic.
    private float attackVisualTimer = 0f;
    private float hurtVisualTimer = 0f;

    public Color bodyColor;
    public Color accentColor;

    private int keyUp, keyDown, keyLeft, keyRight;
    // Optional second key for "move right". -1 means unset, and it must be guarded
    // explicitly rather than passed to isKeyPressed(), because Input.Keys.ANY_KEY is
    // also -1 and would report true whenever any key at all is held.
    private int keyRightAlt = -1;
    public OrthographicCamera camera;
    private Collidable world;

    private final float camW;
    private final float camH;
    private final int side; // 1 = P1's own floor/gate, 2 = P2's — which side this player collides against

    // full constructor — used for keyboard-controlled players (default zoom, side=1)
    public Player(float startX, float startY,
                  Color bodyColor, Color accentColor,
                  int keyUp, int keyDown, int keyLeft, int keyRight,
                  Collidable world) {
        this(startX, startY, bodyColor, accentColor,
             keyUp, keyDown, keyLeft, keyRight, world, 960f, 1080f, 1);
    }

    // full constructor — explicit camera zoom, default side=1
    public Player(float startX, float startY,
                  Color bodyColor, Color accentColor,
                  int keyUp, int keyDown, int keyLeft, int keyRight,
                  Collidable world, float camW, float camH) {
        this(startX, startY, bodyColor, accentColor,
             keyUp, keyDown, keyLeft, keyRight, world, camW, camH, 1);
    }

    // full constructor — explicit camera zoom AND side (use this for Level1Screen's P1/P2)
    public Player(float startX, float startY,
                  Color bodyColor, Color accentColor,
                  int keyUp, int keyDown, int keyLeft, int keyRight,
                  Collidable world, float camW, float camH, int side) {
        this.x         = startX;
        this.y         = startY;
        this.bodyColor  = bodyColor;
        this.accentColor = accentColor;
        this.keyUp     = keyUp;
        this.keyDown   = keyDown;
        this.keyLeft   = keyLeft;
        this.keyRight  = keyRight;
        this.world     = world;
        this.camW      = camW;
        this.camH      = camH;
        this.side      = side;

        camera = new OrthographicCamera(camW, camH);
        camera.position.set(x + SIZE / 2f, y + SIZE / 2f, 0);
        camera.update();
    }

    // network-only constructor — position set by received state, no keys needed (default zoom, side=1)
    public Player(float startX, float startY,
                  Color bodyColor, Color accentColor,
                  Collidable world) {
        this(startX, startY, bodyColor, accentColor,
             -1, -1, -1, -1, world, 960f, 1080f, 1);
    }

    public void takeDamage(float amount) {
        if (amount <= 0f) return; // zero/negative damage must not trigger the hurt flash
        float newHealth = Math.max(0f, health - amount);
        if (newHealth < health) triggerHurtVisual(); // only fires when health actually dropped
        health = newHealth;
    }

    /** Starts (or restarts) the short attack lunge/pulse. Call exactly once per accepted swing. */
    public void triggerAttackVisual() {
        attackVisualTimer = ATTACK_VISUAL_DURATION;
    }

    /** Starts (or refreshes) the hurt flash — continuous contact keeps extending it. */
    private void triggerHurtVisual() {
        hurtVisualTimer = HURT_FLASH_DURATION;
    }

    /**
     * Advances the visual-only timers. Independent of movement/input — must be called
     * once per frame regardless of whether this player is moving, has a popup open, or
     * the mission has failed, so an in-flight lunge/flash always finishes cleanly.
     */
    public void updateVisualState(float delta) {
        if (attackVisualTimer > 0f) attackVisualTimer = Math.max(0f, attackVisualTimer - delta);
        if (hurtVisualTimer > 0f) hurtVisualTimer = Math.max(0f, hurtVisualTimer - delta);
    }

    /** Clears every temporary visual-only state. Called from every Level 1 restart handler. */
    public void resetVisualState() {
        attackVisualTimer = 0f;
        hurtVisualTimer = 0f;
    }

    /** Adds a second key that also moves this player right, alongside the primary one. */
    public void setAlternateRightKey(int key) {
        this.keyRightAlt = key;
    }

    private boolean isMovingRight() {
        return Gdx.input.isKeyPressed(keyRight)
            || (keyRightAlt >= 0 && Gdx.input.isKeyPressed(keyRightAlt));
    }

    // reads local keyboard — used by host for P1, by client for P2
    public PlayerInput readInput() {
        return new PlayerInput(
            Gdx.input.isKeyPressed(keyUp),
            Gdx.input.isKeyPressed(keyDown),
            Gdx.input.isKeyPressed(keyLeft),
            Gdx.input.isKeyPressed(keyRight)
        );
    }

    /**
     * Loads a 4-direction walk sheet (8 columns x 4 rows: down, up, left, right) and
     * slices it into a looping Animation per direction. There's no separate idle pose in
     * these sheets, so idle just holds frame 0 of whichever direction was last faced.
     *
     * Frame boundaries are detected from the actual transparent gutters between poses
     * rather than assumed to be a uniform grid — these hand-placed sheets aren't
     * perfectly evenly spaced, and slicing on a uniform grid cut a few pixels off one
     * neighboring frame and into the next (stray pixels above/below/beside the sprite).
     */
    @SuppressWarnings("unchecked")
    public void setTexture(String spriteSheetFile) {
        if (spriteSheet != null) spriteSheet.dispose();

        Pixmap pixmap = new Pixmap(Gdx.files.internal(spriteSheetFile));
        int[] colBoundaries = detectBoundaries(pixmap, true, SHEET_COLUMNS);
        int[] rowBoundaries = detectBoundaries(pixmap, false, SHEET_ROWS);

        spriteSheet = new Texture(pixmap);
        pixmap.dispose();

        walkAnimations = new Animation[SHEET_ROWS];
        for (int row = 0; row < SHEET_ROWS; row++) {
            TextureRegion[] frames = new TextureRegion[SHEET_COLUMNS];
            int y0 = rowBoundaries[row], y1 = rowBoundaries[row + 1];
            for (int col = 0; col < SHEET_COLUMNS; col++) {
                int x0 = colBoundaries[col], x1 = colBoundaries[col + 1];
                frames[col] = new TextureRegion(spriteSheet, x0, y0, x1 - x0, y1 - y0);
            }
            walkAnimations[row] = new Animation<>(FRAME_DURATION, frames);
        }
    }

    /**
     * Finds expectedFrames boundaries along one axis by locating fully-transparent
     * gutters between frames and cutting at the midpoint of each gutter. Falls back to
     * a uniform grid if the sheet doesn't actually have expectedFrames content runs
     * (an unexpected layout — better to degrade to the old behavior than guess wrong).
     */
    private static int[] detectBoundaries(Pixmap pixmap, boolean horizontal, int expectedFrames) {
        int length = horizontal ? pixmap.getWidth() : pixmap.getHeight();
        int otherLength = horizontal ? pixmap.getHeight() : pixmap.getWidth();

        boolean[] hasContent = new boolean[length];
        for (int i = 0; i < length; i++) {
            for (int j = 0; j < otherLength; j++) {
                int px = horizontal ? i : j;
                int py = horizontal ? j : i;
                int alpha = pixmap.getPixel(px, py) & 0xFF;
                if (alpha > ALPHA_THRESHOLD) {
                    hasContent[i] = true;
                    break;
                }
            }
        }

        List<int[]> runs = new ArrayList<>(); // each is [start, endInclusive]
        int runStart = -1;
        for (int i = 0; i < length; i++) {
            if (hasContent[i] && runStart == -1) {
                runStart = i;
            } else if (!hasContent[i] && runStart != -1) {
                runs.add(new int[]{runStart, i - 1});
                runStart = -1;
            }
        }
        if (runStart != -1) runs.add(new int[]{runStart, length - 1});

        int[] boundaries = new int[expectedFrames + 1];
        if (runs.size() != expectedFrames) {
            for (int i = 0; i <= expectedFrames; i++) {
                boundaries[i] = Math.round(i * length / (float) expectedFrames);
            }
            return boundaries;
        }

        boundaries[0] = 0;
        boundaries[expectedFrames] = length;
        for (int i = 1; i < expectedFrames; i++) {
            int prevEnd = runs.get(i - 1)[1];
            int nextStart = runs.get(i)[0];
            boundaries[i] = (prevEnd + nextStart + 1) / 2;
        }
        return boundaries;
    }

    // applies a received PlayerInput — used by host to move P2
    public void applyInput(PlayerInput input, float delta) {
        float dx = 0, dy = 0;
        if (input.up)    dy += SPEED * delta;
        if (input.down)  dy -= SPEED * delta;
        if (input.left)  dx -= SPEED * delta;
        if (input.right) dx += SPEED * delta;

        move(dx, dy, delta);
    }

    // reads keyboard and moves — used by host for P1
    public void update(float delta) {
        float dx = 0, dy = 0;
        if (Gdx.input.isKeyPressed(keyUp))    dy += SPEED * delta;
        if (Gdx.input.isKeyPressed(keyDown))  dy -= SPEED * delta;
        if (Gdx.input.isKeyPressed(keyLeft))  dx -= SPEED * delta;
        if (isMovingRight())                  dx += SPEED * delta;

        move(dx, dy, delta);
    }

    private void move(float dx, float dy, float delta) {
        isMoving = (dx != 0 || dy != 0);

        // Diagonal input faces the side sprite (horizontal takes priority over vertical).
        if (dx < 0) facing = Direction.LEFT;
        else if (dx > 0) facing = Direction.RIGHT;
        else if (dy > 0) facing = Direction.UP;
        else if (dy < 0) facing = Direction.DOWN;

        stateTime = isMoving ? stateTime + delta : 0f;

        if (!world.collides(x + dx, y, SIZE, SIZE, side)) x += dx;
        if (!world.collides(x, y + dy, SIZE, SIZE, side)) y += dy;

        updateCamera();
    }

    // updates camera to follow this player — call after any position change
    public void updateCamera() {
        float halfW = camW / 2f;
        float halfH = camH / 2f;
        float camX = Math.max(halfW, Math.min(x + SIZE / 2f, world.getWorldWidth() - halfW));
        float camY = Math.max(halfH, Math.min(y + SIZE / 2f, world.getWorldHeight() - halfH));
        camera.position.set(camX, camY, 0);
        camera.update();
    }

    public void draw(ShapeRenderer shape) {
        // body
         shape.setColor(bodyColor);
        shape.rect(x, y, SIZE, SIZE);

        // visor strip
        shape.setColor(accentColor);
        shape.rect(x + 5f, y + SIZE - 13f, SIZE - 10f, 9f);

        // visor inner reflection
        shape.setColor(0.05f, 0.05f, 0.1f, 1f);
        shape.rect(x + 8f, y + SIZE - 11f, SIZE - 16f, 5f);

        // legs
        shape.setColor(bodyColor.r * 0.6f, bodyColor.g * 0.6f, bodyColor.b * 0.6f, 1f);
        shape.rect(x + 4f,         y, 10f, 9f);
        shape.rect(x + SIZE - 14f, y, 10f, 9f);

        // shoulder pads
        shape.setColor(accentColor.r * 0.7f, accentColor.g * 0.7f, accentColor.b * 0.7f, 1f);
        shape.rect(x,             y + SIZE - 18f, 7f, 7f);
        shape.rect(x + SIZE - 7f, y + SIZE - 18f, 7f, 7f);
    }

    public void draw(SpriteBatch batch) {
        if (walkAnimations == null) return;
        TextureRegion frame = walkAnimations[facing.ordinal()].getKeyFrame(stateTime, true);

        // Detected frame boundaries aren't perfectly uniform width, so scale per-frame
        // (height locked to SIZE, width proportional) rather than reusing one fixed size.
        float drawH = SIZE;
        float drawW = frame.getRegionWidth() * (SIZE / (float) frame.getRegionHeight());
        float drawX = x + (SIZE - drawW) / 2f;
        float drawY = y;

        boolean isDead = health <= 0f;

        // Attack lunge/pulse — visual-offset only, x/y (gameplay position) never changes.
        // Suppressed once dead so a stale timer can't animate a corpse.
        float offsetX = 0f, offsetY = 0f;
        float scale = 1f;
        if (!isDead && attackVisualTimer > 0f) {
            float progress = 1f - (attackVisualTimer / ATTACK_VISUAL_DURATION); // 0 at trigger -> 1 at end
            float curve = (float) Math.sin(progress * Math.PI); // eases out and back to 0, never snaps
            float lunge = curve * ATTACK_LUNGE_DISTANCE;
            switch (facing) {
                case DOWN:  offsetY = -lunge; break;
                case UP:    offsetY = lunge; break;
                case LEFT:  offsetX = -lunge; break;
                case RIGHT: offsetX = lunge; break;
            }
            scale = 1f + curve * ATTACK_SCALE_AMOUNT;
        }

        float rotation = 0f;
        float alpha = 1f;
        float tintG = 1f, tintB = 1f;

        // Death rendering takes priority over the hurt flash — a corpse never flashes red.
        if (isDead) {
            rotation = DEAD_ROTATION_DEGREES;
            alpha = DEAD_ALPHA;
        } else if (hurtVisualTimer > 0f) {
            float hurtProgress = hurtVisualTimer / HURT_FLASH_DURATION; // 1 at trigger -> 0 as it fades
            tintG = 1f - HURT_TINT_STRENGTH * hurtProgress;
            tintB = 1f - HURT_TINT_STRENGTH * hurtProgress;
        }

        // origin = sprite centre, so both the attack scale pulse and the dead rotation
        // pivot around the middle of the sprite rather than its bottom-left corner.
        batch.setColor(1f, tintG, tintB, alpha);
        batch.draw(frame, drawX + offsetX, drawY + offsetY, drawW / 2f, drawH / 2f, drawW, drawH,
            scale, scale, rotation);
        batch.setColor(1f, 1f, 1f, 1f); // restore — never leave the next draw call tinted/faded
    }

    public void dispose() {
        if (spriteSheet != null) spriteSheet.dispose();
    }
}
