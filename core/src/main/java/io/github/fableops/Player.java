package io.github.fableops;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Animation;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.math.Rectangle;

import io.github.fableops.network.PlayerInput;

// x, y is the bottom-left of the feet hitbox, the sprite is drawn around it
public class Player {

    // Same order as the rows in the sheets
    private enum Direction { DOWN, UP, LEFT, RIGHT }

    private static final Direction[] DIRECTIONS = Direction.values();
    private static final int SHEET_COLUMNS = 8;
    private static final int SHEET_ROWS = 4;
    private static final float FRAME_DURATION = 0.1f;

    private static final float SIZE = 100f;
    private static final float SPEED = 220f;
    public static final float MAX_HEALTH = 100f;
    public static final float INTERACT_RANGE = 80f;

    // One swing plays all 8 attack frames and the next swing can't start before it ends.
    // 0.35 / 8 is about 44 ms per frame, longer than a frame at 30 FPS, so none get skipped
    private static final float ATTACK_DURATION = 0.35f;
    // The fall plays once and then stays on its last frame
    public static final float DEATH_DURATION = 0.96f;
    private static final float HURT_FLASH_DURATION = 0.18f;
    private static final float HURT_TINT_STRENGTH = 0.65f;   // how far green and blue drop at peak flash

    public float x, y;
    public float health = MAX_HEALTH;
    public final OrthographicCamera camera = new OrthographicCamera();

    private final Texture sheet;
    private final Animation<TextureRegion>[] walk; // indexed by Direction.ordinal()
    private final SpriteBounds bounds;
    private final OverlaySheet attack;
    private final OverlaySheet death;
    private final int keyUp, keyDown, keyLeft, keyRight;
    // -1 means unset. It is checked explicitly because Input.Keys.ANY_KEY is also -1.
    private int keyRightAlt = -1;
    private final Collidable world;
    private final int side;

    private float camW, camH;
    private float stateTime = 0f;
    private Direction facing = Direction.DOWN;
    private Direction attackDirection = Direction.DOWN;
    private float attackTimer = 0f;
    private float hurtVisualTimer = 0f;
    private float deathTimer = 0f;
    // Per direction: 0 while its key is up, otherwise the order the keys went down in
    private final int[] pressOrder = new int[DIRECTIONS.length];
    private int pressCount = 0;

    // sheetName.png walks, sheetNameAttacking.png swings and sheetNameDeath.png falls. All three are
    // 8x4 with rows down, up, left, right. side = which wing this player can walk in Level 1
    @SuppressWarnings("unchecked")
    public Player(String sheetName, float x, float y, int keyUp, int keyDown, int keyLeft, int keyRight,
                  Collidable world, int side) {
        this.x = x;
        this.y = y;
        this.keyUp = keyUp;
        this.keyDown = keyDown;
        this.keyLeft = keyLeft;
        this.keyRight = keyRight;
        this.world = world;
        this.side = side;

        Pixmap pixmap = new Pixmap(Gdx.files.internal(sheetName + ".png"));
        int[] columns = boundaries(pixmap, true, SHEET_COLUMNS);
        int[] rows = boundaries(pixmap, false, SHEET_ROWS);
        sheet = new Texture(pixmap);

        TextureRegion[] allFrames = new TextureRegion[SHEET_ROWS * SHEET_COLUMNS];
        float[] scales = new float[allFrames.length];
        walk = new Animation[SHEET_ROWS];
        for (int row = 0; row < SHEET_ROWS; row++) {
            TextureRegion[] frames = new TextureRegion[SHEET_COLUMNS];
            for (int col = 0; col < SHEET_COLUMNS; col++) {
                TextureRegion frame = new TextureRegion(sheet, columns[col], rows[row],
                    columns[col + 1] - columns[col], rows[row + 1] - rows[row]);
                frames[col] = frame;
                allFrames[row * SHEET_COLUMNS + col] = frame;
                scales[row * SHEET_COLUMNS + col] = SIZE / frame.getRegionHeight();
            }
            walk[row] = new Animation<>(FRAME_DURATION, frames);
        }
        bounds = new SpriteBounds(pixmap, allFrames, scales, SIZE);

        attack = new OverlaySheet(pixmap, sheetName + "Attacking.png", ATTACK_DURATION);
        death = new OverlaySheet(pixmap, sheetName + "Death.png", DEATH_DURATION);
        pixmap.dispose();
    }

