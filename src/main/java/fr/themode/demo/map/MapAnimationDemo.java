package fr.themode.demo.map;

import net.minestom.server.MinecraftServer;
import net.minestom.server.event.player.PlayerSpawnEvent;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.item.metadata.MapMeta;
import net.minestom.server.map.MapColors;
import net.minestom.server.map.framebuffers.Graphics2DFramebuffer;
import net.minestom.server.network.packet.server.play.MapDataPacket;
import net.minestom.server.timer.SchedulerManager;
import net.minestom.server.utils.time.TimeUnit;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

public class MapAnimationDemo {

    public static final int MAP_ID = 1;

    public static void init() {
        SchedulerManager scheduler = MinecraftServer.getSchedulerManager();
        scheduler.buildTask(MapAnimationDemo::tick).repeat(16, TimeUnit.MILLISECOND).schedule();

        MinecraftServer.getConnectionManager().addPlayerInitialization(player -> {
            player.addEventCallback(PlayerSpawnEvent.class, event -> {
                ItemStack map = new ItemStack(Material.FILLED_MAP, (byte) 1);
                map.setItemMeta(new MapMeta(MAP_ID));
                player.getInventory().addItemStack(map);
            });
        });
    }

    private static final Graphics2DFramebuffer framebuffer = new Graphics2DFramebuffer();

    private static float time = 0f;
    private static long lastTime = System.currentTimeMillis();

    public static void tick() {
        Graphics2D renderer = framebuffer.getRenderer();
        renderer.setColor(Color.BLACK);
        renderer.clearRect(0, 0, 128, 128);
        renderer.setColor(Color.WHITE);
        renderer.drawString("Hello from", 0, 10);
        renderer.drawString("Graphics2D!", 0, 20);

        long currentTime = System.currentTimeMillis();
        long l = currentTime / 60;
        if(l % 2 == 0) {
            renderer.setColor(Color.RED);
        }
        renderer.fillRect(128-10, 0, 10, 10);

        renderer.setColor(Color.GREEN);
        float dt = (currentTime-lastTime)/1000.0f;
        lastTime = currentTime;
        time += dt;
        float speed = 10f;
        int x = (int) (Math.cos(time*speed) * 10 + 64) - 25;
        int y = (int) (Math.sin(time*speed) * 10 + 64) - 10;
        renderer.fillRoundRect(x, y, 50, 20, 10, 10);

        MapDataPacket mapDataPacket = new MapDataPacket();
        mapDataPacket.mapId = MAP_ID;
        mapDataPacket.columns = 128;
        mapDataPacket.rows = 128;
        mapDataPacket.icons = new MapDataPacket.Icon[0];
        mapDataPacket.x = 0;
        mapDataPacket.z = 0;
        mapDataPacket.scale = 0;
        mapDataPacket.locked = true;
        mapDataPacket.trackingPosition = true;
        mapDataPacket.data = framebuffer.toMapColors();
        MinecraftServer.getConnectionManager().getOnlinePlayers().forEach(p -> {
            p.getPlayerConnection().sendPacket(mapDataPacket);
        });
    }
}
