package dan200.computercraft.client.render;

import dan200.computercraft.ComputerCraft;
import dan200.computercraft.client.gui.FixedWidthFontRenderer;
import dan200.computercraft.core.terminal.Terminal;
import dan200.computercraft.shared.common.ClientTerminal;
import dan200.computercraft.shared.peripheral.monitor.TileMonitor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

public class TileEntityMonitorRenderer
extends TileEntitySpecialRenderer {
    private static final ResourceLocation grey = new ResourceLocation("computercraft", "textures/gui/termgrey.png");
    private static final ResourceLocation black = new ResourceLocation("computercraft", "textures/gui/terminal.png");

    public void renderTileEntityMonitorAt(TileMonitor monitor, double xPos, double yPos, double zPos, float f) {
        boolean origin;
        boolean redraw = monitor.pollChanged();
        boolean bl = origin = monitor.getXIndex() == 0 && monitor.getYIndex() == 0;
        if (!origin) {
            if (monitor.m_renderDisplayList >= 0) {
                GL11.glDeleteLists((int)monitor.m_renderDisplayList, (int)2);
                monitor.m_renderDisplayList = -1;
            }
            return;
        }
        redraw = redraw || ((ClientTerminal)monitor.getTerminal()).hasTerminalChanged();
        Terminal terminal = monitor.getTerminal().getTerminal();
        if (monitor.m_renderDisplayList < 0) {
            monitor.m_renderDisplayList = GL11.glGenLists((int)2);
            redraw = true;
        }
        int dir = monitor.getDir() % 6;
        int dirAngle = monitor.getRenderFace();
        float angle = 0.0f;
        float rot = 0.0f;
        switch (dir) {
            case 2: {
                rot = 180.0f;
                break;
            }
            case 3: {
                rot = 0.0f;
                break;
            }
            case 4: {
                rot = 90.0f;
                break;
            }
            case 5: {
                rot = 270.0f;
            }
        }
        switch (dirAngle) {
            case 0: {
                angle = 270.0f;
                break;
            }
            case 1: {
                angle = 90.0f;
            }
        }
        GL11.glPushMatrix();
        GL11.glTranslatef((float)((float)xPos + 0.5f), (float)((float)yPos + 0.5f), (float)((float)zPos + 0.5f));
        GL11.glRotatef((float)(-rot), (float)0.0f, (float)1.0f, (float)0.0f);
        GL11.glRotatef((float)(-angle), (float)1.0f, (float)0.0f, (float)0.0f);
        GL11.glTranslatef((float)-0.34375f, (float)((float)monitor.getHeight() - 0.5f - 0.15625f), (float)0.5f);
        float xSize = (float)monitor.getWidth() - 0.3125f;
        float ySize = (float)monitor.getHeight() - 0.3125f;
        GL11.glDepthMask((boolean)false);
        Tessellator tessellator = Tessellator.field_78398_a;
        Minecraft mc = Minecraft.func_71410_x();
        mc.func_110434_K().func_110577_a(grey);
        tessellator.func_78382_b();
        tessellator.func_78375_b(0.0f, 0.0f, 1.0f);
        tessellator.func_78374_a(-0.03125, (double)(-ySize - 0.03125f), 0.0, 0.0, 1.0);
        tessellator.func_78374_a((double)(xSize + 0.03125f), (double)(-ySize - 0.03125f), 0.0, 1.0, 1.0);
        tessellator.func_78374_a((double)(xSize + 0.03125f), 0.03125, 0.0, 1.0, 0.0);
        tessellator.func_78374_a(-0.03125, 0.03125, 0.0, 0.0, 0.0);
        tessellator.func_78381_a();
        if (terminal != null) {
            float xScale = xSize / (float)(terminal.getWidth() * FixedWidthFontRenderer.FONT_WIDTH);
            float yScale = ySize / (float)(terminal.getHeight() * FixedWidthFontRenderer.FONT_HEIGHT);
            GL11.glPushMatrix();
            GL11.glScalef((float)xScale, (float)(-yScale), (float)1.0f);
            FixedWidthFontRenderer fontRenderer = (FixedWidthFontRenderer)ComputerCraft.getFixedWidthFontRenderer();
            int width = terminal.getWidth();
            int height = terminal.getHeight();
            if (redraw) {
                int cursorX = terminal.getCursorX();
                int cursorY = terminal.getCursorY();
                String emptyLine = terminal.getLine(-1);
                GL11.glNewList((int)monitor.m_renderDisplayList, (int)4864);
                float marginXSize = 0.03125f / xScale;
                float marginYSize = 0.03125f / yScale;
                float marginSquash = marginYSize / (float)FixedWidthFontRenderer.FONT_HEIGHT;
                GL11.glPushMatrix();
                GL11.glScalef((float)1.0f, (float)marginSquash, (float)1.0f);
                GL11.glTranslatef((float)0.0f, (float)(-marginYSize / marginSquash), (float)0.0f);
                fontRenderer.drawString(emptyLine, 0, 0, terminal.getColourLine(0), marginXSize, false);
                GL11.glTranslatef((float)0.0f, (float)((marginYSize + (float)(height * FixedWidthFontRenderer.FONT_HEIGHT)) / marginSquash), (float)0.0f);
                fontRenderer.drawString(emptyLine, 0, 0, terminal.getColourLine(height - 1), marginXSize, false);
                GL11.glPopMatrix();
                for (int y = 0; y < height; ++y) {
                    fontRenderer.drawString(terminal.getLine(y), 0, FixedWidthFontRenderer.FONT_HEIGHT * y, terminal.getColourLine(y), marginXSize, false);
                }
                GL11.glEndList();
                GL11.glNewList((int)(monitor.m_renderDisplayList + 1), (int)4864);
                if (terminal.getCursorBlink() && cursorX >= 0 && cursorX < width && cursorY >= 0 && cursorY < height) {
                    String cursorColour = "0123456789abcdef".charAt(terminal.getTextColour()) + "";
                    fontRenderer.drawString("_", FixedWidthFontRenderer.FONT_WIDTH * cursorX, FixedWidthFontRenderer.FONT_HEIGHT * cursorY, cursorColour, 0);
                }
                GL11.glEndList();
            }
            GL11.glBlendFunc((int)1, (int)0);
            GL11.glDisable((int)2896);
            GL11.glDepthMask((boolean)false);
            GL11.glCallList((int)monitor.m_renderDisplayList);
            if (ComputerCraft.getGlobalCursorBlink()) {
                GL11.glCallList((int)(monitor.m_renderDisplayList + 1));
            }
            GL11.glPopMatrix();
        }
        GL11.glDepthMask((boolean)true);
        GL11.glColorMask((boolean)false, (boolean)false, (boolean)false, (boolean)false);
        GL11.glDisable((int)3008);
        mc = Minecraft.func_71410_x();
        mc.func_110434_K().func_110577_a(black);
        tessellator.func_78382_b();
        tessellator.func_78375_b(0.0f, 0.0f, 1.0f);
        tessellator.func_78374_a(-0.03125, (double)(-ySize - 0.03125f), 0.0, 0.0, 1.0);
        tessellator.func_78374_a((double)(xSize + 0.03125f), (double)(-ySize - 0.03125f), 0.0, 1.0, 1.0);
        tessellator.func_78374_a((double)(xSize + 0.03125f), 0.03125, 0.0, 1.0, 0.0);
        tessellator.func_78374_a(-0.03125, 0.03125, 0.0, 0.0, 0.0);
        tessellator.func_78381_a();
        GL11.glDepthMask((boolean)true);
        GL11.glColorMask((boolean)true, (boolean)true, (boolean)true, (boolean)true);
        GL11.glEnable((int)3008);
        GL11.glBlendFunc((int)770, (int)771);
        GL11.glEnable((int)2896);
        GL11.glPopMatrix();
    }

    public void func_76894_a(TileEntity tileentity, double d, double d1, double d2, float f) {
        this.renderTileEntityMonitorAt((TileMonitor)tileentity, d, d1, d2, f);
    }
}