    // Cut at the gaps between frames, or an even grid if that doesn't work
    private static int[] boundaries(Pixmap pixmap, boolean horizontal, int frames) {
        int length = horizontal ? pixmap.getWidth() : pixmap.getHeight();
        int across = horizontal ? pixmap.getHeight() : pixmap.getWidth();
        int[] cuts = SpriteSheetSlicer.midpoints(
            SpriteSheetSlicer.runs(pixmap, horizontal, 0, across, 0), length, frames);
        return cuts != null ? cuts : SpriteSheetSlicer.uniform(length, frames);
    }

    // The attack and death art isn't drawn at the same size or spot as the walk art, so each row is
    // scaled until its first frame is as tall as the standing walk frame, with the feet and body centre lined up
    private final class OverlaySheet {

        private final Texture texture;
        private final Animation<TextureRegion>[] rows;
        // Per row: world units per sheet pixel, and where each frame is drawn relative to x, y
        private final float[] scale = new float[SHEET_ROWS];
        private final float[] offsetY = new float[SHEET_ROWS];
        private final float[][] offsetX = new float[SHEET_ROWS][SHEET_COLUMNS];

        @SuppressWarnings("unchecked")
        OverlaySheet(Pixmap walkPixmap, String file, float duration) {
            Pixmap pixmap = new Pixmap(Gdx.files.internal(file));
            texture = new Texture(pixmap);
            rows = new Animation[SHEET_ROWS];
            int[] rowCuts = boundaries(pixmap, false, SHEET_ROWS);
            int[] grid = SpriteSheetSlicer.uniform(pixmap.getWidth(), SHEET_COLUMNS);

            for (int row = 0; row < SHEET_ROWS; row++) {
                int rowHeight = rowCuts[row + 1] - rowCuts[row];
                // Slashes and fallen bodies cross the gaps between frames, so each row is cut on its own
                int[] columns = SpriteSheetSlicer.columnsInRow(pixmap, rowCuts[row], rowCuts[row + 1], SHEET_COLUMNS);
                TextureRegion[] frames = new TextureRegion[SHEET_COLUMNS];
                for (int col = 0; col < SHEET_COLUMNS; col++) {
                    frames[col] = new TextureRegion(texture, columns[col], rowCuts[row],
                        columns[col + 1] - columns[col], rowHeight);
                }
                rows[row] = new Animation<>(duration / SHEET_COLUMNS, frames);

                TextureRegion stand = walk[row].getKeyFrames()[0];
                int[] w = SpriteSheetSlicer.opaqueBounds(walkPixmap, stand); // {left, top, right, bottom}
                int[] a = SpriteSheetSlicer.opaqueBounds(pixmap, frames[0]);
                if (a[1] < 0) {
                    throw new IllegalStateException(file + " has an empty first frame in row " + row
                        + ", so it can't be lined up with the walk sprite.");
                }
                float walkScale = SIZE / stand.getRegionHeight();
                scale[row] = (w[3] - w[1] + 1) * walkScale / (a[3] - a[1] + 1);
                float walkLeft = -bounds.footX + (SIZE - stand.getRegionWidth() * walkScale) / 2f;
                float centreX = walkLeft + (w[0] + w[2] + 1) / 2f * walkScale;
                float feetY = -bounds.footY + (stand.getRegionHeight() - 1 - w[3]) * walkScale;

                offsetY[row] = feetY - (rowHeight - 1 - a[3]) * scale[row];
                float firstLeft = centreX - (a[0] + a[2] + 1) / 2f * scale[row];
                for (int col = 0; col < SHEET_COLUMNS; col++) {
                    // Keeps each frame where it sits in its grid cell, so lunges and falls still move
                    offsetX[row][col] = firstLeft + (columns[col] - grid[col]) * scale[row];
                }
            }
            pixmap.dispose();
        }

        // Time past the end stays on the last frame
        void draw(SpriteBatch batch, int row, float time) {
            int col = rows[row].getKeyFrameIndex(time);
            TextureRegion frame = rows[row].getKeyFrames()[col];
            batch.draw(frame, x + offsetX[row][col], y + offsetY[row],
                frame.getRegionWidth() * scale[row], frame.getRegionHeight() * scale[row]);
        }
    }

