package io.github.fableops;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;

import io.github.fableops.network.PlayerInput;

public class Player {

    public float x, y;
    public static final float SIZE = 100f;
    private static final float SPEED = 220f;
    private Texture texture;
    private Texture runTexture;
    private float idleDrawW, idleDrawH;
    private float runDrawW, runDrawH;
private boolean isMoving = false;

    public Color bodyColor;
    public Color accentColor;

    private int keyUp, keyDown, keyLeft, keyRight;
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

    // reads local keyboard — used by host for P1, by client for P2
    public PlayerInput readInput() {
        return new PlayerInput(
            Gdx.input.isKeyPressed(keyUp),
            Gdx.input.isKeyPressed(keyDown),
            Gdx.input.isKeyPressed(keyLeft),
            Gdx.input.isKeyPressed(keyRight)
        );
    }

    // Setter Method to set Sprites

    public void setTexture(String idleFile, String runFile)
    {
        if(texture!=null) texture.dispose();
        if(runTexture!=null) runTexture.dispose();
        texture=new Texture(Gdx.files.internal(idleFile));
        runTexture=new Texture(Gdx.files.internal(runFile));

        // Textures aren't square — lock height to SIZE and scale width to match,
        // so sprites aren't stretched. Width naturally varies by pose (a running
        // lunge is wider than a standing idle frame), which is correct.
        idleDrawH = SIZE;
        idleDrawW = texture.getWidth() * (SIZE / texture.getHeight());
        runDrawH = SIZE;
        runDrawW = runTexture.getWidth() * (SIZE / runTexture.getHeight());
    }

    // applies a received PlayerInput — used by host to move P2
    public void applyInput(PlayerInput input, float delta) {
        float dx = 0, dy = 0;
        if (input.up)    dy += SPEED * delta;
        if (input.down)  dy -= SPEED * delta;
        if (input.left)  dx -= SPEED * delta;
        if (input.right) dx += SPEED * delta;

        isMoving=(dx!=0 || dy!=0);

        if (!world.collides(x + dx, y, SIZE, SIZE, side)) x += dx;
        if (!world.collides(x, y + dy, SIZE, SIZE, side)) y += dy;

        updateCamera();
    }

    // reads keyboard and moves — used by host for P1
    public void update(float delta) {
        float dx = 0, dy = 0;
        if (Gdx.input.isKeyPressed(keyUp))    dy += SPEED * delta;
        if (Gdx.input.isKeyPressed(keyDown))  dy -= SPEED * delta;
        if (Gdx.input.isKeyPressed(keyLeft))  dx -= SPEED * delta;
        if (Gdx.input.isKeyPressed(keyRight)) dx += SPEED * delta;

        isMoving=(dx!=0 || dy!=0);
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

    public void draw(SpriteBatch batch)
    {
        if(texture==null || runTexture==null) return;
        if(isMoving)
        {
            float drawX = x + (SIZE - runDrawW) / 2f;
            batch.draw(runTexture, drawX, y, runDrawW, runDrawH);
        }
        else
        {
            float drawX = x + (SIZE - idleDrawW) / 2f;
            batch.draw(texture, drawX, y, idleDrawW, idleDrawH);
        }
    }


public void dispose() {
    if (texture != null) texture.dispose();
    if (runTexture != null) runTexture.dispose();
}
}