    // Only items heal, there is no regeneration
    public void heal(float amount) {
        if (amount <= 0f || health <= 0f) return;
        health = Math.min(MAX_HEALTH, health + amount);
    }

    public void takeDamage(float amount) {
        if (amount <= 0f) return;
        float newHealth = Math.max(0f, health - amount);
        if (newHealth < health) hurtVisualTimer = HURT_FLASH_DURATION;
        health = newHealth;
    }

    // Returns false while the last swing is still playing
    public boolean startAttack() {
        if (attackTimer > 0f) return false;
        attackTimer = ATTACK_DURATION;
        attackDirection = facing;
        return true;
    }

    public void updateVisualState(float delta) {
        attackTimer = Math.max(0f, attackTimer - delta);
        hurtVisualTimer = Math.max(0f, hurtVisualTimer - delta);
        deathTimer = (health <= 0f) ? deathTimer + delta : 0f;
    }

    public void resetVisualState() {
        attackTimer = 0f;
        hurtVisualTimer = 0f;
        deathTimer = 0f;
    }

    public void setAlternateRightKey(int key) {
        this.keyRightAlt = key;
    }

    public void update(float delta) {
        boolean right = Gdx.input.isKeyPressed(keyRight)
            || (keyRightAlt >= 0 && Gdx.input.isKeyPressed(keyRightAlt));
        steer(Gdx.input.isKeyPressed(keyUp), Gdx.input.isKeyPressed(keyDown),
            Gdx.input.isKeyPressed(keyLeft), right, delta);
    }

    // Used by the host for the client's player
    public void applyInput(PlayerInput input, float delta) {
        steer(input.up, input.down, input.left, input.right, delta);
    }

    // No diagonals: of the keys held, the one pressed first wins, and two opposite keys stop the player.
    // Keys that go down on the same frame prefer left/right, like the old diagonal facing did
    private void steer(boolean up, boolean down, boolean left, boolean right, float delta) {
        if (!up && !down && !left && !right) pressCount = 0;
        holdKey(Direction.LEFT, left);
        holdKey(Direction.RIGHT, right);
        holdKey(Direction.UP, up);
        holdKey(Direction.DOWN, down);

        Direction chosen = null;
        if (!(up && down) && !(left && right)) {
            for (Direction direction : DIRECTIONS) {
                int order = pressOrder[direction.ordinal()];
                if (order > 0 && (chosen == null || order < pressOrder[chosen.ordinal()])) chosen = direction;
            }
        }
        float step = SPEED * delta;
        float dx = (chosen == Direction.LEFT) ? -step : (chosen == Direction.RIGHT) ? step : 0f;
        float dy = (chosen == Direction.DOWN) ? -step : (chosen == Direction.UP) ? step : 0f;
        move(dx, dy, delta);
    }

    private void holdKey(Direction direction, boolean held) {
        int i = direction.ordinal();
        if (!held) pressOrder[i] = 0;
        else if (pressOrder[i] == 0) pressOrder[i] = ++pressCount;
    }

    private void move(float dx, float dy, float delta) {
        if (dx < 0) facing = Direction.LEFT;
        else if (dx > 0) facing = Direction.RIGHT;
        else if (dy > 0) facing = Direction.UP;
        else if (dy < 0) facing = Direction.DOWN;
        stateTime = (dx != 0 || dy != 0) ? stateTime + delta : 0f;

        if (dx != 0 && !world.collides(x + dx, y, bounds.footW, bounds.footH, side)) x += dx;
        if (dy != 0 && !world.collides(x, y + dy, bounds.footW, bounds.footH, side)) y += dy;
        updateCamera();
    }

    public void placeAt(float x, float y) {
        this.x = x;
        this.y = y;
        stateTime = 0f;
        updateCamera();
    }

    // The direction the sprite is drawn facing, sent to the client with the attack timer
    public int getDirection() {
        return (attackTimer > 0f ? attackDirection : facing).ordinal();
    }

    // Where the player faces, as a unit vector. Shots go this way
    public float facingX() {
        Direction direction = DIRECTIONS[getDirection()];
        return (direction == Direction.LEFT) ? -1f : (direction == Direction.RIGHT) ? 1f : 0f;
    }

    public float facingY() {
        Direction direction = DIRECTIONS[getDirection()];
        return (direction == Direction.DOWN) ? -1f : (direction == Direction.UP) ? 1f : 0f;
    }

    public float getAttackTimer() { return attackTimer; }

    public float getStateTime() { return stateTime; }

    // Client side: position and pose both come from the host
    public void applyRemote(float x, float y, int direction, float stateTime, float attackTimer) {
        this.x = x;
        this.y = y;
        facing = DIRECTIONS[direction];
        attackDirection = facing;
        this.stateTime = stateTime;
        this.attackTimer = attackTimer;
        updateCamera();
    }

    public void setCameraViewport(float camW, float camH) {
        this.camW = camW;
        this.camH = camH;
        camera.viewportWidth = camW;
        camera.viewportHeight = camH;
        updateCamera();
    }

    // Clamped so the camera never shows outside the map
    public void updateCamera() {
        float halfW = camW / 2f;
        float halfH = camH / 2f;
        camera.position.set(
            Math.max(halfW, Math.min(centreX(), world.getWorldWidth() - halfW)),
            Math.max(halfH, Math.min(centreY(), world.getWorldHeight() - halfH)), 0);
        camera.update();
    }

    public float colliderWidth() { return bounds.footW; }

    public float colliderHeight() { return bounds.footH; }

    public float colliderCentreX() { return x + bounds.footW / 2f; }

    public float colliderCentreY() { return y + bounds.footH / 2f; }

    // Body centre, used for reach and attacks
    public float centreX() { return x - bounds.footX + bounds.bodyX + bounds.bodyW / 2f; }

    public float centreY() { return y - bounds.footY + bounds.bodyY + bounds.bodyH / 2f; }

    public boolean colliderOverlaps(Rectangle zone) {
        return x < zone.x + zone.width && x + bounds.footW > zone.x
            && y < zone.y + zone.height && y + bounds.footH > zone.y;
    }

    // Used for enemy contact damage
    public boolean bodyOverlaps(float boxX, float boxY, float boxW, float boxH) {
        float bodyLeft = x - bounds.footX + bounds.bodyX;
        float bodyBottom = y - bounds.footY + bounds.bodyY;
        return bodyLeft < boxX + boxW && bodyLeft + bounds.bodyW > boxX
            && bodyBottom < boxY + boxH && bodyBottom + bounds.bodyH > boxY;
    }

    // Measured to the zone's nearest edge, because consoles are painted into the walls
    public boolean canReach(Rectangle zone) {
        float cx = centreX();
        float cy = centreY();
        float dx = Math.max(Math.max(zone.x - cx, 0f), cx - (zone.x + zone.width));
        float dy = Math.max(Math.max(zone.y - cy, 0f), cy - (zone.y + zone.height));
        return dx * dx + dy * dy <= INTERACT_RANGE * INTERACT_RANGE;
    }

    public void draw(SpriteBatch batch) {
        if (health <= 0f) {
            death.draw(batch, facing.ordinal(), deathTimer);
            return;
        }
        float tint = (hurtVisualTimer > 0f)
            ? 1f - HURT_TINT_STRENGTH * (hurtVisualTimer / HURT_FLASH_DURATION)
            : 1f;
        batch.setColor(1f, tint, tint, 1f);

        if (attackTimer > 0f) {
            attack.draw(batch, attackDirection.ordinal(), ATTACK_DURATION - attackTimer);
        } else {
            TextureRegion frame = walk[facing.ordinal()].getKeyFrame(stateTime, true);
            // Same placement SpriteBounds measured, so the feet line up with the hitbox
            float drawW = frame.getRegionWidth() * (SIZE / frame.getRegionHeight());
            batch.draw(frame, x - bounds.footX + (SIZE - drawW) / 2f, y - bounds.footY, drawW, SIZE);
        }
        batch.setColor(1f, 1f, 1f, 1f);
    }

    // For the inventory portrait
    public TextureRegion portraitFrame() {
        return walk[Direction.DOWN.ordinal()].getKeyFrames()[0];
    }

    public void dispose() {
        sheet.dispose();
        attack.texture.dispose();
        death.texture.dispose();
    }
}
